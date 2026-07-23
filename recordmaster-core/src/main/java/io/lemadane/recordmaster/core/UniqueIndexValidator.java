package io.lemadane.recordmaster.core;

import io.lemadane.recordmaster.DuplicateIndexValueException;
import io.lemadane.recordmaster.Record;
import io.lemadane.recordmaster.RecordDatabase;
import java.util.*;

public final class UniqueIndexValidator {

    public static void validate(RecordDatabase db, DatabaseState committedDbState, TransactionChangeSet changeSet) {
        for (Map.Entry<String, TableChangeSet> tableEntry : changeSet.getAllTableChanges().entrySet()) {
            String tableName = tableEntry.getKey();
            TableChangeSet tableChanges = tableEntry.getValue();
            if (tableChanges.isEmpty()) {
                continue;
            }

            TableState committedTable = committedDbState.getTable(tableName);
            if (committedTable == null) {
                continue; 
            }

            for (IndexState committedIndex : committedTable.indexes().values()) {
                if (!committedIndex.metadata().unique()) {
                    continue;
                }

                IndexMetadata meta = committedIndex.metadata();
                Map<Object, Object> finalUniqueMap = new HashMap<>();

                if (!tableChanges.isCleared()) {
                    if (committedIndex.getUniqueMap() != null) {
                        finalUniqueMap.putAll(committedIndex.getUniqueMap());
                    }
                }

                for (Object key : tableChanges.getDeletes()) {
                    RecordPointer ptr = committedTable.recordPointers().get(key);
                    if (ptr != null) {
                        try {
                            byte[] bytes = db.getTableStorage(tableName).readRecord(ptr);
                            Record committedRecord = BinaryCodec.deserialize(bytes, committedTable.entityType());
                            Object indexVal = meta.extractor().apply(committedRecord);
                            if (indexVal != null) {
                                finalUniqueMap.remove(indexVal);
                            }
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to read record for unique index validation", e);
                        }
                    }
                }

                for (Object key : tableChanges.getUpdates().keySet()) {
                    RecordPointer ptr = committedTable.recordPointers().get(key);
                    if (ptr != null) {
                        try {
                            byte[] bytes = db.getTableStorage(tableName).readRecord(ptr);
                            Record committedRecord = BinaryCodec.deserialize(bytes, committedTable.entityType());
                            Object indexVal = meta.extractor().apply(committedRecord);
                            if (indexVal != null) {
                                finalUniqueMap.remove(indexVal);
                            }
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to read record for unique index validation", e);
                        }
                    }
                }

                for (Map.Entry<Object, Record> entry : tableChanges.getInserts().entrySet()) {
                    Object key = entry.getKey();
                    Record rec = entry.getValue();
                    Object indexVal = meta.extractor().apply(rec);
                    if (indexVal != null) {
                        Object existingKey = finalUniqueMap.get(indexVal);
                        if (existingKey != null && !Objects.equals(existingKey, key)) {
                            throw new DuplicateIndexValueException(tableName, meta.indexName(), indexVal,
                                    "Duplicate value '" + indexVal + "' in unique index '" + meta.indexName() +
                                    "' on table '" + tableName + "'");
                        }
                        finalUniqueMap.put(indexVal, key);
                    }
                }

                for (Map.Entry<Object, Record> entry : tableChanges.getUpdates().entrySet()) {
                    Object key = entry.getKey();
                    Record rec = entry.getValue();
                    Object indexVal = meta.extractor().apply(rec);
                    if (indexVal != null) {
                        Object existingKey = finalUniqueMap.get(indexVal);
                        if (existingKey != null && !Objects.equals(existingKey, key)) {
                            throw new DuplicateIndexValueException(tableName, meta.indexName(), indexVal,
                                    "Duplicate value '" + indexVal + "' in unique index '" + meta.indexName() +
                                    "' on table '" + tableName + "'");
                        }
                        finalUniqueMap.put(indexVal, key);
                    }
                }
            }
        }
    }
}
