package io.lemadane.recordmaster;

import io.lemadane.recordmaster.core.CorruptWalException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

public class WalCorruptionTest {

    @Test
    public void testCorruptWalPayloadLengthThrowsCorruptWalException(@TempDir Path dbDir) throws Exception {
        Files.createDirectories(dbDir);
        Path walFile = dbDir.resolve("wal.log");

        try (FileOutputStream fos = new FileOutputStream(walFile.toFile());
             DataOutputStream dos = new DataOutputStream(fos)) {
            // Write valid header magic
            dos.writeInt(0x524d574c);
            dos.writeByte(1); // BEGIN
            dos.writeLong(100L); // TxId
            dos.writeLong(1L);   // Gen
            dos.writeInt(-500);  // Corrupt negative payload length!
            dos.writeInt(0);     // Checksum
            dos.flush();

            // Append extra data so it's in the middle of file
            dos.writeInt(12345);
            dos.flush();
        }

        assertThrows(CorruptWalException.class, () -> {
            RecordDatabase.open(dbDir);
        });
    }

    @Test
    public void testCorruptWalOversizedPayloadLengthThrowsCorruptWalException(@TempDir Path dbDir) throws Exception {
        Files.createDirectories(dbDir);
        Path walFile = dbDir.resolve("wal.log");

        try (FileOutputStream fos = new FileOutputStream(walFile.toFile());
             DataOutputStream dos = new DataOutputStream(fos)) {
            dos.writeInt(0x524d574c);
            dos.writeByte(1); // BEGIN
            dos.writeLong(100L); // TxId
            dos.writeLong(1L);   // Gen
            dos.writeInt(100 * 1024 * 1024);  // 100MB (exceeds 64MB limit)
            dos.writeInt(0);     // Checksum
            dos.flush();

            dos.writeInt(12345);
            dos.flush();
        }

        assertThrows(CorruptWalException.class, () -> {
            RecordDatabase.open(dbDir);
        });
    }
}
