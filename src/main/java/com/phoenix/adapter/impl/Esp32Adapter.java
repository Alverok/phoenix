package com.phoenix.adapter.impl;

import com.phoenix.adapter.IHardwareAdapter;
import com.phoenix.model.NodeInfo;
import com.phoenix.model.ResourceStatus;
import com.phoenix.model.TaskDescriptor;
import com.phoenix.model.TaskResult;

/**
 * Day 1 reference implementation for ESP32 microcontrollers.
 * Note: real ESP32 SRAM is ~520KB, far below the {@code long memoryMb}
 * unit used elsewhere. We round up to 1MB here rather than overloading
 * the model with a second unit — revisit if this gets confusing once
 * real firmware is wired in over MQTT.
 */
public class Esp32Adapter implements IHardwareAdapter {

    @Override
    public NodeInfo registerNode() {
        System.out.println("[Esp32Adapter] Registering ESP32 microcontroller node...");
        return new NodeInfo("node-esp32-01", "ESP32", 1, 1);
    }

    @Override
    public ResourceStatus getResourceStatus() {
        return new ResourceStatus(60.0, 0, 1, 3);
    }

    @Override
    public TaskResult executeTask(TaskDescriptor task) {
        System.out.println("[Esp32Adapter] Executing task natively: " + task.id());
        return new TaskResult(task.id(), "SUCCESS", "Computed Monte-Carlo pi ~= 3.14");
    }

    @Override
    public void reportResult(TaskResult result) {
        System.out.println("[Esp32Adapter] Result reported: " + result.status());
    }
}
