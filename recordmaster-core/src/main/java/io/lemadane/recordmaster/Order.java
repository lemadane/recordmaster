package io.lemadane.recordmaster;

import io.lemadane.recordmaster.annotations.*;
import java.util.UUID;

public record Order(
    @Id UUID id,
    Double total
) implements io.lemadane.recordmaster.Record {
}
