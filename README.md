# RecordMaster

RecordMaster is a lightweight, embedded transactional database engine for Java 21, built on Java `record` persistence. 

It guarantees **transaction rollback** as a first-class feature, supports **read-your-own-writes** and **no dirty reads** via MVCC-like volatile memory overlays, utilizes a secure append-only **Write-Ahead Log (WAL)** for startup recovery, and enforces **Java 21 Virtual Thread** execution for all transaction blocks.

---

## 🚀 Key Features

* **Java Record Persistence**: Native type-safe CRUD operations working directly on standard Java `record` objects.
* **First-Class Transaction Engine**: Supports explicit `rollback()`, rollback-only states (`setRollbackOnly()`), automatic rollback on callback exceptions, and manual transaction blocks.
* **Virtual-Thread Enforced**: Every database transaction block runs inside a JVM Virtual Thread, automatically yielding its carrier thread on blocking locks (`ReentrantLock`) and WAL flushes (`CompletableFuture`).
* **Zero External Dependencies**: The core transaction engine and annotation processor are written purely in standard Java SE libraries (zero-dependency core).
* **WAL-Based Recovery & Compaction**: Recovers cleanly from crashes by replaying fully committed transactions, ignoring incomplete log segments. Supports atomic JSON snapshot compaction.
* **Metamodel Annotation Processor**: Generates type-safe metamodel classes (e.g. `CustomerFields.status`) to execute fluent, compile-time checked DSL queries.
* **SQL Migrator**: Supports one-click, zero-dependency schema and record migration over JDBC to **PostgreSQL**, **MySQL**, and **SQLite**. Handles flat mapping, unique indexes, and converts nested java records to JSON text automatically.

---

## 📦 Project Structure

RecordMaster is organized as a multi-module Gradle project:
* [`recordmaster-core`](file:///home/lem/Projects/java/recordmaster/recordmaster-core/): The database engine, including codec, WAL manager, recovery parser, and SQL migrator.
* [`recordmaster-processor`](file:///home/lem/Projects/java/recordmaster/recordmaster-processor/): The Java annotation processor generating query metamodel fields.
* [`recordmaster-demo`](file:///home/lem/Projects/java/recordmaster/recordmaster-demo/): Spring Boot REST application demonstrating atomic rollbacks.
* [`recordmaster-sqlite-demo`](file:///home/lem/Projects/java/recordmaster/recordmaster-sqlite-demo/): A standalone demo displaying migration of flat and nested records to SQLite.

---

## ⚡ Quick Start

```java
import io.succinct.recordmaster.*;
import java.nio.file.Path;
import java.util.UUID;
import java.time.Instant;

// 1. Define your records and index annotations
public record Customer(
    @Id UUID id,
    @Index(unique = true) String email,
    String name,
    CustomerStatus status,
    @Index(ordered = true) Instant createdAt
) implements Record {}

// 2. Open the database
RecordDatabase db = RecordDatabase.open(Path.of("data/db"));

// 3. Run transactional CRUD
db.transaction(tx -> {
    RecordTable<UUID, Customer> table = tx.table(Customer.class);
    
    // Insert record (visible immediately in this transaction)
    Customer c = new Customer(UUID.randomUUID(), "john@example.com", "John Doe", CustomerStatus.ACTIVE, Instant.now());
    table.insert(c);
    
    // Querying overlays staging (read-your-own-writes)
    Customer found = table.findById(c.id()).orElseThrow();
    System.out.println("Staged name: " + found.name());
    
    // Explicitly roll back if needed
    if (shouldCancel) {
        tx.rollback();
    }
});
```

---

## 🛠️ Build and Verification

To build and run all automated tests (unit and integration tests) using Java 21:

```bash
# Run all tests
./gradlew test
```

### Running Spring Boot Demo App
Provides REST endpoints demonstrating transactional rollbacks:
```bash
./gradlew :recordmaster-demo:bootRun
```
* `POST http://localhost:8080/api/transactions/customer-with-order`: Insert customer and order atomically.
* `POST http://localhost:8080/api/transactions/rollback-demo`: Test explicit `rollback-only` operations.

### Running SQLite Migration Demo
Populates RecordMaster with records (including a nested `Address` record inside a `Person` record) and migrates them into an SQLite database file:
```bash
./gradlew :recordmaster-sqlite-demo:run
```

---

## 📜 License

RecordMaster is licensed under the [MIT License](file:///home/lem/Projects/java/recordmaster/LICENSE).
