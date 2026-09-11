package com.phoenix.net;

import java.util.Set;

import com.phoenix.model.HardwareClass;

/**
 * Wire representation of a node's initial registration request.
 *
 * <p>This class belongs to the network layer rather than the domain model:
 * it represents JSON received from an external node agent.
 */
public class DeviceAnnouncement {

    public String deviceId;
    public String displayName;
    public HardwareClass hardwareClass;
    public String architecture;
    public int cpuCores;
    public long memoryMb;
    public String adapterType;
    public Set<String> capabilities;
}