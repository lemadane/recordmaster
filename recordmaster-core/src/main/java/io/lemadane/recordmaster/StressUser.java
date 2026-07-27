package io.lemadane.recordmaster;

import io.lemadane.recordmaster.annotations.Id;
import io.lemadane.recordmaster.annotations.Index;
import java.util.UUID;

public record StressUser(
    @Id UUID id,
    @Index(unique = true) String email,
    String name
) implements Record {}
