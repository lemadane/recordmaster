package io.lemadane.recordmaster;

public class TransactionRollbackException extends RecordMasterException {
    private final long transactionId;

    public TransactionRollbackException(long transactionId, String message) {
        super(message);
        this.transactionId = transactionId;
    }

    public TransactionRollbackException(long transactionId, String message, Throwable cause) {
        super(message, cause);
        this.transactionId = transactionId;
    }

    public long transactionId() {
        return transactionId;
    }
}
