package io.succinct.recordmaster;

import java.util.Optional;

public class TransactionRolledBackException extends RecordMasterException {
    private final long transactionId;
    private final String rollbackReason;

    public TransactionRolledBackException(long transactionId, String rollbackReason) {
        super("Transaction " + transactionId + " rolled back" + (rollbackReason != null ? ": " + rollbackReason : ""));
        this.transactionId = transactionId;
        this.rollbackReason = rollbackReason;
    }

    public TransactionRolledBackException(long transactionId, String rollbackReason, Throwable cause) {
        super("Transaction " + transactionId + " rolled back" + (rollbackReason != null ? ": " + rollbackReason : ""), cause);
        this.transactionId = transactionId;
        this.rollbackReason = rollbackReason;
    }

    public long transactionId() {
        return transactionId;
    }

    public Optional<String> rollbackReason() {
        return Optional.ofNullable(rollbackReason);
    }
}
