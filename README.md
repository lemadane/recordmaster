# RecordMaster

**RecordMaster** is a lightweight, embedded transactional database engine for **Java 21** that persists standard Java `record` objects directly to disk.

It provides type-safe CRUD operations, callback and manual transactions, rollback, read-your-own-writes, a checksummed append-only Write-Ahead Log (WAL), startup recovery, snapshots, compaction, generated query metamodels, and JDBC-based migration to PostgreSQL, MySQL, and SQLite.

> [!IMPORTANT]
> **Current query-engine status**
>
> `findById(id)` performs a direct primary-key-to-file-pointer lookup and reads only the requested record.
>
> Fluent field queries such as `query().where(UserFields.email.eq(...))` are functionally supported, but the current `QueryEngine.list()` implementation still reads the active records and filters them in Java. Secondary index metadata and in-memory index state are maintained, and unique indexes are enforced during commit, but index-backed candidate selection is not yet connected to query execution.
>
> `Query.explain()` is currently descriptive: it may report an index-oriented plan based on metadata even though `list()` still performs a scan.

---

## Features

- **Native Java record persistence** — store Java `record` objects without JDBC row mapping or an ORM.
- **Type-safe tables** — access records through `RecordTable<ID, T>`.
- **Transactional CRUD** — insert, update, upsert, delete, clear, and query across multiple tables in one transaction.
- **Automatic rollback** — callback transactions roll back when their callback throws.
- **Explicit rollback control** — use `rollback()`, `setRollbackOnly()`, and rollback reasons.
- **Read-your-own-writes** — inserts and updates are visible inside the transaction before commit.
- **No dirty reads** — uncommitted transaction changes are kept in a transaction-local overlay.
- **Virtual-thread transaction callbacks** — top-level `db.transaction(...)` callbacks run on Java 21 virtual threads.
- **Append-only WAL** — transactions are framed with `BEGIN`, mutation records, and `COMMIT`.
- **Checksummed recovery** — WAL records and snapshots use CRC32C validation.
- **Snapshots and compaction** — rewrite table files using only active records and truncate the WAL.
- **Generated metamodels** — the annotation processor generates classes such as `UserFields`.
- **Nested record encoding** — nested Java records can be stored inline inside a persisted record.
- **Durability modes** — `SYNC`, `BATCHED`, and `ASYNC`.
- **Zero-dependency core** — the storage engine and annotation processor use the Java standard library.
- **SQL migration** — migrate schema and records through JDBC to PostgreSQL, MySQL, or SQLite.

---

## Project Structure

RecordMaster is a multi-module Gradle project:

- [`recordmaster-core`](recordmaster-core/) — database API, transactions, binary codec, WAL, recovery, snapshots, compaction, query engine, and SQL migration.
- [`recordmaster-processor`](recordmaster-processor/) — annotation processor that generates type-safe field metamodels.
- [`recordmaster-demo`](recordmaster-demo/) — Spring Boot demo for transactional writes and rollback.
- [`recordmaster-sqlite-demo`](recordmaster-sqlite-demo/) — migration demo using SQLite.
- [`LICENSE`](LICENSE) — MIT License.

---

## Requirements

- Java 21 or later
- Gradle, the included Gradle wrapper, or another JVM build tool
- A writable directory for database files

---

## Installation with JitPack

Add JitPack to your repositories:

```groovy
repositories {
    mavenCentral()
    maven { url 'https://jitpack.io' }
}
```

Add the core library and annotation processor:

```groovy
dependencies {
    implementation 'com.github.lemadane.recordmaster:recordmaster-core:main-SNAPSHOT'
    annotationProcessor 'com.github.lemadane.recordmaster:recordmaster-processor:main-SNAPSHOT'
}
```

For reproducible production builds, replace `main-SNAPSHOT` with a release tag or commit hash.

---

# Quick Start

## 1. Define a persisted record

A top-level entity stored in a table must implement RecordMaster's `Record` marker interface. Use `@Table` to set the table name, `@Id` to identify the primary key, and `@Index` to declare secondary-index metadata.

```java
package example;

import io.lemadane.recordmaster.Record;
import io.lemadane.recordmaster.annotations.Id;
import io.lemadane.recordmaster.annotations.Index;
import io.lemadane.recordmaster.annotations.Table;

import java.time.Instant;
import java.util.UUID;

@Table("users")
public record User(
        @Id UUID id,
        @Index UUID tenantId,
        @Index(unique = true) String email,
        String name,
        int age,
        Address address,
        @Index(ordered = true) Instant createdAt
) implements Record {
}
```

A nested record does not need to implement RecordMaster's marker interface when it is stored inline as a component of another persisted record:

```java
package example;

public record Address(
        String street,
        String city,
        String province,
        String postalCode
) {
}
```

For the example above:

- The table is named `users`.
- `id` is the primary key.
- `email` is checked for uniqueness during transaction commit.
- `tenantId`, `email`, and `createdAt` produce index metadata and index state.
- `Address` is encoded inside the `User` record rather than stored in a separate table.

### Table and ID resolution

RecordMaster resolves metadata in this order:

- `@Table("name")` sets the table name; otherwise the Java record's simple class name is used.
- A component accessor annotated with `@Id` is preferred as the primary key.
- An accessor named `id` is accepted as a fallback.
- If neither is found, the first record component is used as the ID.

Explicitly annotating the ID is recommended.

---

## 2. Open the database

`RecordDatabase.open(path)` uses `DurabilityMode.SYNC` by default:

```java
import io.lemadane.recordmaster.RecordDatabase;

import java.nio.file.Path;

try (RecordDatabase db =
             RecordDatabase.open(Path.of("./data/application-db"))) {

    // Work with tables here.
}
```

You can select a durability mode through the builder:

```java
import io.lemadane.recordmaster.DurabilityMode;
import io.lemadane.recordmaster.RecordDatabase;

import java.nio.file.Path;

RecordDatabase db = RecordDatabase.builder()
        .directory(Path.of("./data/application-db"))
        .durabilityMode(DurabilityMode.SYNC)
        .build();
```

### Durability modes

| Mode | Behavior |
|---|---|
| `SYNC` | Forces the WAL to stable storage before commit returns. This is the safest default. |
| `BATCHED` | Groups WAL flushes through a background flusher and waits for the relevant batch. |
| `ASYNC` | Writes to the WAL without forcing it before commit returns. It offers lower latency but recent commits may be lost after a process, operating-system, or power failure. |

Use `db.flush()` or `db.flushAsync()` when an explicit WAL flush is required.

---

## 3. Get a table

```java
import io.lemadane.recordmaster.RecordTable;

import java.util.UUID;

RecordTable<UUID, User> users = db.table(User.class);
```

The first table resolution registers its table, ID, entity, and index metadata.

---

# Saving Data

## Save inside an explicit callback transaction

```java
import java.time.Instant;
import java.util.UUID;

UUID tenantId = UUID.randomUUID();
UUID userId = UUID.randomUUID();

User user = new User(
        userId,
        tenantId,
        "lemuel@example.com",
        "Lemuel Adane",
        35,
        new Address(
                "123 Example Street",
                "Manila",
                "Metro Manila",
                "1000"
        ),
        Instant.now()
);

db.transaction(tx -> {
    RecordTable<UUID, User> users = tx.table(User.class);
    users.insert(user);
});
```

When the callback completes normally, RecordMaster commits the transaction. When the callback throws, RecordMaster rolls it back.

A transaction may also return a value:

```java
User saved = db.transaction(tx -> {
    RecordTable<UUID, User> users = tx.table(User.class);
    return users.insert(user);
});
```

## Save through an automatic transaction

A table obtained directly from the database is not attached to an explicit transaction. Calling a write method on it automatically opens and commits a transaction:

```java
RecordTable<UUID, User> users = db.table(User.class);
users.insert(user);
```

This is convenient for a single operation. Use an explicit transaction when multiple operations must commit or roll back together.

---

# Transaction Behavior

## Read your own writes

A staged insert or update can be read inside the same transaction before it is committed:

```java
db.transaction(tx -> {
    RecordTable<UUID, User> users = tx.table(User.class);

    users.insert(user);

    User staged = users.findById(user.id())
            .orElseThrow();

    System.out.println(staged.name());
});
```

## Automatic rollback on exception

```java
db.transaction(tx -> {
    RecordTable<UUID, User> users = tx.table(User.class);
    users.insert(user);

    throw new IllegalStateException("Cancel the transaction");
});
```

The staged insert is discarded.

## Explicit rollback

```java
db.transaction(tx -> {
    RecordTable<UUID, User> users = tx.table(User.class);
    users.insert(user);

    tx.rollback();
});
```

## Rollback-only state

```java
db.transaction(tx -> {
    RecordTable<UUID, User> users = tx.table(User.class);
    users.insert(user);

    tx.setRollbackOnly("Business validation failed");
});
```

An attempted commit of a rollback-only transaction rolls the transaction back and raises a transaction-rollback exception.

## Manual transaction

```java
import io.lemadane.recordmaster.RecordTransaction;

try (RecordTransaction tx = db.beginTransaction()) {
    RecordTable<UUID, User> users = tx.table(User.class);

    users.insert(user);
    tx.commit();
}
```

Closing an active manual transaction without calling `commit()` rolls it back automatically.

Nested write transactions are not supported.

---

# Querying Data

## Query by primary key

```java
RecordTable<UUID, User> users = db.table(User.class);

User found = users.findById(userId)
        .orElseThrow();
```

`findById()` is the direct lookup path:

```text
primary key
    ↓
in-memory primary-key map
    ↓
RecordPointer(offset, size)
    ↓
read the exact byte range from users.db
    ↓
BinaryCodec.deserialize(...)
    ↓
User
```

Inside a transaction, `findById()` checks the transaction overlay first:

1. Cleared table state
2. Staged deletes
3. Staged inserts
4. Staged updates
5. Committed primary-key pointer

---

## Generated field metamodel

The annotation processor generates a companion class for each RecordMaster entity. For `User`, it generates a class conceptually similar to:

```java
public final class UserFields {

    public static final Field<User, UUID> id =
            new Field<>("id", User::id);

    public static final Field<User, UUID> tenantId =
            new Field<>("tenantId", User::tenantId);

    public static final Field<User, String> email =
            new Field<>("email", User::email);

    public static final Field<User, String> name =
            new Field<>("name", User::name);

    public static final Field<User, Integer> age =
            new Field<>("age", User::age);

    public static final Field<User, Address> address =
            new Field<>("address", User::address);

    public static final Field<User, Instant> createdAt =
            new Field<>("createdAt", User::createdAt);

    private UserFields() {
    }
}
```

The generated fields provide compile-time checked query values and getters.

---

## Find by another field

```java
import java.util.Optional;

Optional<User> result = users.query()
        .where(UserFields.email.eq("lemuel@example.com"))
        .findFirst();
```

## Filter by tenant

```java
import java.util.List;

List<User> tenantUsers = users.query()
        .where(UserFields.tenantId.eq(tenantId))
        .list();
```

## Multiple conditions

```java
List<User> workingAgeUsers = users.query()
        .where(UserFields.tenantId.eq(tenantId))
        .where(UserFields.age.gte(18))
        .where(UserFields.age.lt(65))
        .list();
```

Multiple `where()` calls are evaluated as **AND** conditions.

## Supported field operators

```java
UserFields.email.eq("lemuel@example.com");
UserFields.email.ne("blocked@example.com");

UserFields.age.gt(18);
UserFields.age.gte(18);
UserFields.age.lt(65);
UserFields.age.lte(65);
```

The comparison operators require values that can be compared meaningfully at runtime.

## Sorting

```java
import io.lemadane.recordmaster.SortOrder;

List<User> alphabetical = users.query()
        .where(UserFields.tenantId.eq(tenantId))
        .orderBy(SortOrder.asc(UserFields.name))
        .list();
```

Descending order:

```java
List<User> newest = users.query()
        .where(UserFields.tenantId.eq(tenantId))
        .orderBy(SortOrder.desc(UserFields.createdAt))
        .list();
```

## Offset and limit

```java
List<User> page = users.query()
        .where(UserFields.tenantId.eq(tenantId))
        .orderBy(SortOrder.asc(UserFields.name))
        .offset(0)
        .limit(20)
        .list();
```

The current query engine materializes matching results, performs sorting in memory, and then applies offset and limit.

---

## Query a nested record component

The generated `UserFields.address` represents the complete `Address` value. It does not automatically generate `UserFields.address.city`.

You can define a field manually:

```java
import io.lemadane.recordmaster.Field;

private static final Field<User, String> USER_CITY =
        new Field<>(
                "address.city",
                user -> user.address() == null
                        ? null
                        : user.address().city()
        );
```

Then use it like any other field:

```java
List<User> manilaUsers = users.query()
        .where(USER_CITY.eq("Manila"))
        .list();
```

This works because a `Field<T, V>` contains a field name and a Java getter function.

The nested component is not automatically indexable through `@Index` on the parent record. When an address must be independently indexed, shared, updated, or referenced by several entities, store it as its own RecordMaster table and keep an `addressId` in `User`.

---

# Current Query Execution Behavior

The current implementation behaves as follows:

| Operation | Current execution |
|---|---|
| `findById(id)` | Direct primary-key pointer lookup; reads only the selected record bytes. |
| `query().where(field.eq(value))` | Scans active committed records, deserializes them, merges the transaction overlay, and filters in Java. |
| Range conditions | Full scan and Java comparison. |
| Multiple `where()` calls | Full scan with AND filtering. |
| `orderBy(...)` | In-memory sorting after filtering. |
| `offset(...)` and `limit(...)` | Applied after result materialization and sorting. |
| `findFirst()` | Calls the same list execution path with a limit of one. |
| Unique `@Index` | Enforced during commit. |
| Secondary index state | Maintained, but not yet used to select query candidates. |
| `explain()` | Reports a strategy from metadata; it does not currently guarantee that `list()` uses that strategy. |

A future index-routing implementation should use equality or range conditions to obtain candidate IDs from `IndexState`, merge staged index changes, and read only the candidate records from disk.

---

# Updating and Deleting Data

## Update

The record ID must already exist:

```java
User existing = users.findById(userId)
        .orElseThrow();

User updated = new User(
        existing.id(),
        existing.tenantId(),
        existing.email(),
        "Lemuel A. Adane",
        36,
        existing.address(),
        existing.createdAt()
);

users.update(updated);
```

## Upsert

`upsert()` updates an existing ID or inserts a new one:

```java
users.upsert(updated);
```

## Delete by ID

```java
boolean deleted = users.deleteById(userId);
```

## Clear a table

```java
users.clear();
```

## RecordTable API

```java
Optional<T> findById(ID id);

T insert(T record);

T update(T record);

T upsert(T record);

boolean deleteById(ID id);

void clear();

Query<T> query();
```

---

# How RecordMaster Persists an Insert

When you call:

```java
users.insert(user);
```

inside an active transaction, RecordMaster performs the following lifecycle:

1. Extracts the primary key.
2. Rejects a null ID.
3. Checks staged and committed state for a duplicate primary key.
4. Places the record in the transaction-local table change set.
5. Stages corresponding secondary-index changes.
6. Makes the staged record visible to reads in the same transaction.
7. On commit, validates unique-index constraints.
8. Serializes the Java record into RecordMaster's binary format.
9. Builds WAL mutation records.
10. Appends a WAL transaction containing `BEGIN`, the mutations, and `COMMIT`.
11. Applies the configured WAL durability mode.
12. Appends the serialized bytes to the table-specific `.db` file.
13. Updates the primary-key pointer map and secondary-index state.
14. Publishes the next committed database generation.

If a crash occurs after a committed WAL transaction but before all table-file changes are applied, startup recovery can replay the committed WAL mutations.

### Update behavior

Updates are append-only at the table-file level:

1. The new record version is serialized and appended.
2. The active primary-key pointer moves to the new byte range.
3. The old record bytes remain stale until compaction.

### Delete behavior

A delete removes the active pointer and index entries. Existing bytes remain in the append-only table file until compaction.

---

# Binary Codec

RecordMaster serializes record components by name and value. The current codec supports:

| Value | Supported |
|---|---|
| `null` | Yes |
| `String` | Yes |
| `UUID` | Yes |
| `Instant` | Yes |
| `int` / `Integer` | Yes |
| `long` / `Long` | Yes |
| `double` / `Double` | Yes |
| `boolean` / `Boolean` | Yes |
| Enums | Yes |
| Nested Java records | Yes |

Types not currently handled by the binary codec include:

- `BigDecimal`
- `LocalDate`, `LocalDateTime`, and other date/time classes except `Instant`
- `float`, `short`, `byte`, and `char`
- Arrays
- Collections and maps
- Arbitrary non-record classes

Trying to persist an unsupported non-null value raises an unsupported-field-type error.

---

# Storage Layout

A database directory can contain files such as:

```text
application-db/
├── wal.log
├── users.db
├── orders.db
└── snapshot.42
```

- `wal.log` contains framed transaction records.
- `<tableName>.db` contains append-only binary record versions for one table.
- `snapshot.<generation>` is a checksummed binary snapshot created during compaction.
- Temporary snapshot and compaction files may appear while those operations are running.

The snapshot is binary, not JSON. JSON export is a separate operation.

---

# Memory Model

RecordMaster does not intentionally keep every deserialized entity object in the Java heap.

The in-memory committed state contains:

- Table and entity metadata
- A primary-key-to-`RecordPointer` map
- Secondary-index structures
- The current database generation
- Transaction-local overlays while transactions are active

A `RecordPointer` contains a `long` offset and an `int` size. Those fields represent 12 bytes of raw data, but the real JVM memory cost per entry is higher because maps, keys, object headers, references, alignment, and secondary indexes also consume memory.

When `findById()` is called, RecordMaster reads the exact byte range identified by the pointer and deserializes it. Record objects can be garbage-collected after the application releases them. Repeated reads may also benefit from the operating system's filesystem page cache.

Field queries currently read and deserialize all active candidate records because index-backed query routing is not yet implemented.

---

# WAL, Recovery, and Compaction

## WAL framing

A committed write transaction is stored as:

```text
BEGIN_TRANSACTION
MUTATION
MUTATION
...
COMMIT_TRANSACTION
```

Each WAL record contains magic bytes, operation type, transaction ID, generation, payload length, payload, and a CRC32C checksum.

## Startup recovery

When opening a database, RecordMaster:

1. Finds and validates the latest snapshot, when one exists.
2. Reconstructs table metadata and active records from the snapshot.
3. Reads WAL records.
4. Ignores an incomplete or truncated tail.
5. Replays fully committed transactions.
6. Discards transactions without a valid commit marker.
7. Rebuilds active primary-key pointers and index state.

Corruption detected in the middle of the WAL is treated differently from a truncated final record and can stop startup recovery.

## Compaction

```java
db.compact();
```

Compaction:

1. Acquires the database write lock.
2. Writes an atomic CRC32C-protected `snapshot.<generation>` file.
3. Rewrites every table file using only active records.
4. Replaces old pointers with the new offsets.
5. Truncates the WAL.

An asynchronous wrapper is also available:

```java
db.compactAsync();
```

---

# JSON Export and Import

Export the committed database state:

```java
db.exportJson(Path.of("./exports/database.json"));
```

Import previously exported data after the required table metadata has been registered:

```java
db.importJson(Path.of("./exports/database.json"));
```

Validate JSON round trips with the exact record types used by your application, especially when nested records are involved.

---

# Programmatic Relationships and Joins

RecordMaster is not a SQL relational engine and does not provide a SQL join optimizer. Relationships are normally represented by storing another record's ID.

```java
@Table("orders")
public record Order(
        @Id UUID id,
        @Index UUID tenantId,
        @Index UUID customerId,
        String description,
        Instant createdAt
) implements Record {
}
```

A programmatic join can be written as:

```java
CustomerWithOrders result = db.transaction(tx -> {
    RecordTable<UUID, User> users = tx.table(User.class);
    RecordTable<UUID, Order> orders = tx.table(Order.class);

    User customer = users.findById(customerId)
            .orElseThrow();

    List<Order> customerOrders = orders.query()
            .where(OrderFields.customerId.eq(customerId))
            .list();

    return new CustomerWithOrders(customer, customerOrders);
});
```

The order lookup currently scans the active order records. When a related entity is already identified by ID, prefer `findById()`.

---

# SQL Migration

RecordMaster includes a JDBC-based migration utility under `io.lemadane.recordmaster.migration`.

Supported target dialects are:

- PostgreSQL
- MySQL
- SQLite

Example:

```java
import io.lemadane.recordmaster.migration.SqlDialect;
import io.lemadane.recordmaster.migration.SqlMigrator;

import java.sql.Connection;
import java.sql.DriverManager;

try (Connection connection = DriverManager.getConnection(
        "jdbc:postgresql://localhost:5432/application",
        "application_user",
        "secret"
)) {
    SqlMigrator.migrate(
            db,
            connection,
            SqlDialect.POSTGRESQL
    );
}
```

The JDBC driver for the selected target database must be supplied by the application. It is not part of RecordMaster's zero-dependency core.

The migrator creates tables, creates declared indexes, and inserts the active records. Complex analytics, joins, and aggregations are better performed after migration in the target SQL database.

---

# RecordMaster and SQLite

RecordMaster and SQLite target different priorities.

| RecordMaster | SQLite |
|---|---|
| Pure Java embedded engine | Mature embedded relational database |
| Direct Java record persistence | SQL tables and rows |
| Type-safe Java CRUD API | SQL and JDBC |
| Direct in-memory primary-key pointer lookup | B-tree indexes and query planning |
| Programmatic relationships | Native joins, aggregates, and constraints |
| Current field queries scan in Java | Mature index-backed query execution |
| Java 21 virtual-thread transaction callbacks | JDBC-driver-dependent behavior |
| Young project with a focused feature set | Decades of production hardening |

RecordMaster is a good fit when an application primarily needs Java-record persistence, transactional key-based access, embedded deployment, and a small pure-Java core.

SQLite is usually the stronger choice when the application needs sophisticated filtering, joins, reporting, ad hoc SQL, mature tooling, or extensively proven database behavior.

Benchmark both systems with the application's actual data model and workload rather than assuming one is universally faster.

---

# Operational Backup Note

The current public `main` API does not expose a `db.backup(Path)` hot-backup method.

Do not independently copy `wal.log`, table files, and snapshots while writes are active and assume the result is transactionally consistent. For a simple consistent filesystem backup:

1. Stop writes.
2. Close the `RecordDatabase`.
3. Copy the entire database directory as one unit.
4. Restore the entire directory as one unit.
5. Test restoration regularly.

A future online backup API should coordinate the copy under the database write lock.

---

# Build and Verification

Run all tests:

```bash
./gradlew test
```

Build all modules:

```bash
./gradlew build
```

## Run the Spring Boot demo

```bash
./gradlew :recordmaster-demo:bootRun
```

The demo includes endpoints for atomic multi-table writes and rollback behavior.

## Run the SQLite migration demo

```bash
./gradlew :recordmaster-sqlite-demo:run
```

---

# Development Status and Roadmap

RecordMaster is an actively developed embedded database project. Important next steps include:

- Connect equality queries to unique and secondary index state.
- Connect range queries and ordered sorting to ordered index state.
- Make `Query.explain()` reflect the execution strategy actually used.
- Add generated paths for nested record components.
- Expand binary-codec type support.
- Add a lock-coordinated online backup API.
- Publish reproducible benchmarks and stress-test results.
- Expand crash, corruption, concurrency, and long-running soak tests.

Production users should pin a release or commit, test crash recovery, select the durability mode deliberately, maintain verified backups, and benchmark the expected workload.

---

# License

RecordMaster is licensed under the [MIT License](LICENSE).