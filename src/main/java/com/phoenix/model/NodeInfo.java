package com.phoenix.model;

import java.util.Set;

/**
 * Identity and static capability profile of an edge node, captured at
 * registration time (Design doc, Sec. 4 "Device Registry & Discovery").
 *
 * <p>Deliberately separated from {@link ResourceStatus} (which is live,
 * changes every heartbeat) and wrapped together with it inside a
 * {@link NodeRecord}. See the Day-2 design discussion, item 4-5.
 *
 * <p>Field notes:
 * <ul>
 *   <li>{@code nodeId} — stable, machine-generated identity. Assigned once at
 *       first enrollment and never regenerated on reconnect (that's the whole
 *       point of a registry instead of a disposable scan result).</li>
 *   <li>{@code displayName} — human-facing label ("Linux-01"), not used for
 *       identity or lookups.</li>
 *   <li>{@code hardwareClass} — physical family (LINUX/ANDROID/ESP32).</li>
 *   <li>{@code architecture} — CPU ISA, free-form for now ("x86_64",
 *       "aarch64", "armv7", "xtensa"...). Not enforced against an enum
 *       because new architectures showing up later shouldn't require a code
 *       change here.</li>
 *   <li>{@code adapterType} — the plugin key {@link
 *       com.phoenix.core.AdapterRegistry} uses to resolve the concrete
 *       {@link com.phoenix.adapter.IHardwareAdapter}. Usually derived from
 *       hardwareClass 1:1 today, but kept as its own field because it does
 *       not have to stay 1:1 forever.</li>
 *   <li>{@code capabilities} — free-form capability tags (e.g. "gpu",
 *       "camera") for future eligibility filtering beyond raw CPU/RAM.
 *       Empty set is fine for Day 2; the field exists so TaskScheduler
 *       filtering can grow without a model rewrite.</li>
 * </ul>
 */
public record NodeInfo(
        String nodeId,
        String displayName,
        HardwareClass hardwareClass,
        String architecture,
        int cpuCores,
        long memoryMb,
        String adapterType,
        Set<String> capabilities
) {}
