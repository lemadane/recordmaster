package io.succinct.recordmaster;

public enum TransactionStatus {
    ACTIVE,
    COMMITTING,
    COMMITTED,
    ROLLING_BACK,
    ROLLED_BACK,
    FAILED
}
