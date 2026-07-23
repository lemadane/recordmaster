package io.lemadane.recordmaster.core;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class IndexChangeSet {
    private final Map<IndexId, Set<IndexEntry>> additions = new LinkedHashMap<>();
    private final Map<IndexId, Set<IndexEntry>> removals = new LinkedHashMap<>();

    public void add(IndexId indexId, Object indexValue, Object primaryKey) {
        additions.computeIfAbsent(indexId, k -> new LinkedHashSet<>()).add(new IndexEntry(indexValue, primaryKey));
        Set<IndexEntry> rems = removals.get(indexId);
        if (rems != null) {
            rems.remove(new IndexEntry(indexValue, primaryKey));
        }
    }

    public void remove(IndexId indexId, Object indexValue, Object primaryKey) {
        removals.computeIfAbsent(indexId, k -> new LinkedHashSet<>()).add(new IndexEntry(indexValue, primaryKey));
        Set<IndexEntry> adds = additions.get(indexId);
        if (adds != null) {
            adds.remove(new IndexEntry(indexValue, primaryKey));
        }
    }

    public Map<IndexId, Set<IndexEntry>> getAdditions() {
        return additions;
    }

    public Map<IndexId, Set<IndexEntry>> getRemovals() {
        return removals;
    }

    public void clear() {
        additions.clear();
        removals.clear();
    }
}
