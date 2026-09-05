package com.phoenix.model;

import java.util.Set;

/**
 * The self-registration message a node agent sends to Phoenix Core:
 * "Hello, I'm new (or reconnecting), here's what I can do."
 *
 * <p>This is transport-agnostic on purpose (design discussion, "device
 * detection" section): {@link com.phoenix.core.DeviceRegistry} does not
 * care whether this request was built by
 * {@link com.phoenix.sim.NodeSimulator} in-process (Day 2), decoded off an
 * MQTT topic, or received on a REST endpoint (Day 3+). Whoever produces one
 * of these and calls {@code DeviceRegistry.register(...)} is doing the same
 * conceptual thing.
 *
 * <p>{@code existingNodeId} is {@code null}/blank for a first-time
 * enrollment; when present, DeviceRegistry treats this as a reconnect of an
 * already-known node and keeps the stable identity rather than minting a
 * new one (design discussion, "don't give devices disposable identities").
 */
public record NodeRegistrationRequest(
        String existingNodeId,
        String displayName,
        HardwareClass hardwareClass,
        String architecture,
        int cpuCores,
        long memoryMb,
        String adapterType,
        Set<String> capabilities,
        ResourceStatus initialStatus
) {

    public boolean isReconnect() {
        return existingNodeId != null && !existingNodeId.isBlank();
    }
}
