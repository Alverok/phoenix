package com.phoenix.adapter.impl;

import java.util.Set;

import com.phoenix.adapter.IHardwareAdapter;
import com.phoenix.model.HardwareClass;
import com.phoenix.model.NodeExecutionTarget;
import com.phoenix.model.NodeInfo;
import com.phoenix.model.ResourceStatus;
import com.phoenix.model.TaskDescriptor;
import com.phoenix.model.TaskResult;

public class LinuxAdapter implements IHardwareAdapter {

    @Override
    public NodeInfo registerNode() {
        System.out.println("[LinuxAdapter] Providing Linux capability template...");
        return new NodeInfo(
                "template", "Linux-node", HardwareClass.LINUX, "x86_64",
                8, 16_384, "linux", Set.of());
    }

    @Override
    public ResourceStatus getResourceStatus() {
        return ResourceStatus.of(15.5, 8192, 2, 0, 8.0, 4.5);
    }

    @Override
    public TaskResult executeTask(TaskDescriptor task, NodeExecutionTarget target) {
        System.out.printf("[LinuxAdapter] Executing task %s on node %s (%s)%n",
                task.id(), target.displayName(), target.nodeId());
        return new TaskResult(task.id(), "SUCCESS", "Computed Monte-Carlo pi ~= 3.14159");
    }

    @Override
    public void reportResult(TaskResult result) {
        System.out.println("[LinuxAdapter] Result reported: " + result.status());
    }
}