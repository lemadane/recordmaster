package io.succinct.recordmaster.core;

import io.succinct.recordmaster.Record;
import java.util.*;
import java.util.function.Function;

public final class TableState {
    private final String tableName;
    private final Class<?> idType;
    private final Class<? extends Record> entityType;
    private final Function<Record, Object> idExtractor;
    private final Map<Object, Record> records;
    private final Map<String, IndexState> indexes;
    private final List<IndexMetadata> indexMetadataList;

    public TableState(String tableName, Class<?> idType, Class<? extends Record> entityType, Function<Record, Object> idExtractor, List<IndexMetadata> indexMetadataList) {
        this.tableName = tableName;
        this.idType = idType;
        this.entityType = entityType;
        this.idExtractor = idExtractor;
        this.records = new LinkedHashMap<>();
        this.indexes = new HashMap<>();
        this.indexMetadataList = indexMetadataList;
        for (IndexMetadata meta : indexMetadataList) {
            this.indexes.put(meta.indexName(), new IndexState(meta));
        }
    }

    private TableState(String tableName, Class<?> idType, Class<? extends Record> entityType, Function<Record, Object> idExtractor, Map<Object, Record> records, Map<String, IndexState> indexes, List<IndexMetadata> indexMetadataList) {
        this.tableName = tableName;
        this.idType = idType;
        this.entityType = entityType;
        this.idExtractor = idExtractor;
        this.records = records;
        this.indexes = indexes;
        this.indexMetadataList = indexMetadataList;
    }

    public String tableName() {
        return tableName;
    }

    public Class<?> idType() {
        return idType;
    }

    public Class<? extends Record> entityType() {
        return entityType;
    }

    @SuppressWarnings("unchecked")
    public <T extends Record> Function<T, Object> idExtractor() {
        return (Function<T, Object>) idExtractor;
    }

    public Map<Object, Record> records() {
        return records;
    }

    public Map<String, IndexState> indexes() {
        return indexes;
    }

    public List<IndexMetadata> indexMetadataList() {
        return indexMetadataList;
    }

    public TableState copy() {
        Map<Object, Record> newRecords = new LinkedHashMap<>(this.records);
        Map<String, IndexState> newIndexes = new HashMap<>();
        for (Map.Entry<String, IndexState> entry : this.indexes.entrySet()) {
            newIndexes.put(entry.getKey(), entry.getValue().copy());
        }
        return new TableState(tableName, idType, entityType, idExtractor, newRecords, newIndexes, indexMetadataList);
    }

    public void insert(Record record) {
        Object id = idExtractor.apply(record);
        records.put(id, record);
        for (IndexState idx : indexes.values()) {
            Object indexVal = idx.metadata().extractor().apply(record);
            idx.add(indexVal, id);
        }
    }

    public void update(Record record, Record oldRecord) {
        Object id = idExtractor.apply(record);
        records.put(id, record);
        for (IndexState idx : indexes.values()) {
            Object oldVal = oldRecord == null ? null : idx.metadata().extractor().apply(oldRecord);
            Object newVal = idx.metadata().extractor().apply(record);
            if (!Objects.equals(oldVal, newVal)) {
                if (oldVal != null) {
                    idx.remove(oldVal, id);
                }
                idx.add(newVal, id);
            }
        }
    }

    public void delete(Object id) {
        Record record = records.remove(id);
        if (record != null) {
            for (IndexState idx : indexes.values()) {
                Object indexVal = idx.metadata().extractor().apply(record);
                idx.remove(indexVal, id);
            }
        }
    }

    public void clear() {
        records.clear();
        for (IndexState idx : indexes.values()) {
            if (idx.metadata().unique()) {
                idx.getUniqueMap().clear();
            } else {
                idx.getSecondaryMap().clear();
            }
        }
    }
}
