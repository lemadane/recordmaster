package io.succinct.recordmaster.core;

import io.succinct.recordmaster.Record;
import java.util.*;
import java.util.function.Function;

public final class TableState {
    private final String tableName;
    private final Class<?> idType;
    private final Class<? extends Record> entityType;
    private final Function<Record, Object> idExtractor;
    private final Map<Object, RecordPointer> recordPointers;
    private final Map<String, IndexState> indexes;
    private final List<IndexMetadata> indexMetadataList;

    public TableState(String tableName, Class<?> idType, Class<? extends Record> entityType, Function<Record, Object> idExtractor, List<IndexMetadata> indexMetadataList) {
        this.tableName = tableName;
        this.idType = idType;
        this.entityType = entityType;
        this.idExtractor = idExtractor;
        this.recordPointers = new LinkedHashMap<>();
        this.indexes = new HashMap<>();
        this.indexMetadataList = indexMetadataList;
        for (IndexMetadata meta : indexMetadataList) {
            this.indexes.put(meta.indexName(), new IndexState(meta));
        }
    }

    private TableState(String tableName, Class<?> idType, Class<? extends Record> entityType, Function<Record, Object> idExtractor, Map<Object, RecordPointer> recordPointers, Map<String, IndexState> indexes, List<IndexMetadata> indexMetadataList) {
        this.tableName = tableName;
        this.idType = idType;
        this.entityType = entityType;
        this.idExtractor = idExtractor;
        this.recordPointers = recordPointers;
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

    public Map<Object, RecordPointer> recordPointers() {
        return recordPointers;
    }

    public Map<String, IndexState> indexes() {
        return indexes;
    }

    public List<IndexMetadata> indexMetadataList() {
        return indexMetadataList;
    }

    public TableState copy() {
        Map<Object, RecordPointer> newRecordPointers = new LinkedHashMap<>(this.recordPointers);
        Map<String, IndexState> newIndexes = new HashMap<>();
        for (Map.Entry<String, IndexState> entry : this.indexes.entrySet()) {
            newIndexes.put(entry.getKey(), entry.getValue().copy());
        }
        return new TableState(tableName, idType, entityType, idExtractor, newRecordPointers, newIndexes, indexMetadataList);
    }

    public void insert(Record record, RecordPointer ptr) {
        Object id = idExtractor.apply(record);
        recordPointers.put(id, ptr);
        for (IndexState idx : indexes.values()) {
            Object indexVal = idx.metadata().extractor().apply(record);
            idx.add(indexVal, id);
        }
    }

    public void update(Record record, Record oldRecord, RecordPointer ptr) {
        Object id = idExtractor.apply(record);
        recordPointers.put(id, ptr);
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

    public void delete(Object key, Record oldRecord) {
        recordPointers.remove(key);
        if (oldRecord != null) {
            for (IndexState idx : indexes.values()) {
                Object indexVal = idx.metadata().extractor().apply(oldRecord);
                if (indexVal != null) {
                    idx.remove(indexVal, key);
                }
            }
        }
    }

    public void clear() {
        recordPointers.clear();
        for (IndexState idx : indexes.values()) {
            idx.clear();
        }
    }
}
