package io.lemadane.recordmaster;

import io.lemadane.recordmaster.annotations.Id;
import io.lemadane.recordmaster.annotations.Index;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

public class BackupCompactionTest {

    public record TestRecord(
        @Id Long id,
        @Index(unique = true) String email,
        String name
    ) implements Record {}

    @Test
    public void testBackupAfterCompactionRestoresDataSuccessfully(@TempDir Path tempDir) {
        Path dbDir = tempDir.resolve("primary-db");
        Path backupDir = tempDir.resolve("backup-db");

        int recordCount = 500;

        try (RecordDatabase db = RecordDatabase.open(dbDir)) {
            RecordTable<Long, TestRecord> table = db.table(TestRecord.class);
            for (long i = 1; i <= recordCount; i++) {
                table.insert(new TestRecord(i, "cust" + i + "@example.com", "Customer " + i));
            }

            assertEquals(recordCount, table.query().list().size());

            // Compact the primary database
            db.compact();

            // Perform backup post compaction
            db.backup(backupDir);
        }

        // Open restored database from backup path
        try (RecordDatabase restoredDb = RecordDatabase.open(backupDir)) {
            RecordTable<Long, TestRecord> restoredTable = restoredDb.table(TestRecord.class);
            assertEquals(recordCount, restoredTable.query().list().size());
            
            TestRecord cust42 = restoredTable.findById(42L).orElse(null);
            assertNotNull(cust42);
            assertEquals("Customer 42", cust42.name());
            assertEquals("cust42@example.com", cust42.email());
        }
    }
}
