package com.phoenix.net;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.phoenix.core.TaskLifecycleService;
import com.phoenix.core.TaskTimeoutPolicy;
import com.phoenix.model.NodeExecutionTarget;
import com.phoenix.model.TaskDescriptor;
import com.phoenix.model.TaskFailureReason;
import com.phoenix.model.TaskResult;

/**
 * Pull-based transport used by mobile/Android-style node agents.
 *
 * <p>Issue #28: execution is waited for in two phases instead of one
 * end-to-end timer, so Core can tell the difference between "the task never
 * left the queue" and "the node took it but never answered":
 *
 * <pre>
 * submit -> awaitDelivery(deliveryTimeout)
 *             |-- not delivered, node still polling  -> DELIVERY_TIMEOUT
 *             |-- not delivered, node silent         -> NODE_UNREACHABLE
 *             '-- delivered -> awaitResult(executionTimeout)
 *                               '-- no result        -> EXECUTION_TIMEOUT
 * </pre>
 *
 * <p>The timeout values and the backlog-vs-unreachable rule come from
 * {@link TaskTimeoutPolicy}; this class only applies them to what the HTTP
 * pull queue can observe. The lifecycle contract itself is documented on
 * {@link NodeAgentClient}.
 */
@Singleton
public class HttpNodeAgentClient
        implements NodeAgentClient {

    private final RegistrationServer registrationServer;
    private final TaskQueueService taskQueue;
    private final TaskLifecycleService lifecycle;
    private final TaskTimeoutPolicy timeouts;

    @Inject
    public HttpNodeAgentClient(
            RegistrationServer registrationServer,
            TaskQueueService taskQueue,
            TaskLifecycleService lifecycle,
            TaskTimeoutPolicy timeouts) {
        this.registrationServer = registrationServer;
        this.taskQueue = taskQueue;
        this.lifecycle = lifecycle;
        this.timeouts = timeouts;
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
                "[HttpNodeAgentClient] Queued %s for %s; waiting for delivery...%n",
                task.id(),
                target.displayName()
        );

        try {

            /*
             * Phase 1 - delivery. This bounds queue backlog only.
             */
            boolean delivered =
                    taskQueue.awaitDelivery(
                            task.id(),
                            timeouts.deliveryTimeout()
                    );

            if (!delivered
                    && taskQueue.cancelQueuedTask(
                            deviceId.get(),
                            task.id())) {

                /*
                 * Removed from the queue, so the node provably never
                 * received this attempt. Whether the node is merely busy
                 * or actually gone is decided from its recent contact.
                 */
                return undelivered(
                        target,
                        task,
                        deviceId.get()
                );
            }

            /*
             * Phase 2 - execution. Reached when the task was delivered, or
             * when the node took it in the instant between the delivery
             * timeout and the cancel attempt (cancel returned false).
             */
            return taskQueue.awaitResult(
                    task.id(),
                    timeouts.executionTimeout()
            );

        } catch (TimeoutException exception) {

            lifecycle.failed(task.id());

            return new TaskResult(
                    task.id(),
                    "FAILED",
                    "No result from "
                            + target.displayName()
                            + " within "
                            + timeouts.executionTimeout().toSeconds()
                            + "s of delivery ("
                            + startDescription(task.id())
                            + ")",
                    TaskFailureReason.EXECUTION_TIMEOUT
            );

        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            taskQueue.cancelQueuedTask(
                    deviceId.get(),
                    task.id()
            );
            lifecycle.failed(task.id());

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
            lifecycle.failed(task.id());

            return new TaskResult(
                    task.id(),
                    "FAILED",
                    "Transport to "
                            + target.displayName()
                            + " failed: "
                            + exception.getMessage(),
                    TaskFailureReason.TRANSPORT_ERROR
            );

        } finally {
            // No-op when awaitResult/cancel already cleaned up.
            taskQueue.discard(task.id());
        }
    }

    private TaskResult undelivered(
            NodeExecutionTarget target,
            TaskDescriptor task,
            String deviceId) {

        Optional<Duration> sinceContact =
                taskQueue.timeSinceLastPoll(deviceId);

        TaskFailureReason reason =
                timeouts.classifyUndelivered(sinceContact);

        lifecycle.failed(task.id());

        String message =
                reason == TaskFailureReason.DELIVERY_TIMEOUT
                        ? "Task "
                                + task.id()
                                + " was not delivered to "
                                + target.displayName()
                                + " within "
                                + timeouts.deliveryTimeout().toSeconds()
                                + "s; node is still polling (queue backlog)"
                        : "Task "
                                + task.id()
                                + " was never delivered: "
                                + target.displayName()
                                + (sinceContact.isPresent()
                                        ? " last polled "
                                                + sinceContact.get().toSeconds()
                                                + "s ago"
                                        : " has never polled");

        return new TaskResult(
                task.id(),
                "FAILED",
                message,
                reason
        );
    }

    private String startDescription(String taskId) {
        return lifecycle.snapshot(taskId)
                .map(snapshot -> snapshot.startAcknowledged()
                        ? "start acknowledged"
                        : "start not acknowledged")
                .orElse("start unknown");
    }
}
