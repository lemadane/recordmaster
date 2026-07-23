package io.lemadane.recordmaster;

import java.nio.file.Path;

public final class RecordDatabaseBuilder {
    private Path directory;
    private DurabilityMode durabilityMode = DurabilityMode.SYNC;

    public RecordDatabaseBuilder directory(Path directory) {
        this.directory = directory;
        return this;
    }

    public RecordDatabaseBuilder durabilityMode(DurabilityMode durabilityMode) {
        this.durabilityMode = durabilityMode;
        return this;
    }

    public Path directory() {
        return directory;
    }

    public DurabilityMode durabilityMode() {
        return durabilityMode;
    }

    public RecordDatabase build() {
        if (directory == null) {
            throw new IllegalStateException("Database directory must be configured");
        }
        return new RecordDatabase(this);
    }
}
