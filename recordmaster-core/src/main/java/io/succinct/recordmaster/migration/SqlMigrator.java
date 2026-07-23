package io.succinct.recordmaster.migration;

import io.succinct.recordmaster.RecordDatabase;
import io.succinct.recordmaster.RecordTable;
import io.succinct.recordmaster.annotations.Id;
import io.succinct.recordmaster.core.TableState;
import io.succinct.recordmaster.core.IndexMetadata;

import java.lang.reflect.RecordComponent;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.*;

public final class SqlMigrator {

    private SqlMigrator() {}

    /**
     * Migrates the schemas and records of all tables in the given RecordDatabase to a target relational database.
     *
     * @param db      the source RecordDatabase
     * @param conn    the open JDBC Connection to the target SQL database
     * @param dialect the target SQL dialect (POSTGRESQL, MYSQL, or SQLITE)
     * @throws SQLException if a database access error occurs
     */
    public static void migrate(RecordDatabase db, Connection conn, SqlDialect dialect) throws SQLException {
        Objects.requireNonNull(db, "Database cannot be null");
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(dialect, "Dialect cannot be null");

        Set<String> tableNames = db.tableNames();

        for (String tableName : tableNames) {
            RecordTable<?, ?> table = db.findTable(tableName)
                    .orElseThrow(() -> new SQLException("Table metadata not found for: " + tableName));
            
            createTable(table, conn, dialect);
            createIndexes(db, tableName, conn, dialect);
            insertRecords(table, conn, dialect);
        }
    }

    private static void createTable(RecordTable<?, ?> table, Connection conn, SqlDialect dialect) throws SQLException {
        Class<?> entityType = table.entityType();
        RecordComponent[] components = entityType.getRecordComponents();

        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE IF NOT EXISTS ").append(quote(table.tableName(), dialect)).append(" (");

        for (int i = 0; i < components.length; i++) {
            RecordComponent comp = components[i];
            sb.append("\n  ").append(quote(comp.getName(), dialect)).append(" ").append(mapType(comp.getType(), dialect));

            if (comp.isAnnotationPresent(Id.class) || comp.getName().equalsIgnoreCase("id")) {
                sb.append(" PRIMARY KEY");
            }

            if (i < components.length - 1) {
                sb.append(",");
            }
        }
        sb.append("\n)");

        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sb.toString());
        }
    }

    private static void createIndexes(RecordDatabase db, String tableName, Connection conn, SqlDialect dialect) throws SQLException {
        TableState ts = db.getCommittedState().getTable(tableName);
        if (ts == null) return;

        for (IndexMetadata meta : ts.indexMetadataList()) {
            if (meta.fieldName().equalsIgnoreCase("id")) {
                continue;
            }

            String indexName = meta.indexName();
            
            StringBuilder sb = new StringBuilder();
            if (meta.unique()) {
                sb.append("CREATE UNIQUE INDEX ");
            } else {
                sb.append("CREATE INDEX ");
            }

            if (dialect == SqlDialect.POSTGRESQL || dialect == SqlDialect.SQLITE) {
                sb.append("IF NOT EXISTS ");
            }

            sb.append(quote(indexName, dialect))
              .append(" ON ")
              .append(quote(tableName, dialect))
              .append(" (")
              .append(quote(meta.fieldName(), dialect))
              .append(")");

            try (Statement stmt = conn.createStatement()) {
                stmt.execute(sb.toString());
            } catch (SQLException e) {
                if (dialect == SqlDialect.MYSQL && e.getErrorCode() == 1061) {
                    continue;
                }
                throw e;
            }
        }
    }

    private static void insertRecords(RecordTable<?, ?> table, Connection conn, SqlDialect dialect) throws SQLException {
        Class<?> entityType = table.entityType();
        RecordComponent[] components = entityType.getRecordComponents();
        List<?> records = table.query().list();

        if (records.isEmpty()) {
            return;
        }

        StringBuilder sql = new StringBuilder("INSERT INTO ");
        sql.append(quote(table.tableName(), dialect)).append(" (");
        for (int i = 0; i < components.length; i++) {
            sql.append(quote(components[i].getName(), dialect));
            if (i < components.length - 1) {
                sql.append(", ");
            }
        }
        sql.append(") VALUES (");
        for (int i = 0; i < components.length; i++) {
            sql.append("?");
            if (i < components.length - 1) {
                sql.append(", ");
            }
        }
        sql.append(")");

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (Object record : records) {
                for (int i = 0; i < components.length; i++) {
                    RecordComponent comp = components[i];
                    try {
                        Object val = comp.getAccessor().invoke(record);
                        bindValue(ps, i + 1, val, comp.getType(), dialect);
                    } catch (Exception e) {
                        throw new SQLException("Failed to bind field: " + comp.getName(), e);
                    }
                }
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void bindValue(PreparedStatement ps, int paramIndex, Object val, Class<?> type, SqlDialect dialect) throws Exception {
        if (val == null) {
            ps.setNull(paramIndex, java.sql.Types.NULL);
            return;
        }

        if (type == String.class) {
            ps.setString(paramIndex, (String) val);
        } else if (type == UUID.class) {
            if (dialect == SqlDialect.POSTGRESQL) {
                ps.setObject(paramIndex, val);
            } else {
                ps.setString(paramIndex, val.toString());
            }
        } else if (type == Instant.class) {
            Instant inst = (Instant) val;
            if (dialect == SqlDialect.POSTGRESQL) {
                ps.setObject(paramIndex, inst.atOffset(java.time.ZoneOffset.UTC));
            } else if (dialect == SqlDialect.SQLITE) {
                ps.setString(paramIndex, inst.toString());
            } else {
                ps.setTimestamp(paramIndex, java.sql.Timestamp.from(inst));
            }
        } else if (type == Integer.class || type == int.class) {
            ps.setInt(paramIndex, (Integer) val);
        } else if (type == Long.class || type == long.class) {
            ps.setLong(paramIndex, (Long) val);
        } else if (type == Double.class || type == double.class) {
            ps.setDouble(paramIndex, (Double) val);
        } else if (type == Boolean.class || type == boolean.class) {
            if (dialect == SqlDialect.POSTGRESQL) {
                ps.setBoolean(paramIndex, (Boolean) val);
            } else {
                ps.setInt(paramIndex, (Boolean) val ? 1 : 0);
            }
        } else if (type.isEnum()) {
            ps.setString(paramIndex, ((Enum<?>) val).name());
        } else if (type.isRecord()) {
            String json = io.succinct.recordmaster.core.SimpleJson.serialize(val);
            ps.setString(paramIndex, json);
        } else {
            ps.setString(paramIndex, val.toString());
        }
    }

    private static String mapType(Class<?> type, SqlDialect dialect) {
        if (type == String.class) {
            return dialect == SqlDialect.SQLITE ? "TEXT" : "VARCHAR(255)";
        } else if (type == UUID.class) {
            if (dialect == SqlDialect.POSTGRESQL) return "UUID";
            if (dialect == SqlDialect.SQLITE) return "TEXT";
            return "VARCHAR(36)";
        } else if (type == Instant.class) {
            if (dialect == SqlDialect.POSTGRESQL) return "TIMESTAMP WITH TIME ZONE";
            if (dialect == SqlDialect.SQLITE) return "TEXT";
            return "DATETIME(6)";
        } else if (type == Integer.class || type == int.class) {
            return "INT";
        } else if (type == Long.class || type == long.class) {
            return "BIGINT";
        } else if (type == Double.class || type == double.class) {
            if (dialect == SqlDialect.POSTGRESQL) return "DOUBLE PRECISION";
            if (dialect == SqlDialect.SQLITE) return "REAL";
            return "DOUBLE";
        } else if (type == Boolean.class || type == boolean.class) {
            if (dialect == SqlDialect.POSTGRESQL) return "BOOLEAN";
            if (dialect == SqlDialect.SQLITE) return "INTEGER";
            return "TINYINT(1)";
        } else if (type.isEnum()) {
            return dialect == SqlDialect.SQLITE ? "TEXT" : "VARCHAR(255)";
        } else if (type.isRecord()) {
            return "TEXT";
        }
        return "TEXT";
    }

    private static String quote(String name, SqlDialect dialect) {
        return dialect == SqlDialect.POSTGRESQL || dialect == SqlDialect.SQLITE ? "\"" + name + "\"" : "`" + name + "`";
    }
}
