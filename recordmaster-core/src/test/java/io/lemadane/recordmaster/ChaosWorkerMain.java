package io.lemadane.recordmaster;

import io.lemadane.recordmaster.annotations.Id;
import io.lemadane.recordmaster.annotations.Index;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class ChaosWorkerMain {

    public record UserAccount(
        @Id Long id,
        @Index(unique = true) String email,
        Long balance,
        String status
    ) implements Record {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.exit(1);
        }

        Path dbDir = Paths.get(args[0]);
        Path backupDir = Paths.get(args[1]);

        AtomicBoolean running = new AtomicBoolean(true);
        ExecutorService pool = Executors.newFixedThreadPool(8);

        try (RecordDatabase db = RecordDatabase.open(dbDir)) {
            RecordTable<Long, UserAccount> table = db.table(UserAccount.class);

            long maxId = table.query().list().stream().mapToLong(UserAccount::id).max().orElse(0L);
            long startCounter = maxId + 1;

            // 1. Writer Threads (Inserts, Updates, Deletes)
            for (int w = 0; w < 3; w++) {
                final int threadId = w;
                pool.submit(() -> {
                    Random r = new Random();
                    long id = startCounter + threadId * 1000L;
                    while (running.get()) {
                        try {
                            final long curId = id++;
                            final String email = "user_" + curId + "@chaos.org";

                            // Transaction: Insert
                            db.transaction(tx -> {
                                RecordTable<Long, UserAccount> txTable = tx.table(UserAccount.class);
                                txTable.insert(new UserAccount(curId, email, 500L, "ACTIVE"));
                            });

                            // Transaction: Update
                            if (r.nextBoolean()) {
                                db.transaction(tx -> {
                                    RecordTable<Long, UserAccount> txTable = tx.table(UserAccount.class);
                                    UserAccount existing = txTable.findById(curId).orElse(null);
                                    if (existing != null) {
                                        txTable.update(new UserAccount(existing.id(), existing.email(), existing.balance() + 250L, "VERIFIED"));
                                    }
                                });
                            }

                            // Transaction: Delete older record
                            if (curId > 10 && r.nextInt(5) == 0) {
                                final long delId = curId - 5;
                                db.transaction(tx -> {
                                    RecordTable<Long, UserAccount> txTable = tx.table(UserAccount.class);
                                    txTable.deleteById(delId);
                                });
                            }

                            Thread.sleep(r.nextInt(5) + 1);
                        } catch (Throwable t) {
                            // Continue until process is SIGKILL'd
                        }
                    }
                });
            }

            // 2. Reader Threads (Concurrent Queries and Index Lookups)
            for (int r = 0; r < 3; r++) {
                pool.submit(() -> {
                    Random rand = new Random();
                    while (running.get()) {
                        try {
                            table.query().where(rec -> rec instanceof UserAccount acc && acc.balance() > 100L).list();
                            long randomId = rand.nextInt(500) + 1;
                            table.findById(randomId);
                            Thread.sleep(rand.nextInt(5) + 1);
                        } catch (Throwable t) {
                            // Continue until process is SIGKILL'd
                        }
                    }
                });
            }

            // 3. Compactor Thread
            pool.submit(() -> {
                Random rand = new Random();
                while (running.get()) {
                    try {
                        db.compact();
                        Thread.sleep(15);
                    } catch (Throwable t) {
                        // Continue until process is SIGKILL'd
                    }
                }
            });

            // 4. Hot Backup Thread
            pool.submit(() -> {
                Random rand = new Random();
                while (running.get()) {
                    try {
                        db.backup(backupDir);
                        Thread.sleep(25);
                    } catch (Throwable t) {
                        // Continue until process is SIGKILL'd
                    }
                }
            });

            // Keep child JVM running until killed by SIGKILL
            while (running.get()) {
                Thread.sleep(50);
            }
        } finally {
            running.set(false);
            pool.shutdownNow();
        }
    }
}
