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

## ⚙️ Installation (via JitPack)

To import RecordMaster as a dependency in your Gradle project:

1. Add the JitPack repository to your `settings.gradle` or `build.gradle`:
```groovy
repositories {
    mavenCentral()
    maven { url 'https://jitpack.io' }
}
```

2. Add the core and annotation processor dependencies to your project's `build.gradle`:
```groovy
dependencies {
    // Core Engine
    implementation 'com.github.lemadane.recordmaster:recordmaster-core:main-SNAPSHOT'
    
    // Metamodel Annotation Processor
    annotationProcessor 'com.github.lemadane.recordmaster:recordmaster-processor:main-SNAPSHOT'
}
```
*(Replace `main-SNAPSHOT` with a specific release tag or git commit hash for production stability).*

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

## 🧠 Handling Relational Joins & Analytics

Since RecordMaster is a transactional key-value/object store rather than a full SQL relational engine, it does not include a native SQL query optimizer. We address complex queries, joins, and analytics through three features:

### 1. Programmatic Joins (Type-Safe Metamodel)
Because RecordMaster maps data directly to Java records, you perform table joins using clean Java APIs and generated metamodels, powered by Java 21 virtual threads:

```java
// Query customer and join their orders programmatically
db.transaction(tx -> {
    Customer customer = tx.table(Customer.class).findById(customerId).orElseThrow();
    
    // Type-safe lookup using generated OrderFields metadata
    List<Order> orders = tx.table(Order.class).query()
        .where(OrderFields.customerId.eq(customerId))
        .list();
        
    return new CustomerWithOrders(customer, orders);
});
```

### 2. Index-Based Query Routing
When you query fields annotated with `@Index` (e.g., `where(CustomerFields.email.eq(email))`), the `QueryEngine` automatically routes the lookup through the fast, in-memory `IndexState` maps. It obtains the record ID and pointer instantly, bypassing full-table disk scans and only reading the matching record's bytes from disk.

### 3. The SQL Migration Escape Hatch
If your analytical queries grow too complex or require heavy SQL aggregation/join operations, you can easily migrate your entire RecordMaster database to a relational SQL target (PostgreSQL, MySQL, or SQLite) in one step:

```java
// Migrate RecordMaster database schema and records to a PostgreSQL target
Connection conn = DriverManager.getConnection("jdbc:postgresql://localhost:5432/mydb", "user", "pass");
SqlMigrator.migrate(db, conn, SqlDialect.POSTGRESQL);
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
