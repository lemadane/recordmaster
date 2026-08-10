package io.lemadane.recordmaster;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

public class CrashInjectionTest {

    @Test
    public void testRandomSigkillCrashRecoveryLoop(@TempDir Path dbDir) throws Exception {
        String javaBin = System.getProperty("java.home") + "/bin/java";
        String classPath = System.getProperty("java.class.path");
        Random random = new Random();

        int crashIterations = 8;

        for (int i = 0; i < crashIterations; i++) {
            // 1. Spawn child process running transactions
            ProcessBuilder pb = new ProcessBuilder(
                javaBin,
                "-cp",
                classPath,
                "io.lemadane.recordmaster.CrashInjectionWorker",
                dbDir.toAbsolutePath().toString()
            );
            pb.inheritIO();
            Process process = pb.start();

            // 2. Let child run for random duration between 100ms and 400ms
            int runDurationMs = 100 + random.nextInt(300);
            Thread.sleep(runDurationMs);

            // 3. SIGKILL child JVM process forcibly
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);

            // 4. Open database and verify integrity after abrupt termination
            try (RecordDatabase db = RecordDatabase.open(dbDir)) {
                RecordTable<Long, CrashInjectionWorker.Account> table = db.table(CrashInjectionWorker.Account.class);
                List<CrashInjectionWorker.Account> records = table.query().list();
                assertNotNull(records);

                // Verify every recovered record can be looked up cleanly by ID and unique index
                for (CrashInjectionWorker.Account acc : records) {
                    assertNotNull(acc.id());
                    assertNotNull(acc.email());
                    assertNotNull(acc.balance());
                    CrashInjectionWorker.Account found = table.findById(acc.id()).orElse(null);
                    assertNotNull(found, "Record with ID " + acc.id() + " must be readable after crash recovery");
                }
            }
        }
    }
}
