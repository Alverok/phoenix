package com.phoenix.model;

/**
 * Outcome of a task after it has run on a node, returned to the
 * Task Dispatcher / Result Aggregator.
 */
public record TaskResult(
        String taskId,
        String status,
        String outputData
) {}
