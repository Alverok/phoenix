package com.phoenix.core;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.google.inject.Singleton;
import com.phoenix.model.NodeInfo;
import com.phoenix.model.NodeRecord;
import com.phoenix.model.NodeRegistrationRequest;
import com.phoenix.model.NodeStatus;
import com.phoenix.model.ResourceStatus;

/**
 * Central, in-memory record of every node that has joined the Phoenix mesh
 * (Design doc, Sec. 4 "Device Registry & Discovery"; Day-2 milestone #1,
 * "The Memory").
 *
 * <p>Day-2 rule honored deliberately: entirely in-memory
 * ({@code ConcurrentHashMap<String, NodeRecord>}), no SQLite. The map is an
 * implementation detail; DeviceRegistry is the abstraction, so swapping the
 * backing store later does not change this class's public surface.
 *
 * <p>Stores {@link NodeRecord} (NodeInfo + ResourceStatus + NodeStatus +
 * heartbeat) rather than bare {@link NodeInfo}, per the design discussion:
 * the scheduler needs both static capability and live status, and bolting
 * live state onto NodeInfo later would have been awkward.
 */
@Singleton
public class DeviceRegistry {

    private final Map<String, NodeRecord> nodes = new ConcurrentHashMap<>();

    /**
     * Handles the self-registration protocol described in the design
     * discussion: a brand-new request gets a freshly minted stable
     * {@code nodeId}; a request carrying an {@code existingNodeId} (a
     * reconnect) keeps that identity and simply refreshes its record.
     *
     * @return the NodeInfo actually stored, with its resolved nodeId — read
     *         this back if you need to know the assigned id for a new node.
     */
    public NodeInfo register(NodeRegistrationRequest request) {
        String nodeId = request.isReconnect() ? request.existingNodeId() : UUID.randomUUID().toString();

        NodeInfo info = new NodeInfo(
                nodeId,
                request.displayName(),
                request.hardwareClass(),
                request.architecture(),
                request.cpuCores(),
                request.memoryMb(),
                request.adapterType(),
                request.capabilities());

        NodeRecord record = NodeRecord.newlyRegistered(info, request.initialStatus());
        nodes.put(nodeId, record);

        System.out.printf(
                "[Registry] Node registered: id=%s name=%s class=%s adapter=%s%n",
                nodeId, info.displayName(), info.hardwareClass(), info.adapterType());

        return info;
    }

    /** Lower-level register, used when NodeInfo/ResourceStatus are already assembled. */
    public void register(NodeInfo info, ResourceStatus initialStatus) {
        nodes.put(info.nodeId(), NodeRecord.newlyRegistered(info, initialStatus));
        System.out.printf(
                "[Registry] Node registered: id=%s name=%s class=%s adapter=%s%n",
                info.nodeId(), info.displayName(), info.hardwareClass(), info.adapterType());
    }

    public void remove(String nodeId) {
        NodeRecord removed = nodes.remove(nodeId);
        if (removed != null) {
            System.out.println("[Registry] Node removed: " + nodeId);
        }
    }

    public Optional<NodeRecord> get(String nodeId) {
        return Optional.ofNullable(nodes.get(nodeId));
    }
    
    /** Finds a node by display name — used to detect "same device re-announcing" for upsert-style heartbeats. */
    public Optional<NodeRecord> findByDisplayName(String displayName) {
        return nodes.values().stream()
                .filter(r -> r.info().displayName().equals(displayName))
                .findFirst();
    }

    public List<NodeRecord> list() {
        return List.copyOf(nodes.values());
    }

    /** Only nodes currently eligible for scheduling (ONLINE or DEGRADED). */
    public List<NodeRecord> listSchedulable() {
        return nodes.values().stream()
                .filter(NodeRecord::isSchedulable)
                .toList();
    }

    /** Refresh a node's live resource snapshot (e.g. after a heartbeat/report). */
    public void updateStatus(String nodeId, ResourceStatus newStatus) {
        nodes.computeIfPresent(nodeId, (id, record) -> record.withStatus(newStatus).withHeartbeatNow());
    }

    /** Record a heartbeat without changing resource status (liveness only). */
    public void updateHeartbeat(String nodeId) {
        nodes.computeIfPresent(nodeId, (id, record) -> {
            NodeRecord refreshed = record.withHeartbeatNow();
            // A heartbeat from a previously DEGRADED/OFFLINE node means it's back.
            if (refreshed.state() != NodeStatus.ONLINE) {
                refreshed = refreshed.withState(NodeStatus.ONLINE);
            }
            return refreshed;
        });
    }

    public void markDegraded(String nodeId) {
        transitionState(nodeId, NodeStatus.DEGRADED);
    }

    public void markOffline(String nodeId) {
        transitionState(nodeId, NodeStatus.OFFLINE);
    }

    private void transitionState(String nodeId, NodeStatus newState) {
        nodes.computeIfPresent(nodeId, (id, record) -> {
            System.out.printf("[Registry] Node %s -> %s%n", id, newState);
            return record.withState(newState);
        });
    }

    public int size() {
        return nodes.size();
    }
}
