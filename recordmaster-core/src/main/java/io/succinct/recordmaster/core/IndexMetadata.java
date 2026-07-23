package io.succinct.recordmaster.core;

import io.succinct.recordmaster.Record;
import java.util.function.Function;

public record IndexMetadata(
    String indexName,
    String fieldName,
    boolean unique,
    boolean ordered,
    Function<Record, Object> extractor
) {
}
