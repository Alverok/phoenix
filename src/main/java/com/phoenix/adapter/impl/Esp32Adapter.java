package com.phoenix.adapter.impl;

import java.util.Set;

import com.phoenix.adapter.IHardwareAdapter;
import com.phoenix.model.HardwareClass;
import com.phoenix.model.NodeExecutionTarget;
import com.phoenix.model.NodeInfo;
import com.phoenix.model.ResourceStatus;
import com.phoenix.model.TaskDescriptor;
import com.phoenix.model.TaskResult;

public class Esp32Adapter implements IHardwareAdapter {

    @Override
    public NodeInfo registerNode() {
        System.out.println("[Esp32Adapter] Providing ESP32 capability template...");
        return new NodeInfo(
                "template", "ESP32-node", HardwareClass.ESP32, "xtensa",
                1, 1, "esp32", Set.of());
    }

    @Override
    public ResourceStatus getResourceStatus() {
        return ResourceStatus.of(60.0, 0, 1, 3, 60.0, 0.5);
    }

    @Override
    public TaskResult executeTask(TaskDescriptor task, NodeExecutionTarget target) {
        System.out.printf("[Esp32Adapter] Executing task %s on node %s (%s)%n",
                task.id(), target.displayName(), target.nodeId());
        return new TaskResult(task.id(), "SUCCESS", "Computed Monte-Carlo pi ~= 3.14");
    }

    @Override
    public void reportResult(TaskResult result) {
        System.out.println("[Esp32Adapter] Result reported: " + result.status());
    }
}
