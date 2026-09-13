package com.phoenix.adapter.impl;

import java.util.Set;

import com.google.inject.Inject;
import com.phoenix.adapter.IHardwareAdapter;
import com.phoenix.model.HardwareClass;
import com.phoenix.model.NodeExecutionTarget;
import com.phoenix.model.NodeInfo;
import com.phoenix.model.ResourceStatus;
import com.phoenix.model.TaskDescriptor;
import com.phoenix.model.TaskResult;
import com.phoenix.net.NodeAgentClient;

public class AndroidAdapter implements IHardwareAdapter {

    private final NodeAgentClient client;

    @Inject
    public AndroidAdapter(NodeAgentClient client) {
        this.client = client;
    }

    @Override
    public NodeInfo registerNode() {

        System.out.println(
                "[AndroidAdapter] Providing Android capability template..."
        );

        return new NodeInfo(
                "template",
                "Android-node",
                HardwareClass.ANDROID,
                "aarch64",
                4,
                4_096,
                "android",
                Set.of()
        );
    }

    @Override
    public ResourceStatus getResourceStatus() {

        return ResourceStatus.of(
                32.0,
                2048,
                1,
                1,
                25.0,
                2.0
        );
    }

    @Override
    public TaskResult executeTask(
            TaskDescriptor task,
            NodeExecutionTarget target) {

        System.out.printf(
                "[AndroidAdapter] Submitting %s to real node %s%n",
                task.id(),
                target.displayName()
        );

        return client.execute(
                target,
                task
        );
    }

    @Override
    public void reportResult(TaskResult result) {

        System.out.println(
                "[AndroidAdapter] Result reported: "
                        + result.status()
        );
    }
}
