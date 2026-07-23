package io.lemadane.recordmaster.core;

public enum RecordWalOperation {
    BEGIN_TRANSACTION,
    INSERT,
    UPSERT,
    UPDATE,
    DELETE,
    CLEAR_TABLE,
    CREATE_TABLE,
    DROP_TABLE,
    CREATE_INDEX,
    DROP_INDEX,
    COMMIT_TRANSACTION,
    ROLLBACK_TRANSACTION
}
