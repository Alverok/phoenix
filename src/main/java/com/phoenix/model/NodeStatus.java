package com.phoenix.model;

/**
 * Lifecycle state of a registered node, mirroring the Oakestra-style
 * worker lifecycle called out in the design discussion:
 *
 * <pre>
 * NEW -&gt; REGISTERING -&gt; ONLINE &lt;-&gt; DEGRADED -&gt; OFFLINE
 * </pre>
 *
 * <p>Day 2 does not run a real background heartbeat monitor thread, but the
 * data model and {@link com.phoenix.core.DeviceRegistry} API already speak
 * this vocabulary so a future heartbeat-timeout watcher (Day 3+) only has to
 * plug in a state transition, not redesign the registry.
 */
public enum NodeStatus {
    /** Registration request received but not yet confirmed. */
    NEW,
    /** Enrollment in progress (identity being assigned/confirmed). */
    REGISTERING,
    /** Node is registered and heartbeating normally. */
    ONLINE,
    /** Node is reachable but showing signs of trouble (missed heartbeats,
     *  high load) — still schedulable but penalized by the cost model. */
    DEGRADED,
    /** Node has not been heard from within the failure window and is not
     *  eligible for scheduling. */
    OFFLINE
}
