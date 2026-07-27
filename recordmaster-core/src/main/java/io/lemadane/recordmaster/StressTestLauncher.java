package io.lemadane.recordmaster;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class StressTestLauncher {

    private static final List<String[]> results = new ArrayList<>();

    public static void main(String[] args) {
        System.out.println("========================================================================");
        System.out.println("                  RECORDMASTER PERFORMANCE BENCHMARK");
        System.out.println("========================================================================");

        int totalRecords = 10_000_000;
        int batchSize = 100_000;
        DurabilityMode durabilityMode = DurabilityMode.ASYNC;
        String modeOpt = "both";     // sync, async, both
        String tablesOpt = "both";   // single, multi, both
        String dbPath = "data/stress-db";

        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--records") && i + 1 < args.length) {
                totalRecords = Integer.parseInt(args[++i]);
            } else if (args[i].equals("--batch") && i + 1 < args.length) {
                batchSize = Integer.parseInt(args[++i]);
            } else if (args[i].equals("--durability") && i + 1 < args.length) {
                durabilityMode = DurabilityMode.valueOf(args[++i].toUpperCase());
            } else if (args[i].equals("--mode") && i + 1 < args.length) {
                modeOpt = args[++i].toLowerCase();
            } else if (args[i].equals("--tables") && i + 1 < args.length) {
                tablesOpt = args[++i].toLowerCase();
            } else if (args[i].equals("--path") && i + 1 < args.length) {
                dbPath = args[++i];
            }
        }

        System.out.println("Configuration:");
        System.out.println("  Total Records:   " + String.format("%,d", totalRecords));
        System.out.println("  Batch Size:      " + String.format("%,d", batchSize));
        System.out.println("  Durability Mode: " + durabilityMode);
        System.out.println("  Benchmark Mode:  " + modeOpt);
        System.out.println("  Target Tables:   " + tablesOpt);
        System.out.println("  Storage Path:    " + dbPath);
        System.out.println("========================================================================");

        Path rootPath = Path.of(dbPath);
        try {
            boolean runSync = modeOpt.equals("sync") || modeOpt.equals("both");
            boolean runAsync = modeOpt.equals("async") || modeOpt.equals("both");
            boolean runSingle = tablesOpt.equals("single") || tablesOpt.equals("both");
            boolean runMulti = tablesOpt.equals("multi") || tablesOpt.equals("both");

            if (runSingle && runSync) {
                runSingleTableSync(rootPath.resolve("single-sync"), totalRecords, batchSize, durabilityMode);
            }
            if (runSingle && runAsync) {
                runSingleTableAsync(rootPath.resolve("single-async"), totalRecords, batchSize, durabilityMode);
            }
            if (runMulti && runSync) {
                runMultiTableSync(rootPath.resolve("multi-sync"), totalRecords, batchSize, durabilityMode);
            }
            if (runMulti && runAsync) {
                runMultiTableAsync(rootPath.resolve("multi-async"), totalRecords, batchSize, durabilityMode);
            }

            printFinalSummary();

        } catch (Exception e) {
            System.err.println("Stress test encountered error:");
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static UUID getDeterministicUuid(String prefix, int index) {
        byte[] prefixBytes = prefix.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(prefixBytes.length + 4);
        buffer.put(prefixBytes);
        buffer.putInt(index);
        return UUID.nameUUIDFromBytes(buffer.array());
    }

    private static long getUsedMemory() {
        System.gc();
        try { Thread.sleep(200); } catch (InterruptedException e) {}
        System.gc();
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static void deleteDir(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder())
                  .map(Path::toFile)
                  .forEach(File::delete);
        }
    }

    private static long getDirSize(Path path) throws IOException {
        if (!Files.exists(path)) return 0;
        try (var stream = Files.walk(path)) {
            return stream.filter(Files::isRegularFile)
                         .mapToLong(p -> {
                             try {
                                 return Files.size(p);
                             } catch (IOException e) {
                                 return 0;
                             }
                         })
                         .sum();
         }
    }

    private static void printResultRow(String testName, int records, long writeMs, long queryMs, long memBytes, long diskBytes) {
        double writeSec = writeMs / 1000.0;
        double writeRate = writeSec == 0 ? 0 : records / writeSec;

        double querySec = queryMs / 1000.0;
        int queried = testName.contains("Multi") ? 6000 : 5000;
        double queryRate = querySec == 0 ? 0 : queried / querySec;

        String memMb = String.format("%.2f MB", memBytes / (1024.0 * 1024.0));
        String diskMb = String.format("%.2f MB", diskBytes / (1024.0 * 1024.0));

        results.add(new String[]{
            testName,
            String.format("%,d", records),
            String.format("%.2fs (%,.0f/s)", writeSec, writeRate),
            String.format("%.2fs (%,.0f/s)", querySec, queryRate),
            memMb,
            diskMb
        });
    }

    private static void printFinalSummary() {
        System.out.println("\n" + "=".repeat(110));
        System.out.println("                               RECORDMASTER STRESS TEST SUMMARY");
        System.out.println("=".repeat(110));
        System.out.printf("%-20s | %-12s | %-24s | %-24s | %-12s | %-12s\n",
            "Test Scenario", "Records", "Write Speed", "Verify Query Speed", "RAM Delta", "Disk Size");
        System.out.println("-".repeat(110));
        for (String[] row : results) {
            System.out.printf("%-20s | %-12s | %-24s | %-24s | %-12s | %-12s\n",
                row[0], row[1], row[2], row[3], row[4], row[5]);
        }
        System.out.println("=".repeat(110));
    }

    // --- Scenario 1: Single Table Synchronous ---
    private static void runSingleTableSync(Path path, int totalRecords, int batchSize, DurabilityMode durabilityMode) throws Exception {
        System.out.println("Running Test 1: Single Table Sync (" + totalRecords + " records)...");
        deleteDir(path);

        long startMem = getUsedMemory();
        long start = System.currentTimeMillis();

        try (RecordDatabase db = RecordDatabase.builder().directory(path).durabilityMode(durabilityMode).build()) {
            int batches = totalRecords / batchSize;
            if (batches == 0) batches = 1;
            int actualBatch = totalRecords / batches;

            for (int b = 0; b < batches; b++) {
                final int startIdx = b * actualBatch;
                db.transaction(tx -> {
                    RecordTable<UUID, StressLog> table = tx.table(StressLog.class);
                    for (int i = 0; i < actualBatch; i++) {
                        int current = startIdx + i;
                        UUID id = getDeterministicUuid("single-sync", current);
                        table.insert(new StressLog(id, "INFO", "Log sync message " + current, System.currentTimeMillis()));
                    }
                });
            }

            long writeEnd = System.currentTimeMillis();
            long writeDuration = writeEnd - start;

            int size = db.getCommittedState().getTable("StressLog").recordPointers().size();
            if (size != totalRecords) {
                throw new IllegalStateException("Size mismatch! Expected " + totalRecords + " but got " + size);
            }

            long queryStart = System.currentTimeMillis();
            RecordTable<UUID, StressLog> table = db.table(StressLog.class);
            Random rand = new Random(42);
            for (int i = 0; i < 5000; i++) {
                int idx = rand.nextInt(totalRecords);
                UUID id = getDeterministicUuid("single-sync", idx);
                StressLog log = table.findById(id).orElseThrow();
                if (!log.message().contains(String.valueOf(idx))) {
                    throw new IllegalStateException("Verification failed for log " + idx);
                }
            }
            long queryEnd = System.currentTimeMillis();
            long queryDuration = queryEnd - queryStart;

            long endMem = getUsedMemory();
            long diskSize = getDirSize(path);

            printResultRow("Single-Table Sync", totalRecords, writeDuration, queryDuration, endMem - startMem, diskSize);
        }
    }

    // --- Scenario 2: Single Table Asynchronous ---
    private static void runSingleTableAsync(Path path, int totalRecords, int batchSize, DurabilityMode durabilityMode) throws Exception {
        System.out.println("Running Test 2: Single Table Async (" + totalRecords + " records)...");
        deleteDir(path);

        long startMem = getUsedMemory();
        long start = System.currentTimeMillis();

        try (RecordDatabase db = RecordDatabase.builder().directory(path).durabilityMode(durabilityMode).build()) {
            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            List<Future<?>> futures = new ArrayList<>();
            int batches = totalRecords / batchSize;
            if (batches == 0) batches = 1;
            int actualBatch = totalRecords / batches;

            for (int b = 0; b < batches; b++) {
                final int startIdx = b * actualBatch;
                futures.add(executor.submit(() -> {
                    try {
                        db.transaction(tx -> {
                            RecordTable<UUID, StressLog> table = tx.table(StressLog.class);
                            for (int i = 0; i < actualBatch; i++) {
                                int current = startIdx + i;
                                UUID id = getDeterministicUuid("single-async", current);
                                table.insert(new StressLog(id, "WARN", "Log async message " + current, System.currentTimeMillis()));
                            }
                        });
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));
            }

            for (Future<?> f : futures) {
                f.get();
            }
            executor.close();

            long writeEnd = System.currentTimeMillis();
            long writeDuration = writeEnd - start;

            int size = db.getCommittedState().getTable("StressLog").recordPointers().size();
            if (size != totalRecords) {
                throw new IllegalStateException("Size mismatch! Expected " + totalRecords + " but got " + size);
            }

            long queryStart = System.currentTimeMillis();
            RecordTable<UUID, StressLog> table = db.table(StressLog.class);
            ExecutorService queryExecutor = Executors.newVirtualThreadPerTaskExecutor();
            List<Future<?>> queryFutures = new ArrayList<>();

            for (int t = 0; t < 10; t++) {
                queryFutures.add(queryExecutor.submit(() -> {
                    Random rand = new Random();
                    for (int i = 0; i < 500; i++) {
                        int idx = rand.nextInt(totalRecords);
                        UUID id = getDeterministicUuid("single-async", idx);
                        StressLog log = table.findById(id).orElseThrow();
                        if (!log.message().contains(String.valueOf(idx))) {
                            throw new IllegalStateException("Verification failed for log " + idx);
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> f : queryFutures) {
                f.get();
            }
            queryExecutor.close();

            long queryEnd = System.currentTimeMillis();
            long queryDuration = queryEnd - queryStart;

            long endMem = getUsedMemory();
            long diskSize = getDirSize(path);

            printResultRow("Single-Table Async", totalRecords, writeDuration, queryDuration, endMem - startMem, diskSize);
        }
    }

    // --- Scenario 3: Multiple Tables Synchronous ---
    private static void runMultiTableSync(Path path, int totalRecords, int batchSize, DurabilityMode durabilityMode) throws Exception {
        System.out.println("Running Test 3: Multi-Table Sync (" + totalRecords + " records)...");
        deleteDir(path);

        long startMem = getUsedMemory();
        long start = System.currentTimeMillis();

        int targetPerTable = totalRecords / 3;
        int batches = targetPerTable / batchSize;
        if (batches == 0) batches = 1;
        int actualBatchSize = targetPerTable / batches;

        try (RecordDatabase db = RecordDatabase.builder().directory(path).durabilityMode(durabilityMode).build()) {
            // Write Users
            for (int b = 0; b < batches; b++) {
                final int startIdx = b * actualBatchSize;
                db.transaction(tx -> {
                    RecordTable<UUID, StressUser> table = tx.table(StressUser.class);
                    for (int i = 0; i < actualBatchSize; i++) {
                        int current = startIdx + i;
                        UUID id = getDeterministicUuid("user-sync", current);
                        table.insert(new StressUser(id, "user-sync-" + current + "@example.com", "User " + current));
                    }
                });
            }
            // Write Orders
            for (int b = 0; b < batches; b++) {
                final int startIdx = b * actualBatchSize;
                db.transaction(tx -> {
                    RecordTable<UUID, StressOrder> table = tx.table(StressOrder.class);
                    for (int i = 0; i < actualBatchSize; i++) {
                        int current = startIdx + i;
                        UUID id = getDeterministicUuid("order-sync", current);
                        table.insert(new StressOrder(id, getDeterministicUuid("user-sync", current), 10.0 + current, System.currentTimeMillis()));
                    }
                });
            }
            // Write Products
            for (int b = 0; b < batches; b++) {
                final int startIdx = b * actualBatchSize;
                db.transaction(tx -> {
                    RecordTable<UUID, StressProduct> table = tx.table(StressProduct.class);
                    for (int i = 0; i < actualBatchSize; i++) {
                        int current = startIdx + i;
                        UUID id = getDeterministicUuid("product-sync", current);
                        table.insert(new StressProduct(id, "Product " + current, 1.0 + current));
                    }
                });
            }

            long writeEnd = System.currentTimeMillis();
            long writeDuration = writeEnd - start;

            int userSize = db.getCommittedState().getTable("StressUser").recordPointers().size();
            int orderSize = db.getCommittedState().getTable("StressOrder").recordPointers().size();
            int productSize = db.getCommittedState().getTable("StressProduct").recordPointers().size();
            int totalInserted = userSize + orderSize + productSize;

            long queryStart = System.currentTimeMillis();
            RecordTable<UUID, StressUser> userTable = db.table(StressUser.class);
            RecordTable<UUID, StressOrder> orderTable = db.table(StressOrder.class);
            RecordTable<UUID, StressProduct> productTable = db.table(StressProduct.class);
            Random rand = new Random(42);
            for (int i = 0; i < 2000; i++) {
                int idx = rand.nextInt(targetPerTable);

                UUID userId = getDeterministicUuid("user-sync", idx);
                StressUser user = userTable.findById(userId).orElseThrow();
                if (!user.name().contains(String.valueOf(idx))) {
                    throw new IllegalStateException("Verification failed for user " + idx);
                }

                UUID orderId = getDeterministicUuid("order-sync", idx);
                StressOrder order = orderTable.findById(orderId).orElseThrow();
                if (order.amount() != 10.0 + idx) {
                    throw new IllegalStateException("Verification failed for order " + idx);
                }

                UUID prodId = getDeterministicUuid("product-sync", idx);
                StressProduct prod = productTable.findById(prodId).orElseThrow();
                if (prod.price() != 1.0 + idx) {
                    throw new IllegalStateException("Verification failed for product " + idx);
                }
            }
            long queryEnd = System.currentTimeMillis();
            long queryDuration = queryEnd - queryStart;

            long endMem = getUsedMemory();
            long diskSize = getDirSize(path);

            printResultRow("Multi-Table Sync", totalInserted, writeDuration, queryDuration, endMem - startMem, diskSize);
        }
    }

    // --- Scenario 4: Multiple Tables Asynchronous ---
    private static void runMultiTableAsync(Path path, int totalRecords, int batchSize, DurabilityMode durabilityMode) throws Exception {
        System.out.println("Running Test 4: Multi-Table Async (" + totalRecords + " records)...");
        deleteDir(path);

        long startMem = getUsedMemory();
        long start = System.currentTimeMillis();

        int targetPerTable = totalRecords / 3;
        int batches = targetPerTable / batchSize;
        if (batches == 0) batches = 1;
        int actualBatchSize = targetPerTable / batches;

        try (RecordDatabase db = RecordDatabase.builder().directory(path).durabilityMode(durabilityMode).build()) {
            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            List<Future<?>> futures = new ArrayList<>();

            for (int b = 0; b < batches; b++) {
                final int startIdx = b * actualBatchSize;

                futures.add(executor.submit(() -> {
                    try {
                        db.transaction(tx -> {
                            RecordTable<UUID, StressUser> table = tx.table(StressUser.class);
                            for (int i = 0; i < actualBatchSize; i++) {
                                int current = startIdx + i;
                                UUID id = getDeterministicUuid("user-async", current);
                                table.insert(new StressUser(id, "user-async-" + current + "@example.com", "User " + current));
                            }
                        });
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));

                futures.add(executor.submit(() -> {
                    try {
                        db.transaction(tx -> {
                            RecordTable<UUID, StressOrder> table = tx.table(StressOrder.class);
                            for (int i = 0; i < actualBatchSize; i++) {
                                int current = startIdx + i;
                                UUID id = getDeterministicUuid("order-async", current);
                                table.insert(new StressOrder(id, getDeterministicUuid("user-async", current), 10.0 + current, System.currentTimeMillis()));
                            }
                        });
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));

                futures.add(executor.submit(() -> {
                    try {
                        db.transaction(tx -> {
                            RecordTable<UUID, StressProduct> table = tx.table(StressProduct.class);
                            for (int i = 0; i < actualBatchSize; i++) {
                                int current = startIdx + i;
                                UUID id = getDeterministicUuid("product-async", current);
                                table.insert(new StressProduct(id, "Product " + current, 1.0 + current));
                            }
                        });
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));
            }

            for (Future<?> f : futures) {
                f.get();
            }
            executor.close();

            long writeEnd = System.currentTimeMillis();
            long writeDuration = writeEnd - start;

            int userSize = db.getCommittedState().getTable("StressUser").recordPointers().size();
            int orderSize = db.getCommittedState().getTable("StressOrder").recordPointers().size();
            int productSize = db.getCommittedState().getTable("StressProduct").recordPointers().size();
            int totalInserted = userSize + orderSize + productSize;

            long queryStart = System.currentTimeMillis();
            RecordTable<UUID, StressUser> userTable = db.table(StressUser.class);
            RecordTable<UUID, StressOrder> orderTable = db.table(StressOrder.class);
            RecordTable<UUID, StressProduct> productTable = db.table(StressProduct.class);

            ExecutorService queryExecutor = Executors.newVirtualThreadPerTaskExecutor();
            List<Future<?>> queryFutures = new ArrayList<>();

            for (int t = 0; t < 10; t++) {
                queryFutures.add(queryExecutor.submit(() -> {
                    Random rand = new Random();
                    for (int i = 0; i < 200; i++) {
                        int idx = rand.nextInt(targetPerTable);

                        UUID userId = getDeterministicUuid("user-async", idx);
                        StressUser user = userTable.findById(userId).orElseThrow();
                        if (!user.name().contains(String.valueOf(idx))) {
                            throw new IllegalStateException("Verification failed for user " + idx);
                        }

                        UUID orderId = getDeterministicUuid("order-async", idx);
                        StressOrder order = orderTable.findById(orderId).orElseThrow();
                        if (order.amount() != 10.0 + idx) {
                            throw new IllegalStateException("Verification failed for order " + idx);
                        }

                        UUID prodId = getDeterministicUuid("product-async", idx);
                        StressProduct prod = productTable.findById(prodId).orElseThrow();
                        if (prod.price() != 1.0 + idx) {
                            throw new IllegalStateException("Verification failed for product " + idx);
                        }
                    }
                    return null;
                }));
            }

            for (Future<?> f : queryFutures) {
                f.get();
            }
            queryExecutor.close();

            long queryEnd = System.currentTimeMillis();
            long queryDuration = queryEnd - queryStart;

            long endMem = getUsedMemory();
            long diskSize = getDirSize(path);

            printResultRow("Multi-Table Async", totalInserted, writeDuration, queryDuration, endMem - startMem, diskSize);
        }
    }
}
