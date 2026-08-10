package io.lemadane.recordmaster;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

public class DatabaseLockTest {

    @Test
    public void testExclusiveDatabaseLocking(@TempDir Path dbDir) {
        RecordDatabase db1 = RecordDatabase.open(dbDir);
        assertNotNull(db1);

        // Attempting to open second instance on same path must throw DatabaseAlreadyOpenException
        assertThrows(DatabaseAlreadyOpenException.class, () -> {
            RecordDatabase.open(dbDir);
        });

        // Closing first instance releases lock
        db1.close();

        // Opening again after close succeeds
        RecordDatabase db2 = RecordDatabase.open(dbDir);
        assertNotNull(db2);
        db2.close();
    }
}
