package io.lemadane.recordmaster;

public class TransactionCommitException extends RecordMasterException {
    private final long transactionId;
    private final String commitPhase;
    private final long persistenceGeneration;

    public TransactionCommitException(long transactionId, String commitPhase, long persistenceGeneration, String message) {
        super(message);
        this.transactionId = transactionId;
        this.commitPhase = commitPhase;
        this.persistenceGeneration = persistenceGeneration;
    }

    public TransactionCommitException(long transactionId, String commitPhase, long persistenceGeneration, String message, Throwable cause) {
        super(message, cause);
        this.transactionId = transactionId;
        this.commitPhase = commitPhase;
        this.persistenceGeneration = persistenceGeneration;
    }

    public long transactionId() {
        return transactionId;
    }

    public String commitPhase() {
        return commitPhase;
    }

    public long persistenceGeneration() {
        return persistenceGeneration;
    }
}
