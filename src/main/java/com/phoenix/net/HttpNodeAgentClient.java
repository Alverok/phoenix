package com.phoenix.net;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.phoenix.model.NodeExecutionTarget;
import com.phoenix.model.TaskDescriptor;
import com.phoenix.model.TaskFailureReason;
import com.phoenix.model.TaskResult;

/**
 * Pull-based Node Agent transport.
 *
 * <p>Used by Android-style nodes which retrieve their work by polling
 * Phoenix rather than accepting inbound connections.
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

    private final TaskQueueService taskQueue;
    private final RegistrationServer registrationServer;

    @Inject
    public HttpNodeAgentClient(
            TaskQueueService taskQueue,
            RegistrationServer registrationServer) {

        this.taskQueue = taskQueue;
        this.registrationServer = registrationServer;
    }

    @Override
    public TaskResult execute(
            NodeExecutionTarget target,
            TaskDescriptor task) {

        var deviceIdOptional =
                registrationServer.deviceIdForNode(
                        target.nodeId()
                );

        if (deviceIdOptional.isEmpty()) {

            return new TaskResult(
                    task.id(),
                    "FAILED",
                    "No device identity is known for "
                            + target.displayName(),
                    TaskFailureReason.NODE_UNREACHABLE
            );
        }

        String deviceId =
                deviceIdOptional.get();

        CompletableFuture<TaskResult> resultFuture =
                taskQueue.submit(
                        deviceId,
                        task
                );

        System.out.printf(
                "[HttpNodeAgentClient] Queued %s for %s; waiting for agent...%n",
                task.id(),
                target.displayName()
        );

        try {

            return resultFuture.get(
                    TASK_TIMEOUT.toMillis(),
                    TimeUnit.MILLISECONDS
            );

        } catch (TimeoutException e) {

            /*
             * If the Android node never pulled the task, remove it so
             * recovery does not leave stale work in the old node's queue.
             *
             * If it was already pulled, cancellation returns false and the
             * already-running execution is allowed to finish harmlessly.
             */
            taskQueue.cancelQueuedTask(
                    deviceId,
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

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();

            taskQueue.cancelQueuedTask(
                    deviceId,
                    task.id()
            );

            return new TaskResult(
                    task.id(),
                    "FAILED",
                    "Interrupted while waiting for "
                            + target.displayName(),
                    TaskFailureReason.TRANSPORT_ERROR
            );

        } catch (Exception e) {

            taskQueue.cancelQueuedTask(
                    deviceId,
                    task.id()
            );

            return new TaskResult(
                    task.id(),
                    "FAILED",
                    "Transport to "
                            + target.displayName()
                            + " failed: "
                            + e.getMessage(),
                    classifyTransportFailure(e)
            );
        }
    }

    private static TaskFailureReason classifyTransportFailure(
            Exception exception) {

        Throwable current =
                exception;

        while (current != null) {

            if (current instanceof SocketTimeoutException) {
                return TaskFailureReason.TIMEOUT;
            }

            current =
                    current.getCause();
        }

        return TaskFailureReason.TRANSPORT_ERROR;
    }
}
