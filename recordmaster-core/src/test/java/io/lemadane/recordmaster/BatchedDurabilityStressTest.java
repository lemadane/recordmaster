package io.lemadane.recordmaster;

import io.lemadane.recordmaster.annotations.Id;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

public class BatchedDurabilityStressTest {

    public record AccountItem(@Id Long id, String holder, Long balance) implements Record {}

    @Test
    public void testHighConcurrencyBatchedDurabilityNoDeadlock(@TempDir Path dbDir) throws Exception {
        try (RecordDatabase db = RecordDatabase.builder().directory(dbDir).durabilityMode(DurabilityMode.BATCHED).build()) {
            RecordTable<Long, AccountItem> table = db.table(AccountItem.class);

            AtomicBoolean running = new AtomicBoolean(true);
            ExecutorService pool = Executors.newFixedThreadPool(12);
            List<Future<?>> futures = new ArrayList<>();

            // 8 Concurrent Writer Threads in BATCHED mode
            for (int w = 0; w < 8; w++) {
                final int threadId = w;
                futures.add(pool.submit(() -> {
                    long counter = threadId * 100000L + 1;
                    while (running.get()) {
                        final long id = counter++;
                        db.transaction(tx -> {
                            tx.table(AccountItem.class).insert(new AccountItem(id, "holder_" + id, id * 10));
                        });
                    }
                }));
            }

            // 4 Concurrent Flusher / Reader Threads
            for (int r = 0; r < 4; r++) {
                futures.add(pool.submit(() -> {
                    while (running.get()) {
                        db.flush();
                        table.query().list();
                        try {
                            Thread.sleep(5);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }));
            }

            // Run intense workload for 2 seconds
            Thread.sleep(2000);
            running.set(false);

            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "Batched durability stress pool must terminate without deadlocks");

            for (Future<?> f : futures) {
                f.get();
            }

            assertTrue(table.query().list().size() > 100, "Should have committed thousands of records in BATCHED mode");
        }
    }
}
