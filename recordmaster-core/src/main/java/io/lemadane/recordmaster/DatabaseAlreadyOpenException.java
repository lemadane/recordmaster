package io.lemadane.recordmaster;

public class DatabaseAlreadyOpenException extends RecordMasterException {

    public DatabaseAlreadyOpenException(String message) {
        super(message);
    }

    public DatabaseAlreadyOpenException(String message, Throwable cause) {
        super(message, cause);
    }
}
