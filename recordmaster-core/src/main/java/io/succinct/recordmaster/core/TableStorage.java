package io.succinct.recordmaster.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

public final class TableStorage {

    private final Path tablePath;
    private FileChannel fileChannel;
    private long currentSize;

    public TableStorage(Path directory, String tableName) {
        this.tablePath = directory.resolve(tableName + ".db");
        try {
            Files.createDirectories(directory);
            this.fileChannel = FileChannel.open(tablePath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE);
            this.currentSize = this.fileChannel.size();
        } catch (IOException e) {
            throw new RuntimeException("Failed to open table storage file: " + tablePath, e);
        }
    }

    public synchronized RecordPointer appendRecord(byte[] bytes) throws IOException {
        long offset = currentSize;
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        while (buf.hasRemaining()) {
            offset += fileChannel.write(buf, offset);
        }
        RecordPointer ptr = new RecordPointer(currentSize, bytes.length);
        currentSize = offset;
        return ptr;
    }

    public byte[] readRecord(RecordPointer ptr) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(ptr.size());
        long readOffset = ptr.offset();
        while (buf.hasRemaining()) {
            int read = fileChannel.read(buf, readOffset);
            if (read == -1) {
                throw new IOException("Unexpected end of file reading record at offset " + ptr.offset());
            }
            readOffset += read;
        }
        return buf.array();
    }

    public synchronized void close() {
        try {
            if (fileChannel != null && fileChannel.isOpen()) {
                fileChannel.close();
            }
        } catch (IOException e) {
            // ignore
        }
    }

    /**
     * Compacts the table database file by copying only the active records to a new file,
     * discarding stale/deleted records, and updating the memory pointers map.
     *
     * @param activePointers the map of key -> RecordPointer containing the active records' pointers.
     *                       This map will be updated in-place with the new offsets.
     * @throws IOException if a file access error occurs
     */
    public synchronized void compact(Map<Object, RecordPointer> activePointers) throws IOException {
        Path compactPath = tablePath.getParent().resolve(tablePath.getFileName() + ".compact");
        Files.deleteIfExists(compactPath);

        try (FileChannel compactChannel = FileChannel.open(compactPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE)) {
            
            long writeOffset = 0;
            Map<Object, RecordPointer> newPointers = new HashMap<>();

            for (Map.Entry<Object, RecordPointer> entry : activePointers.entrySet()) {
                Object key = entry.getKey();
                RecordPointer oldPtr = entry.getValue();

                byte[] bytes = readRecord(oldPtr);
                ByteBuffer buf = ByteBuffer.wrap(bytes);
                
                long currentOffset = writeOffset;
                while (buf.hasRemaining()) {
                    writeOffset += compactChannel.write(buf, writeOffset);
                }

                newPointers.put(key, new RecordPointer(currentOffset, bytes.length));
            }

            // Sync compacted file to disk
            compactChannel.force(false);
            
            // Swap files
            fileChannel.close();
            Files.move(compactPath, tablePath, StandardCopyOption.REPLACE_EXISTING);

            // Reopen channel
            this.fileChannel = FileChannel.open(tablePath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE);
            this.currentSize = writeOffset;

            // Update in-memory pointers map in-place
            activePointers.clear();
            activePointers.putAll(newPointers);
        }
    }
}
