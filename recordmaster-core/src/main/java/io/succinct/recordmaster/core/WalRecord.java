package io.succinct.recordmaster.core;

public record WalRecord(
    RecordWalOperation type,
    long transactionId,
    long generation,
    byte[] payload
) {
}
