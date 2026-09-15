package com.phoenix.model;

import java.time.Instant;
import java.util.Objects;

/**
 * The DeviceRegistry's view of one physical node:
 * static identity/capability, live resource state, and lifecycle status.
 *
 * <p>NodeRecord is immutable. Registry updates produce new records instead
 * of mutating existing instances.
 */
public record NodeRecord(
        NodeInfo info,
        ResourceStatus status,
        NodeStatus state,
        Instant lastHeartbeat
) {

    /**
     * A freshly registered node starts ONLINE because registration itself
     * establishes an initial liveness observation.
     */
    public static NodeRecord newlyRegistered(
            NodeInfo info,
            ResourceStatus initialStatus) {

        Objects.requireNonNull(
                info,
                "info"
        );

        Objects.requireNonNull(
                initialStatus,
                "initialStatus"
        );

        return new NodeRecord(
                info,
                initialStatus,
                NodeStatus.ONLINE,
                Instant.now()
        );
    }

    public NodeRecord withStatus(
            ResourceStatus newStatus) {

        return new NodeRecord(
                info,
                newStatus,
                state,
                lastHeartbeat
        );
    }

    public NodeRecord withState(
            NodeStatus newState) {

        return new NodeRecord(
                info,
                status,
                newState,
                lastHeartbeat
        );
    }

    public NodeRecord withHeartbeatNow() {

        return new NodeRecord(
                info,
                status,
                state,
                Instant.now()
        );
    }

    /**
     * Only healthy nodes should receive NEW work.
     *
     * ONLINE:
     *   fully eligible.
     *
     * DEGRADED:
     *   still tracked and allowed to recover, but no new work is assigned
     *   until a fresh heartbeat proves liveness again.
     *
     * OFFLINE:
     *   not schedulable.
     */
    public boolean isSchedulable() {

        return state == NodeStatus.ONLINE;
    }

    public String nodeId() {
        return info.nodeId();
    }
}
