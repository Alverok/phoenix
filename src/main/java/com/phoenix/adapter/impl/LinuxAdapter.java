package com.phoenix.adapter.impl;

import com.phoenix.adapter.IHardwareAdapter;
import com.phoenix.model.NodeInfo;
import com.phoenix.model.ResourceStatus;
import com.phoenix.model.TaskDescriptor;
import com.phoenix.model.TaskResult;

/**
 * Day 1 reference implementation for retired Linux laptops/PCs.
 * Capabilities are hardcoded for now; a later milestone replaces this
 * with real /proc-based metrics without touching {@link IHardwareAdapter}
 * or anything in core/.
 */
public class LinuxAdapter implements IHardwareAdapter {

    @Override
    public NodeInfo registerNode() {
        System.out.println("[LinuxAdapter] Registering high-performance Linux node...");
        return new NodeInfo("node-linux-01", "Linux", 8, 16_384);
    }

    @Override
    public ResourceStatus getResourceStatus() {
        return new ResourceStatus(15.5, 8192, 2, 0);
    }

    @Override
    public TaskResult executeTask(TaskDescriptor task) {
        System.out.println("[LinuxAdapter] Executing task natively: " + task.id());
        return new TaskResult(task.id(), "SUCCESS", "Computed Monte-Carlo pi ~= 3.14159");
    }

    @Override
    public void reportResult(TaskResult result) {
        System.out.println("[LinuxAdapter] Result reported: " + result.status());
    }
}
