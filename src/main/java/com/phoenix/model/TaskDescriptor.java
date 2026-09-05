package com.phoenix.model;

/**
 * A unit of work submitted through the API/CLI Gateway before placement.
 *
 * <p>Day 2 change: {@code cpuRequirement}/{@code memoryRequirementMb} moved
 * out into {@link TaskRequirements} (see that class for why). A convenience
 * constructor below keeps simple call sites — like the existing Day-1
 * App.java call — one line, so this refactor doesn't ripple outward more
 * than it has to.
 */
public record TaskDescriptor(
        String id,
        String type,
        TaskRequirements requirements
) {

    /** Convenience constructor matching the old (id, type, cpu, memoryMb) shape. */
    public TaskDescriptor(String id, String type, int cpuRequirement, int memoryRequirementMb) {
        this(id, type, TaskRequirements.of(cpuRequirement, memoryRequirementMb));
    }
}
