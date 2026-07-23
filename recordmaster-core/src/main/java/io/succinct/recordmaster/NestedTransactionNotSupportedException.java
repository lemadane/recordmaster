package io.succinct.recordmaster;

public class NestedTransactionNotSupportedException extends RecordMasterException {
    public NestedTransactionNotSupportedException(String message) {
        super(message);
    }
}
