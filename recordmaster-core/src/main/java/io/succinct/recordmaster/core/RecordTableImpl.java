package io.succinct.recordmaster.core;

import io.succinct.recordmaster.*;
import java.util.*;
import java.util.function.Function;

public final class RecordTableImpl<ID, T extends io.succinct.recordmaster.Record> implements RecordTable<ID, T> {

    private final String tableName;
    private final Class<ID> idType;
    private final Class<T> entityType;
    private final Function<T, ID> idExtractor;
    
    private final RecordDatabase db;
    private final RecordTransactionImpl transaction;

    public RecordTableImpl(String tableName, Class<ID> idType, Class<T> entityType, Function<T, ID> idExtractor, RecordDatabase db) {
        this.tableName = tableName;
        this.idType = idType;
        this.entityType = entityType;
        this.idExtractor = idExtractor;
        this.db = db;
        this.transaction = null;
    }

    public RecordTableImpl(String tableName, Class<ID> idType, Class<T> entityType, Function<T, ID> idExtractor, RecordDatabase db, RecordTransactionImpl transaction) {
        this.tableName = tableName;
        this.idType = idType;
        this.entityType = entityType;
        this.idExtractor = idExtractor;
        this.db = db;
        this.transaction = transaction;
    }

    @Override
    public String tableName() {
        return tableName;
    }

    @Override
    public Class<ID> idType() {
        return idType;
    }

    @Override
    public Class<T> entityType() {
        return entityType;
    }

    private RecordTransactionImpl getActiveTransaction() {
        if (transaction != null) {
            if (!transaction.isActive()) {
                throw new InvalidTransactionStateException("Transaction is closed or committed/rolled back");
            }
            return transaction;
        }
        return null;
    }

    private TableState getTableState(RecordTransactionImpl tx) {
        TableState ts = tx.getCommittedDbState().getTable(tableName);
        if (ts == null) {
            ts = db.getCommittedState().getTable(tableName);
        }
        return ts;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<T> findById(ID id) {
        if (id == null) return Optional.empty();
        RecordTransactionImpl tx = getActiveTransaction();
        if (tx != null) {
            TableChangeSet cs = tx.getChangeSet().getTableChanges(tableName);
            if (cs.isCleared() && !cs.getInserts().containsKey(id)) {
                return Optional.empty();
            }
            if (cs.getDeletes().contains(id)) {
                return Optional.empty();
            }
            if (cs.getInserts().containsKey(id)) {
                return Optional.of((T) cs.getInserts().get(id));
            }
            if (cs.getUpdates().containsKey(id)) {
                return Optional.of((T) cs.getUpdates().get(id));
            }
            TableState committed = getTableState(tx);
            if (committed != null) {
                return Optional.ofNullable((T) committed.records().get(id));
            }
            return Optional.empty();
        } else {
            TableState committed = db.getCommittedState().getTable(tableName);
            if (committed != null) {
                return Optional.ofNullable((T) committed.records().get(id));
            }
            return Optional.empty();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public T insert(T record) {
        if (record == null) throw new IllegalArgumentException("Record cannot be null");
        ID id = idExtractor.apply(record);
        if (id == null) throw new IllegalArgumentException("Primary key (ID) cannot be null");

        RecordTransactionImpl tx = getActiveTransaction();
        if (tx != null) {
            TableChangeSet cs = tx.getChangeSet().getTableChanges(tableName);
            
            boolean exists = false;
            if (cs.getInserts().containsKey(id) || cs.getUpdates().containsKey(id)) {
                exists = true;
            } else if (!cs.isCleared() && !cs.getDeletes().contains(id)) {
                TableState committed = getTableState(tx);
                if (committed != null && committed.records().containsKey(id)) {
                    exists = true;
                }
            }
            if (exists) {
                throw new RecordMasterException("Record with ID '" + id + "' already exists in table '" + tableName + "'");
            }

            cs.insert(id, record);

            TableState ts = getTableState(tx);
            if (ts != null) {
                for (IndexMetadata meta : ts.indexMetadataList()) {
                    IndexId indexId = new IndexId(tableName, meta.indexName());
                    Object indexVal = meta.extractor().apply(record);
                    tx.getChangeSet().getIndexChanges().add(indexId, indexVal, id);
                }
            }
            return record;
        } else {
            return db.transaction((TransactionFunction<T>) t -> t.table(tableName, idType, entityType, idExtractor).insert(record));
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public T update(T record) {
        if (record == null) throw new IllegalArgumentException("Record cannot be null");
        ID id = idExtractor.apply(record);
        if (id == null) throw new IllegalArgumentException("Primary key (ID) cannot be null");

        RecordTransactionImpl tx = getActiveTransaction();
        if (tx != null) {
            TableChangeSet cs = tx.getChangeSet().getTableChanges(tableName);
            T oldRecord = null;
            if (cs.getInserts().containsKey(id)) {
                oldRecord = (T) cs.getInserts().get(id);
            } else if (cs.getUpdates().containsKey(id)) {
                oldRecord = (T) cs.getUpdates().get(id);
            } else if (!cs.isCleared() && !cs.getDeletes().contains(id)) {
                TableState committed = getTableState(tx);
                if (committed != null) {
                    oldRecord = (T) committed.records().get(id);
                }
            }

            if (oldRecord == null) {
                throw new RecordMasterException("Record with ID '" + id + "' does not exist in table '" + tableName + "'");
            }

            cs.update(id, record);

            TableState ts = getTableState(tx);
            if (ts != null) {
                for (IndexMetadata meta : ts.indexMetadataList()) {
                    IndexId indexId = new IndexId(tableName, meta.indexName());
                    Object oldIndexVal = meta.extractor().apply(oldRecord);
                    Object newIndexVal = meta.extractor().apply(record);
                    if (!Objects.equals(oldIndexVal, newIndexVal)) {
                        if (oldIndexVal != null) {
                            tx.getChangeSet().getIndexChanges().remove(indexId, oldIndexVal, id);
                        }
                        tx.getChangeSet().getIndexChanges().add(indexId, newIndexVal, id);
                    }
                }
            }
            return record;
        } else {
            return db.transaction((TransactionFunction<T>) t -> t.table(tableName, idType, entityType, idExtractor).update(record));
        }
    }

    @Override
    public T upsert(T record) {
        if (record == null) throw new IllegalArgumentException("Record cannot be null");
        ID id = idExtractor.apply(record);
        if (id == null) throw new IllegalArgumentException("Primary key (ID) cannot be null");

        RecordTransactionImpl tx = getActiveTransaction();
        if (tx != null) {
            TableChangeSet cs = tx.getChangeSet().getTableChanges(tableName);
            boolean exists = false;
            if (cs.getInserts().containsKey(id) || cs.getUpdates().containsKey(id)) {
                exists = true;
            } else if (!cs.isCleared() && !cs.getDeletes().contains(id)) {
                TableState committed = getTableState(tx);
                if (committed != null && committed.records().containsKey(id)) {
                    exists = true;
                }
            }

            if (exists) {
                return update(record);
            } else {
                return insert(record);
            }
        } else {
            return db.transaction((TransactionFunction<T>) t -> t.table(tableName, idType, entityType, idExtractor).upsert(record));
        }
    }

    @Override
    public boolean deleteById(ID id) {
        if (id == null) return false;
        RecordTransactionImpl tx = getActiveTransaction();
        if (tx != null) {
            TableChangeSet cs = tx.getChangeSet().getTableChanges(tableName);
            T oldRecord = null;
            boolean present = false;
            
            if (cs.getInserts().containsKey(id)) {
                oldRecord = (T) cs.getInserts().get(id);
                present = true;
            } else if (cs.getUpdates().containsKey(id)) {
                oldRecord = (T) cs.getUpdates().get(id);
                present = true;
            } else if (!cs.isCleared() && !cs.getDeletes().contains(id)) {
                TableState committed = getTableState(tx);
                if (committed != null && committed.records().containsKey(id)) {
                    oldRecord = (T) committed.records().get(id);
                    present = true;
                }
            }

            if (!present) {
                return false;
            }

            cs.delete(id);

            TableState ts = getTableState(tx);
            if (ts != null && oldRecord != null) {
                for (IndexMetadata meta : ts.indexMetadataList()) {
                    IndexId indexId = new IndexId(tableName, meta.indexName());
                    Object indexVal = meta.extractor().apply(oldRecord);
                    tx.getChangeSet().getIndexChanges().remove(indexId, indexVal, id);
                }
            }
            return true;
        } else {
            return db.transaction((TransactionFunction<Boolean>) t -> t.table(tableName, idType, entityType, idExtractor).deleteById(id));
        }
    }

    @Override
    public void clear() {
        RecordTransactionImpl tx = getActiveTransaction();
        if (tx != null) {
            TableChangeSet cs = tx.getChangeSet().getTableChanges(tableName);
            cs.clear();
            tx.getChangeSet().getIndexChanges().clear();
        } else {
            db.transaction((TransactionConsumer) t -> {
                t.table(tableName, idType, entityType, idExtractor).clear();
            });
        }
    }

    @Override
    public Query<T> query() {
        RecordTransactionImpl tx = getActiveTransaction();
        if (tx != null) {
            TableState committed = getTableState(tx);
            TableChangeSet cs = tx.getChangeSet().getTableChanges(tableName);
            IndexChangeSet ics = tx.getChangeSet().getIndexChanges();
            return new QueryEngine<>(tableName, committed, cs, ics);
        } else {
            TableState committed = db.getCommittedState().getTable(tableName);
            return new QueryEngine<>(tableName, committed, null, null);
        }
    }
}
