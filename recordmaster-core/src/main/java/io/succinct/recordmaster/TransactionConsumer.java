package io.succinct.recordmaster;

@FunctionalInterface
public interface TransactionConsumer {
    void execute(RecordTransaction transaction) throws Exception;
}
