package io.lemadane.recordmaster;

public class TransactionAlreadyActiveException extends RecordMasterException {
    public TransactionAlreadyActiveException(String message) {
        super(message);
    }
}
