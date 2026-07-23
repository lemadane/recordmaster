package io.lemadane.recordmaster;

public record QueryPlan(
    String strategy,
    boolean transactionOverlayUsed
) {
}
