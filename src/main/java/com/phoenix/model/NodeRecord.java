package com.phoenix.model;

import java.time.Instant;
import java.util.Objects;

/**
 * The {@link com.phoenix.core.DeviceRegistry}'s view of one physical node:
 * static identity/capability, live resource state, and lifecycle status,
 * bundled together.
 *
 * <pre>
 * NodeRecord
 * ├── NodeInfo        (static identity/capability)
 * ├── ResourceStatus  (live resource snapshot)
 * ├── NodeStatus      (lifecycle state)
 * └── lastHeartbeat   (Instant)
 * </pre>
 *
 * <p>This is the class the design discussion calls out as the missing piece
 * in the original {@code Map<String, NodeInfo>} sketch: without it, live
 * status/heartbeat/health end up bolted onto NodeInfo (which is supposed to
 * stay static). A plain {@code Map<String, NodeRecord>} is still perfectly
 * fine as DeviceRegistry's internal storage — NodeRecord is what makes that
 * map's values richer than a single identity blob.
 *
 * <p>Immutable by design: every mutation on the registry (heartbeat, status
 * update, state transition) produces a new NodeRecord via one of the
 * {@code with*} methods rather than mutating fields in place. This plays
 * nicely with ConcurrentHashMap.compute()-style updates in DeviceRegistry.
 */
public record NodeRecord(
        NodeInfo info,
        ResourceStatus status,
        NodeStatus state,
        Instant lastHeartbeat
) {

    /** A freshly registered node: NEW state, heartbeat stamped now. */
    public static NodeRecord newlyRegistered(NodeInfo info, ResourceStatus initialStatus) {
        Objects.requireNonNull(info, "info");
        Objects.requireNonNull(initialStatus, "initialStatus");
        return new NodeRecord(info, initialStatus, NodeStatus.ONLINE, Instant.now());
    }

    public NodeRecord withStatus(ResourceStatus newStatus) {
        return new NodeRecord(info, newStatus, state, lastHeartbeat);
    }

    public NodeRecord withState(NodeStatus newState) {
        return new NodeRecord(info, status, newState, lastHeartbeat);
    }

    public NodeRecord withHeartbeatNow() {
        return new NodeRecord(info, status, state, Instant.now());
    }

    /** Convenience: is this node currently a valid scheduling candidate? */
    public boolean isSchedulable() {
        return state == NodeStatus.ONLINE || state == NodeStatus.DEGRADED;
    }

    public String nodeId() {
        return info.nodeId();
    }
}
