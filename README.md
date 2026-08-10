# RecordMaster

[![JitPack](https://jitpack.io/v/lemadane/recordmaster.svg)](https://jitpack.io/#lemadane/recordmaster)
[![Build Status](https://github.com/lemadane/recordmaster/actions/workflows/ci.yml/badge.svg)](https://github.com/lemadane/recordmaster/actions/workflows/ci.yml)

**RecordMaster** is a lightweight, embedded transactional database engine for **Java 21+** that persists standard Java `record` objects directly to disk.

It provides type-safe CRUD operations, callback and manual transactions, rollback, read-your-own-writes, a checksummed append-only Write-Ahead Log (WAL), startup recovery, snapshots, compaction, online hot backups, exclusive process locking, generated query metamodels, and JDBC-based migration to PostgreSQL, MySQL, and SQLite.

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
- **Snapshots and compaction** — rewrite table files using only active records and truncate the WAL safely under `compactionLock`.
- **Online Hot Backups** — atomic checkpoint snapshot creation and WAL/storage copying via `db.backup(targetDir)`.
- **Exclusive Directory Locking** — prevents multi-instance corruption via `recordmaster.lock` OS `FileLock`.
- **Generated metamodels** — the annotation processor generates classes such as `UserFields`.
- **Nested record encoding** — nested Java records can be stored inline inside a persisted record.
- **Durability modes** — `SYNC`, `BATCHED`, and `ASYNC`.
- **Zero-dependency core** — the storage engine and annotation processor use the Java standard library.
- **SQL migration** — migrate schema and records through JDBC to PostgreSQL, MySQL, or SQLite.

---

## Project Structure

RecordMaster is a multi-module Gradle project:

- [`recordmaster-core`](recordmaster-core/) — database API, transactions, binary codec, WAL, recovery, snapshots, compaction, hot backups, query engine, and SQL migration.
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
    implementation 'com.github.lemadane.recordmaster:recordmaster-core:v1.0.0-rc3'
    annotationProcessor 'com.github.lemadane.recordmaster:recordmaster-processor:v1.0.0-rc3'
}
```

Or for Maven:

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>com.github.lemadane.recordmaster</groupId>
        <artifactId>recordmaster-core</artifactId>
        <version>v1.0.0-rc3</version>
    </dependency>
</dependencies>
```

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
    @Index(name = "idx_user_email", unique = true) String email,
    String name,
    Instant createdAt
) implements Record {
}
```

## 2. Open a database

```java
import io.lemadane.recordmaster.RecordDatabase;
import java.nio.file.Path;

Path dbPath = Path.of("./data/my-db");

try (RecordDatabase db = RecordDatabase.open(dbPath)) {
    // Database is open, isolated with an exclusive recordmaster.lock
}
```

## 3. Perform Transactional Operations

```java
db.transaction(tx -> {
    RecordTable<UUID, User> users = tx.table(User.class);

    UUID userId = UUID.randomUUID();
    User user = new User(userId, "alice@example.com", "Alice", Instant.now());
    users.insert(user);
});
```

## 4. Online Hot Backups

```java
Path backupPath = Path.of("./data/my-db-backup");

// Safely creates a checkpoint snapshot and copies WAL and storage files online
db.backup(backupPath);
```

---

# Operational Backup & Maintenance

RecordMaster provides an online hot-backup API `db.backup(Path targetDir)`.

```java
db.backup(backupDir);
```

Under the hood, `backup()` executes an atomic checkpoint sequence:
1. Forces active WAL buffers to disk (`walManager.flush()`).
2. Creates a checkpoint snapshot (`snapshot.<generation>`).
3. Copies the snapshot, active `wal.log`, and `.db` table storage files to `targetDir`.
4. Validates backup checksum integrity before returning.

---

# Build and Verification

Run all unit, integration, concurrency, corruption, and SIGKILL crash tests:

```bash
./gradlew test
```

Build all modules:

```bash
./gradlew build
```

Run the multi-threaded SIGKILL crash-injection harness:

```bash
./gradlew :recordmaster-core:test --tests "io.lemadane.recordmaster.ChaosE2ETest"
```

---

# License

RecordMaster is licensed under the [MIT License](LICENSE).