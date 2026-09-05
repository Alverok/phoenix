package com.phoenix.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.phoenix.model.HardwareClass;
import com.phoenix.model.NodeInfo;
import com.phoenix.model.NodeRecord;
import com.phoenix.model.NodeStatus;
import com.phoenix.model.ResourceStatus;

/**
 * This test IS the Day-2 "Memory" milestone: it proves the registry holds
 * multiple nodes, exposes them via get()/list(), tracks heartbeats, and
 * excludes offline nodes from the scheduler's candidate pool — entirely
 * in memory, no SQLite.
 */
class DeviceRegistryTest {

    private NodeInfo sampleNode(String id, String name) {
        return new NodeInfo(id, name, HardwareClass.LINUX, "x86_64", 8, 16_384, "linux", Set.of());
    }

    private ResourceStatus sampleStatus() {
        return ResourceStatus.of(10.0, 8000, 0, 0, 5.0, 3.0);
    }

    @Test
    void registersAndRetrievesANode() {
        DeviceRegistry registry = new DeviceRegistry();
        registry.register(sampleNode("node-1", "Linux-01"), sampleStatus());

        NodeRecord record = registry.get("node-1").orElseThrow();
        assertEquals("Linux-01", record.info().displayName());
        assertEquals(NodeStatus.ONLINE, record.state());
    }

    @Test
    void listReturnsAllRegisteredNodes() {
        DeviceRegistry registry = new DeviceRegistry();
        registry.register(sampleNode("node-1", "Linux-01"), sampleStatus());
        registry.register(sampleNode("node-2", "Linux-02"), sampleStatus());

        assertEquals(2, registry.list().size());
    }

    @Test
    void removeDropsTheNode() {
        DeviceRegistry registry = new DeviceRegistry();
        registry.register(sampleNode("node-1", "Linux-01"), sampleStatus());
        registry.remove("node-1");

        assertTrue(registry.get("node-1").isEmpty());
    }

    @Test
    void heartbeatBringsADegradedNodeBackOnline() {
        DeviceRegistry registry = new DeviceRegistry();
        registry.register(sampleNode("node-1", "Linux-01"), sampleStatus());
        registry.markDegraded("node-1");
        assertEquals(NodeStatus.DEGRADED, registry.get("node-1").orElseThrow().state());

        registry.updateHeartbeat("node-1");
        assertEquals(NodeStatus.ONLINE, registry.get("node-1").orElseThrow().state());
    }

    @Test
    void offlineNodesAreExcludedFromSchedulableList() {
        DeviceRegistry registry = new DeviceRegistry();
        registry.register(sampleNode("node-1", "Linux-01"), sampleStatus());
        registry.register(sampleNode("node-2", "Linux-02"), sampleStatus());
        registry.markOffline("node-2");

        assertEquals(1, registry.listSchedulable().size());
        assertEquals("node-1", registry.listSchedulable().get(0).nodeId());
    }
}
