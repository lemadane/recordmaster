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

public class DeadlockStressTest {

    public record Account(
        @Id Long id,
        @Index(unique = true) String email,
        Long balance
    ) implements Record {}

    @Test
    public void testHighConcurrencyNoDeadlocks(@TempDir Path dbDir) throws Exception {
        Path backupDir = dbDir.resolve("backup-target");

        try (RecordDatabase db = RecordDatabase.open(dbDir)) {
            RecordTable<Long, Account> table = db.table(Account.class);

            // Pre-seed 100 accounts
            for (long i = 1; i <= 100; i++) {
                table.insert(new Account(i, "acc" + i + "@example.com", 1000L));
            }

            AtomicBoolean running = new AtomicBoolean(true);
            AtomicReference<Throwable> errorRef = new AtomicReference<>();
            ExecutorService pool = Executors.newFixedThreadPool(12);

            // 4 Writer threads: updating accounts in transactions
            for (int w = 0; w < 4; w++) {
                final int threadId = w;
                pool.submit(() -> {
                    long idCounter = threadId * 20 + 1;
                    while (running.get()) {
                        try {
                            final long targetId = idCounter;
                            db.transaction(tx -> {
                                RecordTable<Long, Account> txTable = tx.table(Account.class);
                                Account acc = txTable.findById(targetId).orElse(null);
                                if (acc != null) {
                                    txTable.update(new Account(acc.id(), acc.email(), acc.balance() + 10));
                                }
                            });
                            idCounter = (idCounter % 100) + 1;
                        } catch (Throwable t) {
                            errorRef.set(t);
                            running.set(false);
                        }
                    }
                });
            }

            // 4 Reader threads: querying and reading non-transactionally
            for (int r = 0; r < 4; r++) {
                pool.submit(() -> {
                    long id = 1;
                    while (running.get()) {
                        try {
                            Account acc = table.findById(id).orElse(null);
                            if (acc != null) {
                                assertNotNull(acc.email());
                            }
                            table.query().list();
                            id = (id % 100) + 1;
                        } catch (Throwable t) {
                            errorRef.set(t);
                            running.set(false);
                        }
                    }
                });
            }

            // 2 Compactor threads: calling db.compact() continuously
            for (int c = 0; c < 2; c++) {
                pool.submit(() -> {
                    while (running.get()) {
                        try {
                            db.compact();
                            Thread.sleep(10);
                        } catch (Throwable t) {
                            errorRef.set(t);
                            running.set(false);
                        }
                    }
                });
            }

            // 2 Backup threads: calling db.backup() continuously
            for (int b = 0; b < 2; b++) {
                pool.submit(() -> {
                    while (running.get()) {
                        try {
                            db.backup(backupDir);
                            Thread.sleep(20);
                        } catch (Throwable t) {
                            errorRef.set(t);
                            running.set(false);
                        }
                    }
                });
            }

            // Run high concurrency stress test for 3 seconds
            Thread.sleep(3000);
            running.set(false);

            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "Thread pool failed to terminate - deadlock detected!");

            if (errorRef.get() != null) {
                fail("Concurrency stress test encountered exception", errorRef.get());
            }

            assertTrue(table.query().list().size() >= 100);
        }
    }
}
