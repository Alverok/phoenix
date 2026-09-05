package com.phoenix.model;

import java.util.Optional;
import java.util.Set;

/**
 * What a task needs from a node, kept separate from {@link TaskDescriptor}
 * per the design discussion (item 11): Oakestra-style capacity requirements
 * plus room to grow (latency budget, required hardware class, capability
 * tags) without turning TaskDescriptor into a 15-argument constructor.
 *
 * <p>Day 2 only actually uses {@code cpuCores} and {@code memoryMb} for
 * eligibility filtering in {@link com.phoenix.scheduler.GreedyCostScheduler}.
 * {@code maxNetworkLatencyMs}, {@code requiredHardwareClass} and
 * {@code requiredCapabilities} are wired into the model and the filter now
 * so a later milestone can start populating them without touching the
 * scheduler's method signature again.
 */
public record TaskRequirements(
        int cpuCores,
        long memoryMb,
        Optional<Double> maxNetworkLatencyMs,
        Optional<HardwareClass> requiredHardwareClass,
        Set<String> requiredCapabilities
) {

    /** Minimal requirements: just CPU + memory, no extra constraints. */
    public static TaskRequirements of(int cpuCores, long memoryMb) {
        return new TaskRequirements(
                cpuCores, memoryMb, Optional.empty(), Optional.empty(), Set.of());
    }
}
