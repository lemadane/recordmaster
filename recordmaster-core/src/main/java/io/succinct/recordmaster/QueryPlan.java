package io.succinct.recordmaster;

public record QueryPlan(
    String strategy,
    boolean transactionOverlayUsed
) {
}
