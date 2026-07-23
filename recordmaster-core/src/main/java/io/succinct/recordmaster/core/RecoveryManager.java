package io.succinct.recordmaster.core;

import io.succinct.recordmaster.Record;
import java.io.*;
import java.util.*;
import java.util.function.Function;

public final class RecoveryManager {

    @SuppressWarnings("unchecked")
    public static DatabaseState recover(DatabaseState initialState, List<WalRecord> walRecords) throws Exception {
        if (walRecords.isEmpty()) {
            return initialState != null ? initialState : new DatabaseState(0);
        }

        // Group records by transactionId
        Map<Long, List<WalRecord>> txGroups = new LinkedHashMap<>();
        Set<Long> committedTxIds = new LinkedHashSet<>();
        Set<Long> rolledBackTxIds = new LinkedHashSet<>();

        for (WalRecord rec : walRecords) {
            txGroups.computeIfAbsent(rec.transactionId(), k -> new ArrayList<>()).add(rec);
            if (rec.type() == RecordWalOperation.COMMIT_TRANSACTION) {
                committedTxIds.add(rec.transactionId());
            } else if (rec.type() == RecordWalOperation.ROLLBACK_TRANSACTION) {
                rolledBackTxIds.add(rec.transactionId());
            }
        }

        // We only replay transactions that have committed and not rolled back.
        committedTxIds.removeAll(rolledBackTxIds);

        // Sort committed transactions by their generation (using the generation of their COMMIT record, or the first record)
        List<Long> sortedTxIds = new ArrayList<>(committedTxIds);
        sortedTxIds.sort(Comparator.comparingLong(txId -> {
            List<WalRecord> recs = txGroups.get(txId);
            return recs.isEmpty() ? 0L : recs.get(0).generation();
        }));

        DatabaseState dbState = initialState != null ? initialState.copy(initialState.generation()) : new DatabaseState(0);
        long currentGen = dbState.generation();

        for (long txId : sortedTxIds) {
            List<WalRecord> recs = txGroups.get(txId);
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
                            Record oldRecord = ts.records().get(id);
                            if (rec.type() == RecordWalOperation.INSERT) {
                                ts.insert(record);
                            } else {
                                ts.update(record, oldRecord);
                            }
                            break;
                        }
                        case DELETE: {
                            String tableName = dis.readUTF();
                            String keyClassName = dis.readUTF();
                            Object key = BinaryCodec.readValue(dis);

                            TableState ts = dbState.getTable(tableName);
                            if (ts != null) {
                                ts.delete(key);
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
                            boolean unique = dis.readBoolean();
                            boolean ordered = dis.readBoolean();

                            TableState ts = dbState.getTable(tableName);
                            if (ts != null) {
                                // Add index metadata
                                String fieldName = idxName.substring((tableName + "_").length(), idxName.length() - "_idx".length());
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
                                for (Map.Entry<Object, Record> entry : ts.records().entrySet()) {
                                    Object val = extractor.apply(entry.getValue());
                                    idxState.add(val, entry.getKey());
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
            if (m.isAnnotationPresent(io.succinct.recordmaster.annotations.Id.class) || m.getName().equalsIgnoreCase("id")) {
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

        // Scan for @Index and @Indexes annotations
        List<IndexMetadata> indexMetadataList = new ArrayList<>();
        for (java.lang.reflect.RecordComponent comp : entityType.getRecordComponents()) {
            String fieldName = comp.getName();
            java.lang.reflect.Method accessor = comp.getAccessor();
            accessor.setAccessible(true);
            Function<Record, Object> extractor = r -> {
                try {
                    return accessor.invoke(r);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            };

            List<io.succinct.recordmaster.annotations.Index> idxAnnots = new ArrayList<>();
            if (comp.isAnnotationPresent(io.succinct.recordmaster.annotations.Index.class)) {
                idxAnnots.add(comp.getAnnotation(io.succinct.recordmaster.annotations.Index.class));
            }
            if (comp.isAnnotationPresent(io.succinct.recordmaster.annotations.Indexes.class)) {
                idxAnnots.addAll(Arrays.asList(comp.getAnnotation(io.succinct.recordmaster.annotations.Indexes.class).value()));
            }

            for (io.succinct.recordmaster.annotations.Index idx : idxAnnots) {
                String name = idx.name();
                if (name.isEmpty()) {
                    name = tableName + "_" + fieldName + "_idx";
                }
                indexMetadataList.add(new IndexMetadata(name, fieldName, idx.unique(), idx.ordered(), extractor));
            }
        }

        return new TableState(tableName, idAccessor.getReturnType(), entityType, idExtractor, indexMetadataList);
    }
}
