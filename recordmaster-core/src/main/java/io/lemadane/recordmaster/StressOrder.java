package io.lemadane.recordmaster;

import io.lemadane.recordmaster.annotations.Id;
import io.lemadane.recordmaster.annotations.Index;
import java.util.UUID;

public record StressOrder(
    @Id UUID id,
    UUID userId,
    double amount,
    long timestamp
) implements Record {}
