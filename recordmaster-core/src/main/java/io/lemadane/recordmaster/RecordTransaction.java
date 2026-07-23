package io.lemadane.recordmaster;

import java.util.Optional;
import java.util.function.Function;

public interface RecordTransaction extends AutoCloseable {

    long transactionId();

    TransactionStatus status();

    boolean isActive();

    boolean isCommitted();

    boolean isRolledBack();

    boolean isRollbackOnly();

    void setRollbackOnly();

    void setRollbackOnly(String reason);

    Optional<String> rollbackReason();

    <ID, T extends Record> RecordTable<ID, T> table(
            Class<T> entityType
    );

    <ID, T extends Record> RecordTable<ID, T> table(
            String tableName,
            Class<ID> idType,
            Class<T> entityType,
            Function<T, ID> idExtractor
    );

    void commit();

    void rollback();

    @Override
    void close();
}
