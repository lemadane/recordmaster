package io.lemadane.recordmaster;

import io.lemadane.recordmaster.annotations.Id;
import io.lemadane.recordmaster.annotations.Index;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

public class DurabilityCloseTest {

    public record TestRecord(
        @Id Long id,
        @Index(unique = true) String email,
        String name
    ) implements Record {}

    @Test
    public void testAsyncCloseFlushesWalAndPersistsRecords(@TempDir Path dbDir) {
        try (RecordDatabase db = RecordDatabase.builder().directory(dbDir).durabilityMode(DurabilityMode.ASYNC).build()) {
            RecordTable<Long, TestRecord> table = db.table(TestRecord.class);
            for (long i = 1; i <= 50; i++) {
                table.insert(new TestRecord(i, "async" + i + "@example.com", "Async Cust " + i));
            }
            // Close without calling manual db.flush()
        }

        // Reopen database and verify all 50 records were flushed on close
        try (RecordDatabase db = RecordDatabase.open(dbDir)) {
            RecordTable<Long, TestRecord> table = db.table(TestRecord.class);
            assertEquals(50, table.query().list().size());
            TestRecord c = table.findById(25L).orElse(null);
            assertNotNull(c);
            assertEquals("Async Cust 25", c.name());
        }
    }
}
