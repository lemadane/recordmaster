package io.lemadane.recordmaster;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

public class ChaosE2ETest {

    @Test
    public void testHarshChaosSigkillAndWalCorruptionE2E(@TempDir Path tempDir) throws Exception {
        Path dbDir = tempDir.resolve("chaos-db");
        Path backupDir = tempDir.resolve("chaos-backup");

        String javaBin = System.getProperty("java.home") + "/bin/java";
        String classPath = System.getProperty("java.class.path");
        Random random = new Random();

        // System property chaos.iterations can be set to 10000 for long soak runs
        int iterations = Integer.getInteger("chaos.iterations", 10);

        for (int i = 1; i <= iterations; i++) {
            // 1. Spawn child process running multi-threaded writers, readers, compactors, and backups
            ProcessBuilder pb = new ProcessBuilder(
                javaBin,
                "-cp",
                classPath,
                "io.lemadane.recordmaster.ChaosWorkerMain",
                dbDir.toAbsolutePath().toString(),
                backupDir.toAbsolutePath().toString()
            );
            pb.inheritIO();
            Process process = pb.start();

            // 2. Let child run for random duration between 100ms and 350ms under heavy load
            int runDurationMs = 100 + random.nextInt(250);
            Thread.sleep(runDurationMs);

            // 3. Issue SIGKILL to child JVM process forcibly mid-transaction/compact/backup
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);

            // 4. Randomly corrupt/truncate WAL tail to simulate sudden power loss during disk write
            Path walFile = dbDir.resolve("wal.log");
            if (Files.exists(walFile) && random.nextBoolean()) {
                byte[] corruptTail = new byte[random.nextInt(30) + 1];
                random.nextBytes(corruptTail);
                try (FileOutputStream fos = new FileOutputStream(walFile.toFile(), true)) {
                    fos.write(corruptTail);
                    fos.flush();
                }
            }

            // 5. Open database and verify full state recovery & post-recovery transaction writability
            try (RecordDatabase db = RecordDatabase.open(dbDir)) {
                RecordTable<Long, ChaosWorkerMain.UserAccount> table = db.table(ChaosWorkerMain.UserAccount.class);
                List<ChaosWorkerMain.UserAccount> recovered = table.query().list();
                assertNotNull(recovered);

                for (ChaosWorkerMain.UserAccount acc : recovered) {
                    assertNotNull(acc.id(), "Recovered record ID must not be null");
                    assertNotNull(acc.email(), "Recovered record email must not be null");
                    assertNotNull(acc.balance(), "Recovered record balance must not be null");

                    ChaosWorkerMain.UserAccount found = table.findById(acc.id()).orElse(null);
                    assertNotNull(found, "Record with ID " + acc.id() + " must be readable by primary key");
                }

                // Verify database is fully writable and transactional post-recovery
                long maxExistingId = recovered.stream().mapToLong(ChaosWorkerMain.UserAccount::id).max().orElse(0L);
                long nextId = maxExistingId + 100L;
                db.transaction(tx -> {
                    RecordTable<Long, ChaosWorkerMain.UserAccount> txTable = tx.table(ChaosWorkerMain.UserAccount.class);
                    txTable.insert(new ChaosWorkerMain.UserAccount(nextId, "post_recovery_" + nextId + "@chaos.org", 1000L, "ACTIVE"));
                });

                assertTrue(table.findById(nextId).isPresent(), "Newly inserted transaction post-recovery must be present");
            }
        }
    }
}
