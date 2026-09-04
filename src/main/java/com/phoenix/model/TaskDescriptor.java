package com.phoenix.model;

/**
 * A unit of work submitted through the API/CLI Gateway before placement.
 */
public record TaskDescriptor(
        String id,
        String type,
        int cpuRequirement,
        int memoryRequirementMb
) {}
