package com.phoenix.net;

import com.phoenix.model.NodeExecutionTarget;
import com.phoenix.model.TaskDescriptor;
import com.phoenix.model.TaskResult;

public interface NodeAgentClient {

    TaskResult execute(
            NodeExecutionTarget target,
            TaskDescriptor task
    );
}