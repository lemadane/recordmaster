package io.lemadane.recordmaster;

import io.lemadane.recordmaster.annotations.Id;
import io.lemadane.recordmaster.annotations.Index;
import io.lemadane.recordmaster.annotations.Table;
import io.lemadane.recordmaster.core.RecordWalOperation;
import io.lemadane.recordmaster.core.WalRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

public class SchemaWalRecoveryTest {

    @Table("custom_user")
    public record CustomUser(
        @Id Long id,
        @Index(name = "users_email_unique", unique = true) String email,
        @Index(name = "idx_user_age", ordered = true) Integer age
    ) implements Record {}

    @Test
    public void testCustomIndexNameRecovery(@TempDir Path dbDir) {
        try (RecordDatabase db = RecordDatabase.open(dbDir)) {
            RecordTable<Long, CustomUser> table = db.table(CustomUser.class);
            table.insert(new CustomUser(1L, "alice@example.com", 30));
            table.insert(new CustomUser(2L, "bob@example.com", 25));
        }

        // Reopen database from WAL
        try (RecordDatabase restoredDb = RecordDatabase.open(dbDir)) {
            RecordTable<Long, CustomUser> table = restoredDb.table(CustomUser.class);
            assertEquals(2, table.query().list().size());
            
            CustomUser user = table.findById(1L).orElse(null);
            assertNotNull(user);
            assertEquals("alice@example.com", user.email());
            assertEquals(30, user.age());
        }
    }

    @Test
    public void testUncommittedSchemaTransactionIgnoredOnRecovery(@TempDir Path dbDir) throws Exception {
        try (RecordDatabase db = RecordDatabase.open(dbDir)) {
            RecordTable<Long, CustomUser> table = db.table(CustomUser.class);
            table.insert(new CustomUser(1L, "test@example.com", 20));

            // Write an uncommitted schema transaction manually into WAL without a COMMIT record
            long uncommittedTxId = 999999L;
            long nextGen = db.currentGeneration() + 1;

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeUTF("uncommitted_table");
            dos.writeUTF("java.lang.Long");
            dos.writeUTF("io.lemadane.recordmaster.SchemaWalRecoveryTest$CustomUser");
            dos.flush();

            WalRecord beginRec = new WalRecord(RecordWalOperation.BEGIN_TRANSACTION, uncommittedTxId, nextGen, new byte[0]);
            WalRecord createTableRec = new WalRecord(RecordWalOperation.CREATE_TABLE, uncommittedTxId, nextGen, baos.toByteArray());

            // Write uncommitted WAL records directly to log
            db.getWalManager().appendRecordsDirect(List.of(beginRec, createTableRec));
            db.getWalManager().flush();
        }

        // Reopen database and verify uncommitted_table was NOT created
        try (RecordDatabase db = RecordDatabase.open(dbDir)) {
            assertFalse(db.containsTable("uncommitted_table"));
            assertTrue(db.containsTable("custom_user"));
            assertEquals(1, db.table(CustomUser.class).query().list().size());
        }
    }
}
