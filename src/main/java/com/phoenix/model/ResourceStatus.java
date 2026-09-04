package com.phoenix.model;

/**
 * Live resource snapshot of a node, polled by the Scheduler when computing
 * placement cost (Design doc, Sec. 7 "Algorithms & Methodology").
 */
public record ResourceStatus(
        double cpuUsagePercent,
        long availableRamMb,
        int activeTasks,
        int queueLength
) {}
