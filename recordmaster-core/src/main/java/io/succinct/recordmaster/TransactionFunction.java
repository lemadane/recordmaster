package io.succinct.recordmaster;

@FunctionalInterface
public interface TransactionFunction<R> {
    R execute(RecordTransaction transaction) throws Exception;
}
