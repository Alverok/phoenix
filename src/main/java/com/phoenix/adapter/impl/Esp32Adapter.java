package com.phoenix.adapter.impl;

import com.phoenix.adapter.IHardwareAdapter;
import com.phoenix.model.NodeExecutionTarget;
import com.phoenix.model.TaskDescriptor;
import com.phoenix.model.TaskFailureReason;
import com.phoenix.model.TaskResult;

/**
 * Placeholder adapter for ESP32 nodes.
 *
 * <p>No ESP32 execution agent exists yet, so this adapter never reports
 * success. It fails every attempt with
 * {@link TaskFailureReason#TRANSPORT_ERROR}: the task is re-placed on another
 * node and the ESP32 node is degraded, rather than Core recording a result
 * that no device computed.
 */
public class Esp32Adapter implements IHardwareAdapter {

    @Override
    public TaskResult executeTask(
            TaskDescriptor task,
            NodeExecutionTarget target) {

        return new TaskResult(
                task.id(),
                "FAILED",
                "No execution transport for ESP32 node "
                        + target.displayName(),
                TaskFailureReason.TRANSPORT_ERROR
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