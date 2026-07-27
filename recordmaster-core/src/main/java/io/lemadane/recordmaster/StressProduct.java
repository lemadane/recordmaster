package io.lemadane.recordmaster;

import io.lemadane.recordmaster.annotations.Id;
import io.lemadane.recordmaster.annotations.Index;
import java.util.UUID;

public record StressProduct(
    @Id UUID id,
    String name,
    double price
) implements Record {}
