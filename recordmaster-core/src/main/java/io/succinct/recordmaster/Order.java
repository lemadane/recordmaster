package io.succinct.recordmaster;

import io.succinct.recordmaster.annotations.*;
import java.util.UUID;

public record Order(
    @Id UUID id,
    Double total
) implements io.succinct.recordmaster.Record {
}
