package io.lemadane.recordmaster.core;

import io.lemadane.recordmaster.RecordMasterException;

public class CorruptWalException extends RecordMasterException {

    public CorruptWalException(String message) {
        super(message);
    }

    public CorruptWalException(String message, Throwable cause) {
        super(message, cause);
    }
}
