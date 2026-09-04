package com.phoenix.adapter.impl;

import com.phoenix.adapter.IHardwareAdapter;
import com.phoenix.model.NodeInfo;
import com.phoenix.model.ResourceStatus;
import com.phoenix.model.TaskDescriptor;
import com.phoenix.model.TaskResult;

/**
 * Day 1 reference implementation for legacy Android smartphones.
 * Mid-tier capabilities and moderate network quality relative to Linux.
 */
public class AndroidAdapter implements IHardwareAdapter {

    @Override
    public NodeInfo registerNode() {
        System.out.println("[AndroidAdapter] Registering legacy Android node...");
        return new NodeInfo("node-android-01", "Android", 4, 4_096);
    }

    @Override
    public ResourceStatus getResourceStatus() {
        return new ResourceStatus(32.0, 2048, 1, 1);
    }

    @Override
    public TaskResult executeTask(TaskDescriptor task) {
        System.out.println("[AndroidAdapter] Executing task natively: " + task.id());
        return new TaskResult(task.id(), "SUCCESS", "Computed Monte-Carlo pi ~= 3.1415");
    }

    @Override
    public void reportResult(TaskResult result) {
        System.out.println("[AndroidAdapter] Result reported: " + result.status());
    }
}
