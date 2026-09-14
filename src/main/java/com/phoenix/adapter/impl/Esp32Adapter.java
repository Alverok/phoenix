package com.phoenix.adapter.impl;

import com.phoenix.adapter.IHardwareAdapter;
import com.phoenix.model.NodeExecutionTarget;
import com.phoenix.model.TaskDescriptor;
import com.phoenix.model.TaskResult;

public class Esp32Adapter implements IHardwareAdapter {

    @Override
    public TaskResult executeTask(
            TaskDescriptor task,
            NodeExecutionTarget target) {

        System.out.printf(
                "[Esp32Adapter] Executing task %s on node %s (%s)%n",
                task.id(),
                target.displayName(),
                target.nodeId()
        );

        return new TaskResult(
                task.id(),
                "SUCCESS",
                "Computed Monte-Carlo pi ~= 3.14"
        );
    }

    @Override
    public void reportResult(TaskResult result) {
        System.out.println(
                "[Esp32Adapter] Result reported: "
                        + result.status()
        );
    }
}