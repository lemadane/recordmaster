package io.succinct.recordmaster;

public class RecordMasterException extends RuntimeException {
    public RecordMasterException(String message) {
        super(message);
    }

    public RecordMasterException(String message, Throwable cause) {
        super(message, cause);
    }
}
