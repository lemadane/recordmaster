package io.lemadane.recordmaster;

public class InvalidTransactionStateException extends RecordMasterException {
    public InvalidTransactionStateException(String message) {
        super(message);
    }
}
