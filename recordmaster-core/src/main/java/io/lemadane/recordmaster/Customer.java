package io.lemadane.recordmaster;

import io.lemadane.recordmaster.annotations.*;
import java.time.Instant;
import java.util.UUID;

public record Customer(
    @Id UUID id,
    @Index(unique = true) String email,
    String name,
    CustomerStatus status,
    @Index(ordered = true) Instant createdAt
) implements io.lemadane.recordmaster.Record {
}
