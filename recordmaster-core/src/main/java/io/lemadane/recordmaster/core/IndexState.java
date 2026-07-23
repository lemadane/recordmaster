package io.lemadane.recordmaster.core;

import java.util.*;

public final class IndexState {
    private final IndexMetadata metadata;
    private final Map<Object, Object> uniqueMap;
    private final Map<Object, Set<Object>> secondaryMap;

    public IndexState(IndexMetadata metadata) {
        this.metadata = metadata;
        this.uniqueMap = metadata.unique() ? new HashMap<>() : null;
        this.secondaryMap = metadata.unique() ? null : 
                (metadata.ordered() ? new TreeMap<>() : new HashMap<>());
    }

    private IndexState(IndexMetadata metadata, Map<Object, Object> uniqueMap, Map<Object, Set<Object>> secondaryMap) {
        this.metadata = metadata;
        this.uniqueMap = uniqueMap;
        this.secondaryMap = secondaryMap;
    }

    public IndexMetadata metadata() {
        return metadata;
    }

    public IndexState copy() {
        if (metadata.unique()) {
            return new IndexState(metadata, new HashMap<>(uniqueMap), null);
        } else {
            Map<Object, Set<Object>> newSecMap = metadata.ordered() ? new TreeMap<>() : new HashMap<>();
            for (Map.Entry<Object, Set<Object>> entry : secondaryMap.entrySet()) {
                newSecMap.put(entry.getKey(), new LinkedHashSet<>(entry.getValue()));
            }
            return new IndexState(metadata, null, newSecMap);
        }
    }

    public void add(Object indexVal, Object primaryKey) {
        if (indexVal == null) return;
        if (metadata.unique()) {
            uniqueMap.put(indexVal, primaryKey);
        } else {
            secondaryMap.computeIfAbsent(indexVal, k -> new LinkedHashSet<>()).add(primaryKey);
        }
    }

    public void remove(Object indexVal, Object primaryKey) {
        if (indexVal == null) return;
        if (metadata.unique()) {
            uniqueMap.remove(indexVal);
        } else {
            Set<Object> keys = secondaryMap.get(indexVal);
            if (keys != null) {
                keys.remove(primaryKey);
                if (keys.isEmpty()) {
                    secondaryMap.remove(indexVal);
                }
            }
        }
    }

    public Map<Object, Object> getUniqueMap() {
        return uniqueMap;
    }

    public Map<Object, Set<Object>> getSecondaryMap() {
        return secondaryMap;
    }

    public void clear() {
        if (uniqueMap != null) {
            uniqueMap.clear();
        }
        if (secondaryMap != null) {
            secondaryMap.clear();
        }
    }
}
