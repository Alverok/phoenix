package com.phoenix.model;

/**
 * The physical hardware family of a node, as distinct from {@code adapterType}
 * (the plugin key Guice/AdapterRegistry uses to resolve an {@link
 * com.phoenix.adapter.IHardwareAdapter}).
 *
 * <p>Kept separate on purpose (Obsidian discussion, "device-registry" thread):
 * two nodes can share a hardware class (e.g. two Linux boxes, one x86_64 one
 * aarch64) while both resolving to the same {@code LinuxAdapter}. Conversely,
 * as Phoenix grows, two different hardware classes could theoretically be
 * driven by adapters that share logic. Don't collapse this into adapterType.
 */
public enum HardwareClass {
    LINUX,
    ANDROID,
    ESP32
}
