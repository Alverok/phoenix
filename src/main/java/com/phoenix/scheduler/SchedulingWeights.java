package com.phoenix.scheduler;

/**
 * Configurable weights for the placement-cost formula:
 *
 * <pre>
 * C(n) = w1*executionTime + w2*energyCost + w3*loadPenalty + w4*networkLatency
 * </pre>
 *
 * <p>Named to match the design document's terms exactly (execution time,
 * energy cost, queue/load, network latency) rather than the generic
 * T(n)/E(n)/L(n)/R(n) notation, per the design discussion — the point is
 * this maps back onto the proposal without translation when explaining it.
 *
 * <p>Kept as its own small record (rather than constants buried in
 * {@link WeightedPlacementCostModel}) so weights can be tuned, loaded from
 * config, or swept across experiments later (e.g. comparing Greedy vs a
 * future Memetic scheduler under different weight profiles) without
 * touching the cost model's code.
 */
public record SchedulingWeights(
        double executionTimeWeight,
        double energyCostWeight,
        double loadPenaltyWeight,
        double networkLatencyWeight
) {

    /** Reasonable Day-2 defaults: execution time dominates, the rest matter less. */
    public static SchedulingWeights defaults() {
        return new SchedulingWeights(0.4, 0.2, 0.2, 0.2);
    }
}
