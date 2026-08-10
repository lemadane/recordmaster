package io.lemadane.recordmaster;

import io.lemadane.recordmaster.annotations.Id;
import io.lemadane.recordmaster.annotations.Index;
import io.lemadane.recordmaster.core.CorruptWalException;
import io.lemadane.recordmaster.core.RecordWalOperation;
import io.lemadane.recordmaster.core.WalManager;
import io.lemadane.recordmaster.core.WalRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class WalTornWriteE2ETest {

    public record AccountRecord(
        @Id Long id,
        @Index(unique = true) String email,
        String name,
        Long balance
    ) implements Record {}

    @Test
    public void testTruncateInsideHeaderRecovery(@TempDir Path dbDir) throws Exception {
        try (RecordDatabase db = RecordDatabase.open(dbDir)) {
            db.transaction(tx -> {
                tx.table(AccountRecord.class).insert(new AccountRecord(1L, "user1@example.com", "Alice", 1000L));
            });
            db.transaction(tx -> {
                tx.table(AccountRecord.class).insert(new AccountRecord(2L, "user2@example.com", "Bob", 2000L));
            });
        }

        Path walFile = dbDir.resolve("wal.log");
        assertTrue(Files.exists(walFile));
        long len = Files.size(walFile);
        assertTrue(len > 29);

        // Truncate 7 bytes off the end (lands inside the 29-byte COMMIT header)
        try (FileChannel channel = FileChannel.open(walFile, StandardOpenOption.WRITE)) {
            channel.truncate(len - 7);
        }

        // Reopen database and verify clean recovery of previously committed transactions
        try (RecordDatabase db = RecordDatabase.open(dbDir)) {
            RecordTable<Long, AccountRecord> table = db.table(AccountRecord.class);
            assertTrue(table.findById(1L).isPresent(), "First transaction must be recovered");
        }
    }

    @Test
    public void testTruncateInsidePayloadRecovery(@TempDir Path dbDir) throws Exception {
        try (RecordDatabase db = RecordDatabase.open(dbDir)) {
            db.table(AccountRecord.class).insert(new AccountRecord(1L, "user1@example.com", "Alice", 1000L));
        }

        Path walFile = dbDir.resolve("wal.log");
        assertTrue(Files.exists(walFile));

        byte[] largePayload = "Very long record payload for testing true payload truncation inside the record body".getBytes();
        int payloadLen = largePayload.length;

        // Append a raw INSERT record with valid 29-byte header + large payload directly to log
        try (WalManager wal = new WalManager(dbDir, DurabilityMode.SYNC)) {
            WalRecord rawInsert = new WalRecord(RecordWalOperation.INSERT, 200L, 2L, largePayload);
            wal.appendRecordsDirect(List.of(rawInsert));
        }

        long len = Files.size(walFile);

        // Truncate at: total length - (payloadLen / 2) -> lands cleanly inside the payload body
        long truncatePos = len - (payloadLen / 2);
        try (FileChannel channel = FileChannel.open(walFile, StandardOpenOption.WRITE)) {
            channel.truncate(truncatePos);
        }

        // Reopen database and verify recovery truncates incomplete payload torn write cleanly
        try (RecordDatabase db = RecordDatabase.open(dbDir)) {
            RecordTable<Long, AccountRecord> table = db.table(AccountRecord.class);
            assertTrue(table.findById(1L).isPresent(), "First committed transaction must be recovered intact");
        }
    }

    @Test
    public void testFlipCompleteHeaderMagicFailsRecovery(@TempDir Path dbDir) throws Exception {
        Files.createDirectories(dbDir);
        Path walFile = dbDir.resolve("wal.log");

        try (WalManager wal = new WalManager(dbDir, DurabilityMode.SYNC)) {
            wal.appendTransaction(100L, 1L, List.of(
                new WalRecord(RecordWalOperation.CREATE_TABLE, 100L, 1L, "test".getBytes())
            ));
        }

        assertTrue(Files.exists(walFile));

        // Flip 1 byte in the magic header field at offset 0
        try (RandomAccessFile raf = new RandomAccessFile(walFile.toFile(), "rw")) {
            raf.seek(0);
            byte b = raf.readByte();
            raf.seek(0);
            raf.writeByte(b ^ 0xFF);
        }

        assertThrows(CorruptWalException.class, () -> {
            RecordDatabase.open(dbDir);
        });
    }

    @Test
    public void testInvalidPayloadLengthFailsRecovery(@TempDir Path dbDir) throws Exception {
        Files.createDirectories(dbDir);
        Path walFile = dbDir.resolve("wal.log");

        try (WalManager wal = new WalManager(dbDir, DurabilityMode.SYNC)) {
            wal.appendTransaction(100L, 1L, List.of(
                new WalRecord(RecordWalOperation.CREATE_TABLE, 100L, 1L, "test".getBytes())
            ));
        }

        assertTrue(Files.exists(walFile));

        // Corrupt payload length field (offset 21) in the header
        try (RandomAccessFile raf = new RandomAccessFile(walFile.toFile(), "rw")) {
            raf.seek(21);
            raf.writeInt(-500); // Invalid negative length
        }

        assertThrows(CorruptWalException.class, () -> {
            RecordDatabase.open(dbDir);
        });
    }

    @Test
    public void testFlipFinalCommitCrcFailsRecovery(@TempDir Path dbDir) throws Exception {
        Files.createDirectories(dbDir);
        Path walFile = dbDir.resolve("wal.log");

        try (WalManager wal = new WalManager(dbDir, DurabilityMode.SYNC)) {
            wal.appendTransaction(100L, 1L, List.of(
                new WalRecord(RecordWalOperation.CREATE_TABLE, 100L, 1L, "test".getBytes())
            ));
        }

        assertTrue(Files.exists(walFile));
        long len = Files.size(walFile);

        // Flip 1 byte in the final COMMIT record checksum near end of WAL file
        try (RandomAccessFile raf = new RandomAccessFile(walFile.toFile(), "rw")) {
            raf.seek(len - 2);
            byte b = raf.readByte();
            raf.seek(len - 2);
            raf.writeByte(b ^ 0xFF);
        }

        assertThrows(CorruptWalException.class, () -> {
            RecordDatabase.open(dbDir);
        });
    }
}
