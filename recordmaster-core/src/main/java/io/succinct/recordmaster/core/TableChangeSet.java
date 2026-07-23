package io.succinct.recordmaster.core;

import io.succinct.recordmaster.Record;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class TableChangeSet {
    private final Map<Object, Record> inserts = new LinkedHashMap<>();
    private final Map<Object, Record> updates = new LinkedHashMap<>();
    private final Set<Object> deletes = new LinkedHashSet<>();
    private boolean cleared = false;

    public void insert(Object key, Record record) {
        deletes.remove(key);
        inserts.put(key, record);
    }

    public void update(Object key, Record record) {
        if (inserts.containsKey(key)) {
            inserts.put(key, record);
        } else {
            updates.put(key, record);
        }
    }

    public void delete(Object key) {
        if (inserts.containsKey(key)) {
            inserts.remove(key);
        } else {
            updates.remove(key);
            deletes.add(key);
        }
    }

    public void clear() {
        inserts.clear();
        updates.clear();
        deletes.clear();
        cleared = true;
    }

    public Map<Object, Record> getInserts() {
        return inserts;
    }

    public Map<Object, Record> getUpdates() {
        return updates;
    }

    public Set<Object> getDeletes() {
        return deletes;
    }

    public boolean isCleared() {
        return cleared;
    }

    public boolean isEmpty() {
        return inserts.isEmpty() && updates.isEmpty() && deletes.isEmpty() && !cleared;
    }
}
