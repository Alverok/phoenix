package com.phoenix.net;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.phoenix.model.NodeExecutionTarget;
import com.phoenix.model.TaskDescriptor;
import com.phoenix.model.TaskFailureReason;
import com.phoenix.model.TaskResult;

/**
 * Pull-based transport used by mobile/Android-style node agents.
 */
@Singleton
public class HttpNodeAgentClient
        implements NodeAgentClient {

    private static final Duration TASK_TIMEOUT =
            Duration.ofSeconds(
                    Long.getLong(
                            "phoenix.taskTimeoutSeconds",
                            12L
                    )
            );

    private final RegistrationServer registrationServer;
    private final TaskQueueService taskQueue;

    @Inject
    public HttpNodeAgentClient(
            RegistrationServer registrationServer,
            TaskQueueService taskQueue) {
        this.registrationServer = registrationServer;
        this.taskQueue = taskQueue;
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
                    "Node " + target.displayName()
                            + " is not currently connected",
                    TaskFailureReason.NODE_UNREACHABLE
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
        } catch (TimeoutException exception) {
            taskQueue.cancelQueuedTask(
                    deviceId.get(),
                    task.id()
            );

            return new TaskResult(
                    task.id(),
                    "FAILED",
                    "No result from "
                            + target.displayName()
                            + " within "
                            + TASK_TIMEOUT.toSeconds()
                            + " seconds",
                    TaskFailureReason.TIMEOUT
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            taskQueue.cancelQueuedTask(
                    deviceId.get(),
                    task.id()
            );

            return new TaskResult(
                    task.id(),
                    "FAILED",
                    "Interrupted while waiting for "
                            + target.displayName(),
                    TaskFailureReason.TRANSPORT_ERROR
            );
        } catch (Exception exception) {
            taskQueue.cancelQueuedTask(
                    deviceId.get(),
                    task.id()
            );

            return new TaskResult(
                    task.id(),
                    "FAILED",
                    "Transport to "
                            + target.displayName()
                            + " failed: "
                            + exception.getMessage(),
                    TaskFailureReason.TRANSPORT_ERROR
            );
        }
    }
}
