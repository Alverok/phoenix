package com.phoenix.model;

/**
 * Identity and static capability profile of an edge node, captured at
 * registration time (Design doc, Sec. 4 "Device Registry & Discovery").
 */
public record NodeInfo(
        String id,
        String hardwareClass,
        int cpuCores,
        long memoryMb
) {}
