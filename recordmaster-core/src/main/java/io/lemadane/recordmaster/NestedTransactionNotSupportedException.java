package io.lemadane.recordmaster;

public class NestedTransactionNotSupportedException extends RecordMasterException {
    public NestedTransactionNotSupportedException(String message) {
        super(message);
    }
}
