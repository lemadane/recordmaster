package io.succinct.recordmaster.core;

import io.succinct.recordmaster.DurabilityMode;
import io.succinct.recordmaster.TransactionCommitException;
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
    private volatile long lastFlushedGeneration = 0;

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

            if (durabilityMode == DurabilityMode.SYNC) {
                fileChannel.force(false);
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
            if (durabilityMode == DurabilityMode.SYNC) {
                fileChannel.force(false);
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
            fileChannel.position(0);
            List<WalRecord> records = new ArrayList<>();
            ByteBuffer headerBuf = ByteBuffer.allocate(29); // 4 + 1 + 8 + 8 + 4 + 4

            while (true) {
                headerBuf.clear();
                int read = readFully(fileChannel, headerBuf);
                if (read == 0) {
                    break; // EOF
                }
                if (read < 29) {
                    // Truncated header at end of file - safe to ignore
                    break;
                }

                headerBuf.flip();
                int magic = headerBuf.getInt();
                if (magic != MAGIC) {
                    // If magic doesn't match and we are at the end, treat as truncated.
                    // If there is more data, it's middle corruption.
                    if (fileChannel.position() == fileChannel.size()) {
                        break;
                    }
                    throw new IOException("Corrupt WAL: Magic bytes mismatch in middle of log file");
                }

                int typeOrdinal = headerBuf.get() & 0xFF;
                long txId = headerBuf.getLong();
                long gen = headerBuf.getLong();
                int payloadLen = headerBuf.getInt();
                int checksum = headerBuf.getInt();

                if (typeOrdinal >= RecordWalOperation.values().length) {
                    throw new IOException("Corrupt WAL: Unknown operation type ordinal " + typeOrdinal);
                }
                RecordWalOperation type = RecordWalOperation.values()[typeOrdinal];

                byte[] payload = new byte[payloadLen];
                ByteBuffer payloadBuf = ByteBuffer.wrap(payload);
                int payloadRead = readFully(fileChannel, payloadBuf);

                if (payloadRead < payloadLen) {
                    // Truncated payload at end of file - ignore
                    break;
                }

                // Verify checksum
                CRC32C crc = new CRC32C();
                crc.update(typeOrdinal);
                
                ByteBuffer temp = ByteBuffer.allocate(16);
                temp.putLong(txId);
                temp.putLong(gen);
                crc.update(temp.array(), 0, 16);
                
                if (payloadLen > 0) {
                    crc.update(payload, 0, payloadLen);
                }

                if ((int) crc.getValue() != checksum) {
                    // Check if this is the last record in the file. If yes, ignore as truncated.
                    if (fileChannel.position() == fileChannel.size()) {
                        break;
                    }
                    throw new IOException("Corrupt WAL: Checksum mismatch in middle of log file");
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
            lastFlushedGeneration = 0;
        } finally {
            writeLock.unlock();
        }
    }

    private void startBatchedFlusher() {
        executor.submit(() -> {
            while (!closed) {
                flushLock.lock();
                try {
                    // Wait for pending flushes or timeout (10ms batch window)
                    if (pendingFlushes.isEmpty()) {
                        flushCondition.await(10, TimeUnit.MILLISECONDS);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } finally {
                    flushLock.unlock();
                }
                flushPending();
            }
        });
    }

    private void flushPending() {
        if (pendingFlushes.isEmpty()) return;

        writeLock.lock();
        try {
            if (closed) return;
            fileChannel.force(false);
            
            long maxFlushedGen = lastFlushedGeneration;
            Map.Entry<Long, CompletableFuture<Void>> lastEntry = pendingFlushes.lastEntry();
            if (lastEntry != null) {
                maxFlushedGen = lastEntry.getKey();
            }

            lastFlushedGeneration = maxFlushedGen;

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
            lastFlushedGeneration = Long.MAX_VALUE;
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
            closed = true;
            executor.shutdownNow();
            flush();
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
