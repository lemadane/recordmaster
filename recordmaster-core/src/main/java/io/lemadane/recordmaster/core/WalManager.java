package io.lemadane.recordmaster.core;

import io.lemadane.recordmaster.DurabilityMode;
import io.lemadane.recordmaster.TransactionCommitException;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.CRC32C;

public final class WalManager implements AutoCloseable {

    private static final int MAGIC = 0x524d574c; // RMWL

    private final Path walPath;
    private final DurabilityMode durabilityMode;
    private final ExecutorService executor;

    private FileChannel fileChannel;
    private final ReentrantLock writeLock = new ReentrantLock();
    private volatile boolean closed = false;

    // For BATCHED durability
    private final ConcurrentSkipListMap<Long, CompletableFuture<Void>> pendingFlushes = new ConcurrentSkipListMap<>();
    private final ReentrantLock flushLock = new ReentrantLock();
    private final Condition flushCondition = flushLock.newCondition();
    private volatile long lastAppendedGeneration = 0;
    private volatile long lastForcedGeneration = 0;

    public WalManager(Path directory, DurabilityMode durabilityMode) {
        this.walPath = directory.resolve("wal.log");
        this.durabilityMode = durabilityMode;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        
        try {
            Files.createDirectories(directory);
            this.fileChannel = FileChannel.open(walPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE);
            // Seek to end for appending
            this.fileChannel.position(this.fileChannel.size());
        } catch (IOException e) {
            throw new RuntimeException("Failed to open WAL file: " + walPath, e);
        }

        if (durabilityMode == DurabilityMode.BATCHED) {
            startBatchedFlusher();
        }
    }

    public long lastForcedGeneration() {
        return lastForcedGeneration;
    }

    public void setLastForcedGeneration(long generation) {
        this.lastAppendedGeneration = Math.max(this.lastAppendedGeneration, generation);
        this.lastForcedGeneration = Math.max(this.lastForcedGeneration, generation);
    }

    public void appendTransaction(long transactionId, long generation, List<WalRecord> records) {
        writeLock.lock();
        CompletableFuture<Void> future = null;
        try {
            if (closed) {
                throw new IllegalStateException("WAL Manager is closed");
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);

            // 1. Write BEGIN
            writeRecordToBuffer(dos, new WalRecord(RecordWalOperation.BEGIN_TRANSACTION, transactionId, generation, new byte[0]));

            // 2. Write mutations
            for (WalRecord rec : records) {
                writeRecordToBuffer(dos, rec);
            }

            // 3. Write COMMIT
            writeRecordToBuffer(dos, new WalRecord(RecordWalOperation.COMMIT_TRANSACTION, transactionId, generation, new byte[0]));

            dos.flush();
            byte[] bytes = baos.toByteArray();

            ByteBuffer buf = ByteBuffer.wrap(bytes);
            while (buf.hasRemaining()) {
                fileChannel.write(buf);
            }

            lastAppendedGeneration = Math.max(lastAppendedGeneration, generation);

            if (durabilityMode == DurabilityMode.SYNC) {
                fileChannel.force(false);
                lastForcedGeneration = Math.max(lastForcedGeneration, generation);
            } else if (durabilityMode == DurabilityMode.BATCHED) {
                future = new CompletableFuture<>();
                pendingFlushes.put(generation, future);
                flushLock.lock();
                try {
                    flushCondition.signalAll();
                } finally {
                    flushLock.unlock();
                }
            }
            // ASYNC doesn't block or force.
        } catch (IOException e) {
            throw new TransactionCommitException(transactionId, "WAL_WRITE", generation, "Failed to write to WAL", e);
        } finally {
            writeLock.unlock();
        }

        if (durabilityMode == DurabilityMode.BATCHED && future != null) {
            try {
                future.get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new TransactionCommitException(transactionId, "WAL_BATCH_FORCE", generation, "Failed waiting for batched WAL flush", e);
            }
        }
    }

    public void appendRecordsDirect(List<WalRecord> records) throws IOException {
        writeLock.lock();
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            for (WalRecord rec : records) {
                writeRecordToBuffer(dos, rec);
            }
            dos.flush();
            ByteBuffer buf = ByteBuffer.wrap(baos.toByteArray());
            while (buf.hasRemaining()) {
                fileChannel.write(buf);
            }
            lastAppendedGeneration = Math.max(lastAppendedGeneration, records.get(records.size()-1).generation());
            fileChannel.force(false);
            lastForcedGeneration = Math.max(lastForcedGeneration, lastAppendedGeneration);
        } finally {
            writeLock.unlock();
        }
    }

    public void appendRollbackMarker(long transactionId, long generation, String reason) {
        writeLock.lock();
        try {
            if (closed) return;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            byte[] payload = reason == null ? new byte[0] : reason.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            writeRecordToBuffer(dos, new WalRecord(RecordWalOperation.ROLLBACK_TRANSACTION, transactionId, generation, payload));
            dos.flush();
            ByteBuffer buf = ByteBuffer.wrap(baos.toByteArray());
            while (buf.hasRemaining()) {
                fileChannel.write(buf);
            }
            lastAppendedGeneration = Math.max(lastAppendedGeneration, generation);
            if (durabilityMode == DurabilityMode.SYNC) {
                fileChannel.force(false);
                lastForcedGeneration = Math.max(lastForcedGeneration, generation);
            }
        } catch (IOException e) {
            // Ignore failures for rollback diagnostics, as it is non-blocking and best effort.
        } finally {
            writeLock.unlock();
        }
    }

    private void writeRecordToBuffer(DataOutputStream dos, WalRecord record) throws IOException {
        byte[] payload = record.payload();
        int typeOrdinal = record.type().ordinal();

        // CRC computed over Type (1 byte) + TxID (8 bytes) + Gen (8 bytes) + Payload (N bytes)
        CRC32C crc = new CRC32C();
        crc.update(typeOrdinal);
        
        ByteBuffer temp = ByteBuffer.allocate(16);
        temp.putLong(record.transactionId());
        temp.putLong(record.generation());
        crc.update(temp.array(), 0, 16);
        
        if (payload.length > 0) {
            crc.update(payload, 0, payload.length);
        }
        long checksum = crc.getValue();

        dos.writeInt(MAGIC);
        dos.writeByte(typeOrdinal);
        dos.writeLong(record.transactionId());
        dos.writeLong(record.generation());
        dos.writeInt(payload.length);
        dos.writeInt((int) checksum);
        if (payload.length > 0) {
            dos.write(payload);
        }
    }

    public List<WalRecord> readAllRecords() throws IOException {
        writeLock.lock();
        try {
            if (!Files.exists(walPath) || Files.size(walPath) == 0) {
                return Collections.emptyList();
            }
            fileChannel.position(0);

            List<WalRecord> records = new ArrayList<>();
            ByteBuffer headerBuf = ByteBuffer.allocate(29); // 4 + 1 + 8 + 8 + 4 + 4

            while (true) {
                headerBuf.clear();
                long recordStartPos = fileChannel.position();
                int read = readFully(fileChannel, headerBuf);
                if (read == 0) {
                    break; // EOF
                }
                if (read < 29) {
                    // Truncated header at end of file - safe to truncate to last valid position
                    fileChannel.truncate(recordStartPos);
                    break;
                }

                headerBuf.flip();
                int magic = headerBuf.getInt();
                if (magic != MAGIC) {
                    if (fileChannel.position() == fileChannel.size()) {
                        fileChannel.truncate(recordStartPos);
                        break;
                    }
                    throw new CorruptWalException("Corrupt WAL: Magic bytes mismatch in log file");
                }

                int typeOrdinal = headerBuf.get() & 0xFF;
                long txId = headerBuf.getLong();
                long gen = headerBuf.getLong();
                int payloadLen = headerBuf.getInt();
                int checksum = headerBuf.getInt();

                int maxRecordSize = 64 * 1024 * 1024; // 64 MB
                if (payloadLen < 0 || payloadLen > maxRecordSize) {
                    if (fileChannel.position() == fileChannel.size()) {
                        fileChannel.truncate(recordStartPos);
                        break;
                    }
                    throw new CorruptWalException("Corrupt WAL: Invalid payload length " + payloadLen);
                }

                if (typeOrdinal >= RecordWalOperation.values().length) {
                    if (fileChannel.position() == fileChannel.size()) {
                        fileChannel.truncate(recordStartPos);
                        break;
                    }
                    throw new CorruptWalException("Corrupt WAL: Unknown operation type ordinal " + typeOrdinal);
                }

                RecordWalOperation type = RecordWalOperation.values()[typeOrdinal];
                byte[] payload = new byte[payloadLen];
                ByteBuffer payloadBuf = ByteBuffer.wrap(payload);
                int payloadRead = readFully(fileChannel, payloadBuf);
                if (payloadRead < payloadLen) {
                    fileChannel.truncate(recordStartPos);
                    break;
                }

                CRC32C crc = new CRC32C();
                crc.update(typeOrdinal);
                byte[] txIdBytes = new byte[8];
                ByteBuffer.wrap(txIdBytes).putLong(txId);
                crc.update(txIdBytes);
                byte[] genBytes = new byte[8];
                ByteBuffer.wrap(genBytes).putLong(gen);
                crc.update(genBytes);
                crc.update(payload);

                if ((int) crc.getValue() != checksum) {
                    throw new CorruptWalException("Corrupt WAL: Checksum mismatch in log file");
                }

                records.add(new WalRecord(type, txId, gen, payload));
            }
            return records;
        } finally {
            writeLock.unlock();
        }
    }

    private int readFully(FileChannel channel, ByteBuffer buf) throws IOException {
        int total = 0;
        while (buf.hasRemaining()) {
            int read = channel.read(buf);
            if (read == -1) {
                break;
            }
            total += read;
        }
        return total;
    }

    public void truncate() throws IOException {
        writeLock.lock();
        try {
            fileChannel.truncate(0);
            fileChannel.position(0);
            fileChannel.force(true);
            lastAppendedGeneration = 0;
            lastForcedGeneration = 0;
        } finally {
            writeLock.unlock();
        }
    }

    private void startBatchedFlusher() {
        executor.submit(() -> {
            while (!closed) {
                flushLock.lock();
                try {
                    flushCondition.await(10, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } finally {
                    flushLock.unlock();
                }
                flushPending();
            }
            flushPending();
        });
    }

    private void flushPending() {
        writeLock.lock();
        try {
            if (fileChannel != null && fileChannel.isOpen()) {
                fileChannel.force(false);
            }
            long maxFlushedGen = pendingFlushes.isEmpty() ? lastAppendedGeneration : pendingFlushes.lastKey();
            lastForcedGeneration = Math.max(lastForcedGeneration, maxFlushedGen);

            // Complete all futures <= maxFlushedGen
            while (!pendingFlushes.isEmpty()) {
                long gen = pendingFlushes.firstKey();
                if (gen <= maxFlushedGen) {
                    CompletableFuture<Void> future = pendingFlushes.remove(gen);
                    if (future != null) {
                        future.complete(null);
                    }
                } else {
                    break;
                }
            }
        } catch (IOException e) {
            // Fail all pending futures
            while (!pendingFlushes.isEmpty()) {
                Map.Entry<Long, CompletableFuture<Void>> entry = pendingFlushes.pollFirstEntry();
                if (entry != null) {
                    entry.getValue().completeExceptionally(e);
                }
            }
        } finally {
            writeLock.unlock();
        }
    }

    public void flush() {
        writeLock.lock();
        try {
            if (closed) return;
            fileChannel.force(false);
            lastForcedGeneration = Math.max(lastForcedGeneration, lastAppendedGeneration);
            // Complete all pending futures
            while (!pendingFlushes.isEmpty()) {
                Map.Entry<Long, CompletableFuture<Void>> entry = pendingFlushes.pollFirstEntry();
                if (entry != null) {
                    entry.getValue().complete(null);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to flush WAL", e);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void close() {
        writeLock.lock();
        try {
            if (closed) return;
            executor.shutdownNow();
            // Force remaining unwritten bytes to disk BEFORE setting closed = true
            try {
                if (fileChannel != null && fileChannel.isOpen()) {
                    fileChannel.force(false);
                    lastForcedGeneration = Math.max(lastForcedGeneration, lastAppendedGeneration);
                }
            } catch (IOException e) {
                // ignore
            }
            closed = true;
            if (fileChannel != null) {
                fileChannel.close();
            }
        } catch (IOException e) {
            // ignore
        } finally {
            writeLock.unlock();
        }
    }
}
