package io.lemadane.recordmaster;

@FunctionalInterface
public interface TransactionFunction<R> {
    R execute(RecordTransaction transaction) throws Exception;
}
