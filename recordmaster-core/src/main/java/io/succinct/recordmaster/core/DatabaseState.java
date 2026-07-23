package io.succinct.recordmaster.core;

import java.util.*;

public final class DatabaseState {
    private final long generation;
    private final Map<String, TableState> tables;

    public DatabaseState(long generation) {
        this.generation = generation;
        this.tables = new HashMap<>();
    }

    private DatabaseState(long generation, Map<String, TableState> tables) {
        this.generation = generation;
        this.tables = tables;
    }

    public long generation() {
        return generation;
    }

    public Map<String, TableState> tables() {
        return tables;
    }

    public TableState getTable(String tableName) {
        return tables.get(tableName);
    }

    public DatabaseState copy(long nextGeneration) {
        Map<String, TableState> newTables = new HashMap<>();
        for (Map.Entry<String, TableState> entry : this.tables.entrySet()) {
            newTables.put(entry.getKey(), entry.getValue().copy());
        }
        return new DatabaseState(nextGeneration, newTables);
    }
}
