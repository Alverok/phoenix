package com.phoenix.model;

/**
 * Transport-neutral lifecycle of one task attempt, as observable by Core.
 *
 * <pre>
 * ACCEPTED -> DELIVERED -> STARTED -> COMPLETED
 *     \            \           \
 *      +------------+-----------+--> FAILED
 * </pre>
 *
 * <ul>
 *     <li>{@link #ACCEPTED}  - the transport took responsibility for the task
 *         (HTTP: placed in the per-device pull queue; MQTT: published).
 *         The task has NOT reached the node.</li>
 *     <li>{@link #DELIVERED} - the task reached the node (HTTP: the node
 *         polled it; push: the request was handed to the node).</li>
 *     <li>{@link #STARTED}   - the node acknowledged that execution began.
 *         Optional: transports/agents that cannot report it simply skip it.</li>
 *     <li>{@link #COMPLETED} - the node reported a result (success or a
 *         node-side failure).</li>
 *     <li>{@link #FAILED}    - the attempt ended without a node result
 *         (delivery/execution timeout, transport failure).</li>
 * </ul>
 *
 * <p>The state order is the declaration order; transitions never go backwards.
 */
public enum TaskLifecycleState {
    ACCEPTED,
    DELIVERED,
    STARTED,
    COMPLETED,
    FAILED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}
