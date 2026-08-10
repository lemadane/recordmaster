package io.lemadane.recordmaster.core;

import io.lemadane.recordmaster.Record;
import java.io.*;
import java.util.*;
import java.util.function.Function;

public final class RecoveryManager {

    public interface RecoveryStorageHelper {
        Record readRecord(String tableName, Object id, RecordPointer ptr, Class<? extends Record> type) throws Exception;
        RecordPointer appendRecord(String tableName, Record record, byte[] bytes) throws Exception;
    }

    @SuppressWarnings("unchecked")
    public static DatabaseState recover(DatabaseState initialState, List<WalRecord> walRecords, RecoveryStorageHelper helper) throws Exception {
        if (walRecords.isEmpty()) {
            return initialState != null ? initialState : new DatabaseState(0);
        }

        // Group records by (transactionId, generation) composite key
        record TxKey(long txId, long generation) {}

        Map<TxKey, List<WalRecord>> txGroups = new LinkedHashMap<>();
        Set<TxKey> committedTxKeys = new LinkedHashSet<>();
        Set<TxKey> rolledBackTxKeys = new LinkedHashSet<>();

        for (WalRecord rec : walRecords) {
            TxKey key = new TxKey(rec.transactionId(), rec.generation());
            txGroups.computeIfAbsent(key, k -> new ArrayList<>()).add(rec);
            if (rec.type() == RecordWalOperation.COMMIT_TRANSACTION) {
                committedTxKeys.add(key);
            } else if (rec.type() == RecordWalOperation.ROLLBACK_TRANSACTION) {
                rolledBackTxKeys.add(key);
            }
        }

        // We only replay transactions that have committed and not rolled back.
        committedTxKeys.removeAll(rolledBackTxKeys);

        // Sort committed transactions by their generation
        List<TxKey> sortedTxKeys = new ArrayList<>(committedTxKeys);
        sortedTxKeys.sort(Comparator.comparingLong(TxKey::generation));

        DatabaseState dbState = initialState != null ? initialState.copy(initialState.generation()) : new DatabaseState(0);
        long currentGen = dbState.generation();

        for (TxKey txKey : sortedTxKeys) {
            if (initialState != null && txKey.generation() <= initialState.generation()) {
                continue;
            }
            List<WalRecord> recs = txGroups.get(txKey);
            for (WalRecord rec : recs) {
                currentGen = Math.max(currentGen, rec.generation());
                
                if (rec.type() == RecordWalOperation.BEGIN_TRANSACTION ||
                    rec.type() == RecordWalOperation.COMMIT_TRANSACTION ||
                    rec.type() == RecordWalOperation.ROLLBACK_TRANSACTION) {
                    continue;
                }

                try (ByteArrayInputStream bais = new ByteArrayInputStream(rec.payload());
                     DataInputStream dis = new DataInputStream(bais)) {

                    switch (rec.type()) {
                        case INSERT:
                        case UPSERT:
                        case UPDATE: {
                            String tableName = dis.readUTF();
                            String entityClassName = dis.readUTF();
                            int len = dis.readInt();
                            byte[] recBytes = new byte[len];
                            dis.readFully(recBytes);

                            Class<? extends Record> entityType = (Class<? extends Record>) Class.forName(entityClassName);
                            Record record = BinaryCodec.deserialize(recBytes, entityType);

                            TableState ts = dbState.getTable(tableName);
                            if (ts == null) {
                                // Dynamically recreate table state if not exists
                                ts = createTableState(dbState, tableName, entityType);
                                dbState.tables().put(tableName, ts);
                            }

                            Object id = ts.idExtractor().apply(record);
                            RecordPointer oldPtr = ts.recordPointers().get(id);
                            Record oldRecord = null;
                            if (oldPtr != null) {
                                oldRecord = helper.readRecord(tableName, id, oldPtr, entityType);
                            }

                            RecordPointer ptr = helper.appendRecord(tableName, record, recBytes);
                            if (rec.type() == RecordWalOperation.INSERT) {
                                ts.insert(record, ptr);
                            } else {
                                ts.update(record, oldRecord, ptr);
                            }
                            break;
                        }
                        case DELETE: {
                            String tableName = dis.readUTF();
                            String keyClassName = dis.readUTF();
                            Object key = BinaryCodec.readValue(dis);

                            TableState ts = dbState.getTable(tableName);
                            if (ts != null) {
                                RecordPointer oldPtr = ts.recordPointers().get(key);
                                Record oldRecord = null;
                                if (oldPtr != null) {
                                    oldRecord = helper.readRecord(tableName, key, oldPtr, ts.entityType());
                                }
                                ts.delete(key, oldRecord);
                            }
                            break;
                        }
                        case CLEAR_TABLE: {
                            String tableName = dis.readUTF();
                            TableState ts = dbState.getTable(tableName);
                            if (ts != null) {
                                ts.clear();
                            }
                            break;
                        }
                        case CREATE_TABLE: {
                            String tableName = dis.readUTF();
                            String idClassName = dis.readUTF();
                            String entityClassName = dis.readUTF();

                            Class<?> idType = Class.forName(idClassName);
                            Class<? extends Record> entityType = (Class<? extends Record>) Class.forName(entityClassName);

                            TableState ts = createTableState(dbState, tableName, entityType);
                            dbState.tables().put(tableName, ts);
                            break;
                        }
                        case DROP_TABLE: {
                            String tableName = dis.readUTF();
                            dbState.tables().remove(tableName);
                            break;
                        }
                        case CREATE_INDEX: {
                            String tableName = dis.readUTF();
                            String idxName = dis.readUTF();
                            String fieldName = dis.readUTF();
                            boolean unique = dis.readBoolean();
                            boolean ordered = dis.readBoolean();

                            TableState ts = dbState.getTable(tableName);
                            if (ts != null) {
                                if (!ts.indexes().containsKey(idxName)) {
                                    java.lang.reflect.Method accessor = ts.entityType().getMethod(fieldName);
                                    accessor.setAccessible(true);
                                    Function<Record, Object> extractor = r -> {
                                        try {
                                            return accessor.invoke(r);
                                        } catch (Exception e) {
                                            throw new RuntimeException(e);
                                        }
                                    };

                                    IndexMetadata meta = new IndexMetadata(idxName, fieldName, unique, ordered, extractor);
                                    ts.indexMetadataList().add(meta);
                                    ts.indexes().put(idxName, new IndexState(meta));

                                    // Re-populate index for existing records
                                    IndexState idxState = ts.indexes().get(idxName);
                                    for (Map.Entry<Object, RecordPointer> entry : ts.recordPointers().entrySet()) {
                                        Record record = helper.readRecord(tableName, entry.getKey(), entry.getValue(), ts.entityType());
                                        Object val = extractor.apply(record);
                                        idxState.add(val, entry.getKey());
                                    }
                                }
                            }
                            break;
                        }
                        case DROP_INDEX: {
                            String tableName = dis.readUTF();
                            String idxName = dis.readUTF();
                            TableState ts = dbState.getTable(tableName);
                            if (ts != null) {
                                ts.indexes().remove(idxName);
                                ts.indexMetadataList().removeIf(m -> m.indexName().equals(idxName));
                            }
                            break;
                        }
                    }
                }
            }
        }

        return dbState.copy(currentGen);
    }

    private static TableState createTableState(DatabaseState dbState, String tableName, Class<? extends Record> entityType) throws Exception {
        // Resolve primary key extractor
        java.lang.reflect.Method idAccessor = null;
        for (java.lang.reflect.Method m : entityType.getMethods()) {
            if (m.isAnnotationPresent(io.lemadane.recordmaster.annotations.Id.class) || m.getName().equalsIgnoreCase("id")) {
                idAccessor = m;
                break;
            }
        }
        if (idAccessor == null && entityType.getRecordComponents().length > 0) {
            idAccessor = entityType.getMethod(entityType.getRecordComponents()[0].getName());
        }
        if (idAccessor == null) {
            throw new IllegalStateException("Cannot find ID accessor for " + entityType.getName());
        }
        idAccessor.setAccessible(true);
        java.lang.reflect.Method finalIdAccessor = idAccessor;
        Function<Record, Object> idExtractor = r -> {
            try {
                return finalIdAccessor.invoke(r);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };

        // Return table state with empty initial index list.
        // Indexes will be created explicitly via CREATE_INDEX WAL records.
        return new TableState(tableName, idAccessor.getReturnType(), entityType, idExtractor, new ArrayList<>());
    }
}
