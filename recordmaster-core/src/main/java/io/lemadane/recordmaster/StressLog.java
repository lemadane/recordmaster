package io.lemadane.recordmaster;

import io.lemadane.recordmaster.annotations.Id;
import io.lemadane.recordmaster.annotations.Index;
import java.util.UUID;

public record StressLog(
    @Id UUID id,
    @Index String level,
    String message,
    long timestamp
) implements Record {}
