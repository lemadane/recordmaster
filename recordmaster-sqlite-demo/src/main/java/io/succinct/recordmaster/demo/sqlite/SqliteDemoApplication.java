package io.succinct.recordmaster.demo.sqlite;

import io.succinct.recordmaster.*;
import io.succinct.recordmaster.migration.SqlDialect;
import io.succinct.recordmaster.migration.SqlMigrator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

public class SqliteDemoApplication {

    public record Address(String street, String city) {}

    public record Person(
        @io.succinct.recordmaster.annotations.Id UUID id,
        String name,
        Address address
    ) implements io.succinct.recordmaster.Record {}

    public static void main(String[] args) {
        System.out.println("=== Starting RecordMaster to SQLite Migration Demo ===");

        try {
            // 1. Initialize RecordMaster in a temporary directory
            Path dbPath = Files.createTempDirectory("recordmaster-sqlite-demo-db");
            RecordDatabase db = RecordDatabase.open(dbPath);

            System.out.println("Populating RecordMaster database...");
            UUID customerId1 = UUID.randomUUID();
            UUID customerId2 = UUID.randomUUID();
            
            // Insert Customers
            db.transaction((TransactionConsumer) tx -> {
                tx.table(Customer.class).insert(new Customer(
                    customerId1, "alice@example.com", "Alice Smith", CustomerStatus.ACTIVE, Instant.now()
                ));
                tx.table(Customer.class).insert(new Customer(
                    customerId2, "bob@example.com", "Bob Jones", CustomerStatus.INACTIVE, Instant.now()
                ));
            });

            // Insert Person with Nested Address
            UUID personId = UUID.randomUUID();
            Address address = new Address("456 Elm St", "Springfield");
            Person person = new Person(personId, "Ned Flanders", address);
            
            db.transaction((TransactionConsumer) tx -> {
                tx.table(Person.class).insert(person);
            });

            System.out.println("RecordMaster populated successfully.");

            // 2. Setup SQLite target database
            Path targetDir = Path.of("data");
            Files.createDirectories(targetDir);
            Path sqliteFile = targetDir.resolve("sqlite-migrated.db");
            
            // Delete old file if it exists to start fresh
            Files.deleteIfExists(sqliteFile);

            String sqliteUrl = "jdbc:sqlite:" + sqliteFile.toAbsolutePath();
            System.out.println("Connecting to SQLite at: " + sqliteUrl);

            try (Connection conn = DriverManager.getConnection(sqliteUrl)) {
                // 3. Drop tables if they already exist (pre-caution)
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("DROP TABLE IF EXISTS \"Customer\"");
                    stmt.execute("DROP TABLE IF EXISTS \"Person\"");
                }

                // 4. Run the Migration
                System.out.println("Executing migration via SqlMigrator...");
                SqlMigrator.migrate(db, conn, SqlDialect.SQLITE);
                System.out.println("Migration finished successfully.");

                // 5. Query and verify the results from SQLite
                System.out.println("\n--- Verification: Querying \"Customer\" table in SQLite ---");
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT * FROM \"Customer\"")) {
                    while (rs.next()) {
                        System.out.printf("  Customer Row -> ID: %s | Email: %s | Name: %s | Status: %s | CreatedAt: %s\n",
                                rs.getString("id"),
                                rs.getString("email"),
                                rs.getString("name"),
                                rs.getString("status"),
                                rs.getString("createdAt")
                        );
                    }
                }

                System.out.println("\n--- Verification: Querying \"Person\" table in SQLite ---");
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT * FROM \"Person\"")) {
                    while (rs.next()) {
                        System.out.printf("  Person Row -> ID: %s | Name: %s | Address (JSON TEXT): %s\n",
                                rs.getString("id"),
                                rs.getString("name"),
                                rs.getString("address")
                        );
                    }
                }
            }

            // Cleanup RecordMaster
            db.close();
            System.out.println("\n=== Demo completed successfully ===");

        } catch (Exception e) {
            System.err.println("Demo failed with exception:");
            e.printStackTrace();
        }
    }
}
