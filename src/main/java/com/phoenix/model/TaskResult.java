package com.phoenix.model;

/**
 * Outcome of a task after it has run on a node, returned to the
 * Task Dispatcher / Result Aggregator.
 *
 * <p>Unchanged from Day 1 on purpose: {@link com.phoenix.adapter.IHardwareAdapter}
 * is a capability contract, not a per-node one, so it has no business knowing
 * which physical node instance it's being called on behalf of. "Which node
 * ran this?" is dispatch-level context, not adapter-level context — see
 * {@link com.phoenix.dispatch.DispatchResult}, which wraps a TaskResult
 * together with the nodeId that produced it.
 */
public record TaskResult(
        String taskId,
        String status,
        String outputData
) {}
