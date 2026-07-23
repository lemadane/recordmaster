package io.succinct.recordmaster;

import io.succinct.recordmaster.annotations.*;
import java.time.Instant;
import java.util.UUID;

public record Customer(
    @Id UUID id,
    @Index(unique = true) String email,
    String name,
    CustomerStatus status,
    @Index(ordered = true) Instant createdAt
) implements io.succinct.recordmaster.Record {
}
