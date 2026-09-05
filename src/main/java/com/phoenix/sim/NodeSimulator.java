package com.phoenix.sim;

import com.google.inject.Inject;
import com.phoenix.adapter.IHardwareAdapter;
import com.phoenix.core.AdapterRegistry;
import com.phoenix.core.DeviceRegistry;
import com.phoenix.model.NodeInfo;
import com.phoenix.model.NodeRegistrationRequest;
import com.phoenix.model.ResourceStatus;

/**
 * Simulates the node self-registration protocol for Day 2 (design
 * discussion, "device detection" section): instead of network
 * scanning/mDNS/ARP, Phoenix assumes nodes announce themselves. Today that
 * announcement is simulated in-process; later it arrives over MQTT/REST.
 * {@link DeviceRegistry} does not know or care which — that's the whole
 * point of keeping registration transport-agnostic.
 *
 * <p>Registers <em>multiple distinct node instances</em> per hardware
 * class, exercising the actual {@link NodeRegistrationRequest} path rather
 * than bypassing it: this class builds a request exactly like a real node
 * agent eventually will, and lets {@link DeviceRegistry} be the sole
 * authority for identity assignment (a fresh {@code nodeId} for a new
 * node; a reused one on {@code existingNodeId != null} reconnects).
 */
public class NodeSimulator {

    private final AdapterRegistry adapterRegistry;
    private final DeviceRegistry deviceRegistry;

    @Inject
    public NodeSimulator(AdapterRegistry adapterRegistry, DeviceRegistry deviceRegistry) {
        this.adapterRegistry = adapterRegistry;
        this.deviceRegistry = deviceRegistry;
    }

    /** Registers the Day-2 demo fleet: two Linux nodes, one Android, one ESP32. */
    public void bootstrapDemoFleet() {
        registerInstance("linux", "Linux-01", ResourceStatus.of(15.5, 8192, 2, 0, 8.0, 4.5));
        registerInstance("linux", "Linux-02", ResourceStatus.of(55.0, 6144, 4, 2, 12.0, 5.0));
        registerInstance("android", "Android-01", ResourceStatus.of(32.0, 2048, 1, 1, 25.0, 2.0));
        registerInstance("esp32", "ESP32-01", ResourceStatus.of(60.0, 0, 1, 3, 60.0, 0.5));
    }

    /**
     * Simulates one node agent's registration handshake: fetch the
     * adapter's capability template, wrap it in a {@link
     * NodeRegistrationRequest} with no {@code existingNodeId} (i.e. "I'm
     * new"), and hand that to the Registry — which is the only thing
     * allowed to mint the stable {@code nodeId}.
     */
    public NodeInfo registerInstance(String adapterType, String displayName, ResourceStatus mockedStatus) {
        IHardwareAdapter adapter = adapterRegistry.resolve(adapterType);
        NodeInfo template = adapter.registerNode();

        NodeRegistrationRequest request = new NodeRegistrationRequest(
                null, // existingNodeId absent -> Registry treats this as a brand-new node
                displayName,
                template.hardwareClass(),
                template.architecture(),
                template.cpuCores(),
                template.memoryMb(),
                template.adapterType(),
                template.capabilities(),
                mockedStatus);

        return deviceRegistry.register(request);
    }
}