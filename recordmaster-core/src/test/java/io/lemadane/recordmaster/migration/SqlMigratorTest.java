package io.lemadane.recordmaster.migration;

import io.lemadane.recordmaster.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class SqlMigratorTest {

    @TempDir
    Path tempDir;

    private RecordDatabase db;

    @BeforeEach
    public void setUp() {
        db = RecordDatabase.open(tempDir);
    }

    @Test
    public void testMigrationPostgreSQL() throws Exception {
        UUID id = UUID.randomUUID();
        db.transaction((TransactionConsumer) tx -> {
            tx.table(Customer.class).insert(new Customer(
                id, "mig@example.com", "Migrator Test", CustomerStatus.ACTIVE, Instant.now()
            ));
        });

        List<String> executedSql = new ArrayList<>();
        List<Map<Integer, Object>> boundParams = new ArrayList<>();

        Statement mockStatement = mock(Statement.class, (proxy, method, args) -> {
            if (method.getName().equals("execute") && args.length > 0) {
                executedSql.add((String) args[0]);
            }
            return false;
        });

        PreparedStatement mockPreparedStatement = mock(PreparedStatement.class, new InvocationHandler() {
            private final Map<Integer, Object> params = new TreeMap<>();
            
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                String methodName = method.getName();
                if (methodName.startsWith("set") && args != null && args.length >= 2) {
                    int index = (Integer) args[0];
                    Object val = args[1];
                    params.put(index, val);
                } else if (methodName.equals("addBatch")) {
                    boundParams.add(new TreeMap<>(params));
                    params.clear();
                } else if (methodName.equals("executeBatch")) {
                    return new int[]{1};
                }
                return null;
            }
        });

        Connection mockConnection = mock(Connection.class, (proxy, method, args) -> {
            if (method.getName().equals("createStatement")) {
                return mockStatement;
            } else if (method.getName().equals("prepareStatement") && args.length > 0) {
                executedSql.add((String) args[0]);
                return mockPreparedStatement;
            }
            return null;
        });

        SqlMigrator.migrate(db, mockConnection, SqlDialect.POSTGRESQL);

        db.close();

        assertFalse(executedSql.isEmpty());

        boolean hasCreateTable = executedSql.stream().anyMatch(sql -> 
            sql.contains("CREATE TABLE IF NOT EXISTS \"Customer\"") &&
            sql.contains("\"id\" UUID") &&
            sql.contains("\"email\" VARCHAR(255)") &&
            sql.contains("\"createdAt\" TIMESTAMP WITH TIME ZONE")
        );
        assertTrue(hasCreateTable, "Should contain correct PostgreSQL CREATE TABLE statement");

        boolean hasCreateIndex = executedSql.stream().anyMatch(sql ->
            sql.contains("CREATE UNIQUE INDEX") &&
            sql.contains("\"Customer_email_idx\"") &&
            sql.contains("ON \"Customer\"")
        );
        if (!hasCreateIndex) {
            System.out.println("Executed SQLs (PostgreSQL): " + executedSql);
        }
        assertTrue(hasCreateIndex, "Should contain correct PostgreSQL index statement");

        boolean hasInsert = executedSql.stream().anyMatch(sql ->
            sql.contains("INSERT INTO \"Customer\"") &&
            sql.contains("\"id\", \"email\", \"name\", \"status\", \"createdAt\"")
        );
        assertTrue(hasInsert, "Should contain correct INSERT statement");

        assertEquals(1, boundParams.size(), "boundParams should have size 1: " + boundParams);
        Map<Integer, Object> params = boundParams.get(0);
        assertEquals(id, params.get(1), "Expected ID " + id + " in params: " + params);
        assertEquals("mig@example.com", params.get(2));
    }

    @Test
    public void testMigrationMySQL() throws Exception {
        UUID id = UUID.randomUUID();
        db.transaction((TransactionConsumer) tx -> {
            tx.table(Customer.class).insert(new Customer(
                id, "mig-mysql@example.com", "Migrator Test MySQL", CustomerStatus.ACTIVE, Instant.now()
            ));
        });

        List<String> executedSql = new ArrayList<>();
        List<Map<Integer, Object>> boundParams = new ArrayList<>();

        Statement mockStatement = mock(Statement.class, (proxy, method, args) -> {
            if (method.getName().equals("execute") && args.length > 0) {
                executedSql.add((String) args[0]);
            }
            return false;
        });

        PreparedStatement mockPreparedStatement = mock(PreparedStatement.class, new InvocationHandler() {
            private final Map<Integer, Object> params = new TreeMap<>();
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                String methodName = method.getName();
                if (methodName.startsWith("set") && args != null && args.length >= 2) {
                    int index = (Integer) args[0];
                    Object val = args[1];
                    params.put(index, val);
                } else if (methodName.equals("addBatch")) {
                    boundParams.add(new TreeMap<>(params));
                    params.clear();
                } else if (methodName.equals("executeBatch")) {
                    return new int[]{1};
                }
                return null;
            }
        });

        Connection mockConnection = mock(Connection.class, (proxy, method, args) -> {
            if (method.getName().equals("createStatement")) {
                return mockStatement;
            } else if (method.getName().equals("prepareStatement") && args.length > 0) {
                executedSql.add((String) args[0]);
                return mockPreparedStatement;
            }
            return null;
        });

        SqlMigrator.migrate(db, mockConnection, SqlDialect.MYSQL);

        db.close();

        assertFalse(executedSql.isEmpty());

        boolean hasCreateTable = executedSql.stream().anyMatch(sql -> 
            sql.contains("CREATE TABLE IF NOT EXISTS `Customer`") &&
            sql.contains("`id` VARCHAR(36)") &&
            sql.contains("`email` VARCHAR(255)") &&
            sql.contains("`createdAt` DATETIME(6)")
        );
        assertTrue(hasCreateTable, "Should contain correct MySQL CREATE TABLE statement");

        boolean hasCreateIndex = executedSql.stream().anyMatch(sql ->
            sql.contains("CREATE UNIQUE INDEX") &&
            sql.contains("`Customer_email_idx`") &&
            sql.contains("ON `Customer`")
        );
        if (!hasCreateIndex) {
            System.out.println("Executed SQLs (MySQL): " + executedSql);
        }
        assertTrue(hasCreateIndex, "Should contain correct MySQL index statement");

        boolean hasInsert = executedSql.stream().anyMatch(sql ->
            sql.contains("INSERT INTO `Customer`")
        );
        assertTrue(hasInsert);

        assertEquals(1, boundParams.size(), "boundParams should have size 1: " + boundParams);
        Map<Integer, Object> params = boundParams.get(0);
        assertEquals(id.toString(), params.get(1), "Expected ID " + id + " in params: " + params);
    }

    @SuppressWarnings("unchecked")
    private static <I> I mock(Class<I> iface, InvocationHandler handler) {
        return (I) Proxy.newProxyInstance(
            iface.getClassLoader(),
            new Class<?>[]{iface},
            handler
        );
    }
}
