package com.phoenix.net;

import java.time.Duration;
import java.util.Optional;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.phoenix.model.NodeExecutionTarget;
import com.phoenix.model.TaskDescriptor;
import com.phoenix.model.TaskResult;

@Singleton
public class HttpNodeAgentClient
        implements NodeAgentClient {

    private static final Duration TASK_TIMEOUT =
            Duration.ofSeconds(30);

    private final RegistrationServer registrationServer;
    private final TaskQueueService taskQueue;

    @Inject
    public HttpNodeAgentClient(
            RegistrationServer registrationServer,
            TaskQueueService taskQueue) {

        this.registrationServer =
                registrationServer;

        this.taskQueue =
                taskQueue;
    }

    @Override
    public TaskResult execute(
            NodeExecutionTarget target,
            TaskDescriptor task) {

        Optional<String> deviceId =
                registrationServer.deviceIdForNode(
                        target.nodeId()
                );

        if (deviceId.isEmpty()) {

            return new TaskResult(
                    task.id(),
                    "FAILED",
                    "Node "
                            + target.displayName()
                            + " is not currently connected"
            );
        }

        taskQueue.submit(
                deviceId.get(),
                task
        );

        System.out.printf(
                "[HttpNodeAgentClient] Queued %s for %s; waiting for agent...%n",
                task.id(),
                target.displayName()
        );

        try {

            return taskQueue.awaitResult(
                    task.id(),
                    TASK_TIMEOUT
            );

        } catch (Exception exception) {

            return new TaskResult(
                    task.id(),
                    "FAILED",
                    "No result from "
                            + target.displayName()
                            + " within "
                            + TASK_TIMEOUT.toSeconds()
                            + " seconds"
            );
        }
    }
}
