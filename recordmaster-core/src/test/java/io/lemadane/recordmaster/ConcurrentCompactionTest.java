package io.lemadane.recordmaster;

import io.lemadane.recordmaster.annotations.Id;
import io.lemadane.recordmaster.annotations.Index;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

public class ConcurrentCompactionTest {

    public record TestRecord(
        @Id Long id,
        @Index(unique = true) String email,
        String name
    ) implements Record {}

    @Test
    public void testConcurrentReadersDuringCompaction(@TempDir Path dbDir) throws Exception {
        int initialCount = 200;

        try (RecordDatabase db = RecordDatabase.open(dbDir)) {
            RecordTable<Long, TestRecord> table = db.table(TestRecord.class);
            for (long i = 1; i <= initialCount; i++) {
                table.insert(new TestRecord(i, "cust" + i + "@example.com", "Customer " + i));
            }

            AtomicBoolean running = new AtomicBoolean(true);
            AtomicReference<Throwable> errorRef = new AtomicReference<>();
            ExecutorService executor = Executors.newFixedThreadPool(6);

            // 4 reader threads continuously querying and reading records
            for (int r = 0; r < 4; r++) {
                executor.submit(() -> {
                    long id = 1;
                    while (running.get()) {
                        try {
                            TestRecord c = table.findById(id).orElse(null);
                            if (c != null) {
                                assertNotNull(c.name());
                            }
                            id = (id % initialCount) + 1;
                        } catch (Throwable t) {
                            errorRef.set(t);
                            running.set(false);
                        }
                    }
                });
            }

            // 2 compactor threads running compact() repeatedly
            for (int c = 0; c < 2; c++) {
                executor.submit(() -> {
                    while (running.get()) {
                        try {
                            db.compact();
                            Thread.sleep(5);
                        } catch (Throwable t) {
                            errorRef.set(t);
                            running.set(false);
                        }
                    }
                });
            }

            Thread.sleep(1500);
            running.set(false);
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

            if (errorRef.get() != null) {
                fail("Concurrent reader/compactor thread encountered exception", errorRef.get());
            }

            assertEquals(initialCount, table.query().list().size());
        }
    }

    @Test
    public void testCloseDuringCompactAsync(@TempDir Path dbDir) throws Exception {
        RecordDatabase db = RecordDatabase.open(dbDir);
        RecordTable<Long, TestRecord> table = db.table(TestRecord.class);
        table.insert(new TestRecord(1L, "test@example.com", "Test User"));

        // Trigger compactAsync and immediately call db.close() concurrently
        CompletableFuture<Void> compactFuture = db.compactAsync().toCompletableFuture();
        db.close();

        // Must complete without deadlocking
        assertDoesNotThrow(() -> compactFuture.get(3, TimeUnit.SECONDS));
        assertTrue(db.isClosed());
    }
}
