package io.lemadane.recordmaster;

import io.lemadane.recordmaster.core.WalRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

public class RecordDatabaseTest {

    @TempDir
    Path tempDir;

    private RecordDatabase db;

    @BeforeEach
    public void setUp() {
        db = RecordDatabase.open(tempDir);
    }

    @AfterEach
    public void tearDown() {
        if (db != null && !db.isClosed()) {
            db.close();
        }
    }

    // --- AUTOMATIC ROLLBACK ---

    @Test
    public void testAutomaticRollbackOnCallbackException() {
        UUID id = UUID.randomUUID();
        Customer customer = new Customer(id, "test@example.com", "Test Name", CustomerStatus.ACTIVE, Instant.now());

        assertThrows(IllegalStateException.class, () -> {
            db.transaction((TransactionConsumer) tx -> {
                RecordTable<UUID, Customer> table = tx.table(Customer.class);
                table.insert(customer);
                throw new IllegalStateException("Test exception");
            });
        });

        // Verify the database state remains unchanged
        assertFalse(db.table(Customer.class).findById(id).isPresent());
    }

    @Test
    public void testAutomaticRollbackWithMultipleTables() {
        UUID customerId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        Customer customer = new Customer(customerId, "test@example.com", "Test Name", CustomerStatus.ACTIVE, Instant.now());
        Order order = new Order(orderId, 99.9);

        assertThrows(RuntimeException.class, () -> {
            db.transaction((TransactionConsumer) tx -> {
                tx.table(Customer.class).insert(customer);
                tx.table(Order.class).insert(order);
                throw new RuntimeException("Rollback multi-table");
            });
        });

        assertFalse(db.table(Customer.class).findById(customerId).isPresent());
        assertFalse(db.table(Order.class).findById(orderId).isPresent());
    }

    // --- EXPLICIT ROLLBACK ---

    @Test
    public void testExplicitRollback() {
        UUID id = UUID.randomUUID();
        Customer customer = new Customer(id, "test@example.com", "Test Name", CustomerStatus.ACTIVE, Instant.now());

        db.transaction((TransactionConsumer) tx -> {
            RecordTable<UUID, Customer> table = tx.table(Customer.class);
            table.insert(customer);
            tx.rollback();
            // Further mutations should fail
            assertThrows(InvalidTransactionStateException.class, () -> table.insert(
                new Customer(UUID.randomUUID(), "other@example.com", "Other", CustomerStatus.ACTIVE, Instant.now())
            ));
        });

        assertFalse(db.table(Customer.class).findById(id).isPresent());
    }

    @Test
    public void testRollbackIsIdempotentBeforeClose() {
        db.transaction((TransactionConsumer) tx -> {
            tx.rollback();
            tx.rollback(); // Idempotent check
        });
    }

    // --- ROLLBACK-ONLY ---

    @Test
    public void testRollbackOnlyFlag() {
        UUID id = UUID.randomUUID();
        Customer customer = new Customer(id, "test@example.com", "Test Name", CustomerStatus.ACTIVE, Instant.now());

        assertThrows(TransactionRolledBackException.class, () -> {
            db.transaction(tx -> {
                RecordTable<UUID, Customer> table = tx.table(Customer.class);
                table.insert(customer);
                tx.setRollbackOnly("Marked as rollback-only");
                assertTrue(tx.isRollbackOnly());
            });
        });

        assertFalse(db.table(Customer.class).findById(id).isPresent());
    }

    @Test
    public void testCommitOnRollbackOnlyThrows() {
        try (RecordTransaction tx = db.beginTransaction()) {
            tx.setRollbackOnly("Reason");
            assertThrows(TransactionRolledBackException.class, tx::commit);
        }
    }

    // --- MANUAL TRANSACTIONS ---

    @Test
    public void testManualTransactionCommit() {
        UUID id = UUID.randomUUID();
        Customer customer = new Customer(id, "test@example.com", "Test Name", CustomerStatus.ACTIVE, Instant.now());

        try (RecordTransaction tx = db.beginTransaction()) {
            RecordTable<UUID, Customer> table = tx.table(Customer.class);
            table.insert(customer);
            tx.commit();
        }

        assertTrue(db.table(Customer.class).findById(id).isPresent());
    }

    @Test
    public void testManualTransactionAutoRollbackOnClose() {
        UUID id = UUID.randomUUID();
        Customer customer = new Customer(id, "test@example.com", "Test Name", CustomerStatus.ACTIVE, Instant.now());

        try (RecordTransaction tx = db.beginTransaction()) {
            RecordTable<UUID, Customer> table = tx.table(Customer.class);
            table.insert(customer);
            // No commit
        }

        assertFalse(db.table(Customer.class).findById(id).isPresent());
    }

    @Test
    public void testInvalidTransitionsThrow() {
        try (RecordTransaction tx = db.beginTransaction()) {
            tx.commit();
            assertThrows(InvalidTransactionStateException.class, tx::commit);
            assertThrows(InvalidTransactionStateException.class, tx::rollback);
        }
    }

    // --- READ YOUR OWN WRITES ---

    @Test
    public void testReadYourOwnWrites() {
        UUID id = UUID.randomUUID();
        Customer customer = new Customer(id, "test@example.com", "Test Name", CustomerStatus.ACTIVE, Instant.now());

        db.transaction((TransactionConsumer) tx -> {
            RecordTable<UUID, Customer> table = tx.table(Customer.class);
            table.insert(customer);

            // Staged insert must be visible inside the transaction
            Optional<Customer> found = table.findById(id);
            assertTrue(found.isPresent());
            assertEquals(customer, found.get());

            // Staged update replaces committed
            Customer updated = new Customer(id, "test@example.com", "Updated Name", CustomerStatus.ACTIVE, customer.createdAt());
            table.update(updated);
            assertEquals("Updated Name", table.findById(id).orElseThrow().name());

            // Staged delete hides it
            table.deleteById(id);
            assertFalse(table.findById(id).isPresent());

            // Re-insert works
            table.insert(customer);
            assertTrue(table.findById(id).isPresent());

            // Clear hides all
            table.clear();
            assertFalse(table.findById(id).isPresent());
        });
    }

    @Test
    public void testTransactionQueryOverlay() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        Customer c1 = new Customer(id1, "c1@example.com", "Maria", CustomerStatus.ACTIVE, Instant.now());
        Customer c2 = new Customer(id2, "c2@example.com", "Pedro", CustomerStatus.ACTIVE, Instant.now());

        db.transaction((TransactionConsumer) tx -> {
            RecordTable<UUID, Customer> table = tx.table(Customer.class);
            table.insert(c1);
            table.insert(c2);

            // Test query DSL integration
            List<Customer> active = table.query()
                    .where(CustomerFields.status.eq(CustomerStatus.ACTIVE))
                    .list();
            assertEquals(2, active.size());

            // Staged delete excludes
            table.deleteById(id1);
            active = table.query()
                    .where(CustomerFields.status.eq(CustomerStatus.ACTIVE))
                    .list();
            assertEquals(1, active.size());
            assertEquals(c2, active.get(0));
        });
    }

    // --- ISOLATION ---

    @Test
    public void testNoDirtyReads() throws Exception {
        UUID id = UUID.randomUUID();
        Customer customer = new Customer(id, "test@example.com", "Test Name", CustomerStatus.ACTIVE, Instant.now());
        CountDownLatch transactionStarted = new CountDownLatch(1);
        CountDownLatch readAttempted = new CountDownLatch(1);

        ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
        exec.submit(() -> {
            db.transaction((TransactionConsumer) tx -> {
                tx.table(Customer.class).insert(customer);
                transactionStarted.countDown();
                try {
                    readAttempted.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        });

        transactionStarted.await();

        // Sibling readers should not see uncommitted data
        assertFalse(db.table(Customer.class).findById(id).isPresent());
        readAttempted.countDown();
        exec.close();
    }

    // --- UNIQUE-INDEX VALIDATION ---

    @Test
    public void testUniqueIndexValidationOnConflict() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        Customer c1 = new Customer(id1, "same@example.com", "User 1", CustomerStatus.ACTIVE, Instant.now());
        Customer c2 = new Customer(id2, "same@example.com", "User 2", CustomerStatus.ACTIVE, Instant.now());

        // Step 1: Insert c1
        db.transaction((TransactionConsumer) tx -> tx.table(Customer.class).insert(c1));

        // Step 2: Try to insert c2 with conflicting email
        assertThrows(DuplicateIndexValueException.class, () -> {
            db.transaction(tx -> {
                tx.table(Customer.class).insert(c2);
            });
        });

        // Step 3: Swapping unique values should succeed in a single transaction
        UUID id3 = UUID.randomUUID();
        Customer c3 = new Customer(id3, "c3@example.com", "User 3", CustomerStatus.ACTIVE, Instant.now());
        db.transaction((TransactionConsumer) tx -> tx.table(Customer.class).insert(c3));

        db.transaction((TransactionConsumer) tx -> {
            RecordTable<UUID, Customer> table = tx.table(Customer.class);
            // c1 email changes same@example.com -> temp@example.com
            // c3 email changes c3@example.com -> same@example.com
            table.update(new Customer(id1, "temp@example.com", "User 1", CustomerStatus.ACTIVE, c1.createdAt()));
            table.update(new Customer(id3, "same@example.com", "User 3", CustomerStatus.ACTIVE, c3.createdAt()));
        });

        assertEquals("temp@example.com", db.table(Customer.class).findById(id1).orElseThrow().email());
        assertEquals("same@example.com", db.table(Customer.class).findById(id3).orElseThrow().email());
    }

    // --- WAL ROLLBACK AND RECOVERY ---

    @Test
    public void testStartupRecoveryIgnoresUncommitted() throws IOException {
        db.close();

        // 1. Re-open to start fresh
        db = RecordDatabase.open(tempDir);

        UUID id1 = UUID.randomUUID();
        Customer c1 = new Customer(id1, "c1@example.com", "C1", CustomerStatus.ACTIVE, Instant.now());
        
        // 2. Commit transaction
        db.transaction((TransactionConsumer) tx -> tx.table(Customer.class).insert(c1));

        // 3. Simulate uncommitted/rolled back transaction by manually writing BEGIN and mutations but no COMMIT (we close/crash db)
        UUID id2 = UUID.randomUUID();
        Customer c2 = new Customer(id2, "c2@example.com", "C2", CustomerStatus.ACTIVE, Instant.now());
        try (RecordTransaction tx = db.beginTransaction()) {
            tx.table(Customer.class).insert(c2);
            // Simulate crash by closing database directly without committing or rolling back
        }

        db.close();

        // 4. Re-open and verify recovery
        db = RecordDatabase.open(tempDir);
        assertTrue(db.table(Customer.class).findById(id1).isPresent());
        assertFalse(db.table(Customer.class).findById(id2).isPresent());
    }

    // --- COMPACTION ---

    @Test
    public void testCompactionAndSnapshot() {
        UUID id = UUID.randomUUID();
        Customer customer = new Customer(id, "test@example.com", "Test Name", CustomerStatus.ACTIVE, Instant.now());
        db.transaction((TransactionConsumer) tx -> tx.table(Customer.class).insert(customer));

        db.compact();

        // Reopen database
        db.close();
        db = RecordDatabase.open(tempDir);

        assertTrue(db.table(Customer.class).findById(id).isPresent());
        assertEquals(db.currentGeneration(), db.snapshotGeneration());
        assertEquals(db.currentGeneration(), db.persistedGeneration());
    }

    // --- DURABILITY MODES ---

    @Test
    public void testDurabilityModes() {
        for (DurabilityMode mode : DurabilityMode.values()) {
            Path customDbPath = tempDir.resolve("db-" + mode.name());
            try (RecordDatabase customDb = RecordDatabase.builder()
                    .directory(customDbPath)
                    .durabilityMode(mode)
                    .build()) {
                
                UUID id = UUID.randomUUID();
                Customer customer = new Customer(id, "test@example.com", "Test Name", CustomerStatus.ACTIVE, Instant.now());
                customDb.transaction((TransactionConsumer) tx -> tx.table(Customer.class).insert(customer));

                assertTrue(customDb.table(Customer.class).findById(id).isPresent());
            }
        }
    }

    public record Address(String street, String city) {}
    
    public record Person(
        @io.lemadane.recordmaster.annotations.Id UUID id,
        String name,
        Address address
    ) implements io.lemadane.recordmaster.Record {}

    @Test
    public void testNestedRecordsSerializationAndRecovery() {
        UUID id = UUID.randomUUID();
        Address address = new Address("123 Main St", "Springfield");
        Person person = new Person(id, "Homer Simpson", address);

        db.transaction((TransactionConsumer) tx -> {
            tx.table(Person.class).insert(person);
        });

        Optional<Person> found = db.table(Person.class).findById(id);
        assertTrue(found.isPresent());
        assertEquals(person, found.get());
        assertEquals("123 Main St", found.get().address().street());
        assertEquals("Springfield", found.get().address().city());

        db.compact();

        db.close();
        db = RecordDatabase.open(tempDir);

        Optional<Person> recovered = db.table(Person.class).findById(id);
        assertTrue(recovered.isPresent());
        assertEquals(person, recovered.get());
        assertEquals("123 Main St", recovered.get().address().street());
    }
}
