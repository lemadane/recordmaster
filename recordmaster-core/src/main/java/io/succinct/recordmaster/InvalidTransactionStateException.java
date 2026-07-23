package io.succinct.recordmaster;

public class InvalidTransactionStateException extends RecordMasterException {
    public InvalidTransactionStateException(String message) {
        super(message);
    }
}
