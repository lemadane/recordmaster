package io.lemadane.recordmaster;

@FunctionalInterface
public interface TransactionConsumer {
    void execute(RecordTransaction transaction) throws Exception;
}
