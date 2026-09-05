package com.phoenix.model;

import java.time.Instant;

/**
 * Live resource snapshot of a node, polled/pushed on every heartbeat and fed
 * straight into {@link com.phoenix.scheduler.PlacementCostModel} when
 * computing placement cost (Design doc, Sec. 7 "Algorithms & Methodology").
 *
 * <p>Extended for Day 2 with {@code networkLatencyMs} and
 * {@code energyCostEstimate} so the weighted-cost formula
 * {@code C(n) = w1*T(n) + w2*E(n) + w3*L(n) + w4*R(n)} has a real field to
 * read for every one of its four terms instead of inventing a mystery
 * {@code R(n)}. Per the design discussion, field names map onto the formula
 * like this:
 *
 * <pre>
 * T(n) execution time   -&gt; derived in PlacementCostModel from cpuCores + cpuUsagePercent
 * E(n) energy cost       -&gt; energyCostEstimate
 * L(n) load / queue      -&gt; activeTasks + queueLength
 * R(n) network latency   -&gt; networkLatencyMs
 * </pre>
 *
 * <p>Day 2 rule: these numbers are mocked/hardcoded per simulated node
 * (see {@link com.phoenix.sim.NodeSimulator}), not measured. Swapping this
 * for real /proc metrics or MQTT-reported telemetry later does not require
 * touching the scheduler or registry — only whatever produces ResourceStatus
 * instances.
 */
public record ResourceStatus(
        double cpuUsagePercent,
        long availableRamMb,
        int activeTasks,
        int queueLength,
        double networkLatencyMs,
        double energyCostEstimate,
        Instant observedAt
) {

    /** Convenience factory that stamps "now" as the observation time. */
    public static ResourceStatus of(
            double cpuUsagePercent, long availableRamMb, int activeTasks,
            int queueLength, double networkLatencyMs, double energyCostEstimate) {
        return new ResourceStatus(
                cpuUsagePercent, availableRamMb, activeTasks, queueLength,
                networkLatencyMs, energyCostEstimate, Instant.now());
    }
}
