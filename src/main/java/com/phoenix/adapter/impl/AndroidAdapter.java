package com.phoenix.adapter.impl;

import java.util.Set;

import com.phoenix.adapter.IHardwareAdapter;
import com.phoenix.model.HardwareClass;
import com.phoenix.model.NodeExecutionTarget;
import com.phoenix.model.NodeInfo;
import com.phoenix.model.ResourceStatus;
import com.phoenix.model.TaskDescriptor;
import com.phoenix.model.TaskResult;

public class AndroidAdapter implements IHardwareAdapter {

    @Override
    public NodeInfo registerNode() {
        System.out.println("[AndroidAdapter] Providing Android capability template...");
        return new NodeInfo(
                "template", "Android-node", HardwareClass.ANDROID, "aarch64",
                4, 4_096, "android", Set.of());
    }

    @Override
    public ResourceStatus getResourceStatus() {
        return ResourceStatus.of(32.0, 2048, 1, 1, 25.0, 2.0);
    }

    @Override
    public TaskResult executeTask(TaskDescriptor task, NodeExecutionTarget target) {
        System.out.printf("[AndroidAdapter] Executing task %s on node %s (%s)%n",
                task.id(), target.displayName(), target.nodeId());
        return new TaskResult(task.id(), "SUCCESS", "Computed Monte-Carlo pi ~= 3.1415");
    }

    @Override
    public void reportResult(TaskResult result) {
        System.out.println("[AndroidAdapter] Result reported: " + result.status());
    }
}