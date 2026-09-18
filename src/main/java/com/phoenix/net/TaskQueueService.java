package com.phoenix.net;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.google.inject.Singleton;
import com.phoenix.model.TaskDescriptor;
import com.phoenix.model.TaskResult;

/**
 * Per-device transport queues for pull-based node agents.
 *
 * <p>This is deliberately not Phoenix's logical work queue. A logical task
 * can leave this queue when a node polls; if execution later fails, recovery
 * is handled by PendingTaskQueue/TaskDispatcher.
 */
@Singleton
public class TaskQueueService {

    private final Map<String, LinkedBlockingQueue<TaskDescriptor>>
            queuesByDeviceId = new ConcurrentHashMap<>();

    private final Map<String, CompletableFuture<TaskResult>>
            pendingResults = new ConcurrentHashMap<>();

    public void submit(
            String deviceId,
            TaskDescriptor task) {

        // Install the future before making the task visible to the agent.
        pendingResults.put(
                task.id(),
                new CompletableFuture<>()
        );

        queuesByDeviceId
                .computeIfAbsent(
                        deviceId,
                        ignored -> new LinkedBlockingQueue<>()
                )
                .offer(task);

        System.out.printf(
                "[TaskQueueService] queued %s for device %s%n",
                task.id(),
                deviceId
        );
    }

    public Optional<TaskDescriptor> poll(
            String deviceId) {

        LinkedBlockingQueue<TaskDescriptor> queue =
                queuesByDeviceId.get(deviceId);

        if (queue == null) {
            return Optional.empty();
        }

        return Optional.ofNullable(queue.poll());
    }

    /**
     * Remove a task if it is still waiting in a pull-node transport queue.
     * If the node has already pulled it, this returns false and the in-flight
     * execution is allowed to finish harmlessly.
     */
    public boolean cancelQueuedTask(
            String deviceId,
            String taskId) {

        LinkedBlockingQueue<TaskDescriptor> queue =
                queuesByDeviceId.get(deviceId);

        if (queue == null) {
            return false;
        }

        boolean removed = queue.removeIf(
                task -> task.id().equals(taskId)
        );

        if (removed) {
            pendingResults.remove(taskId);
            System.out.printf(
                    "[TaskQueueService] removed stale queued task %s from %s%n",
                    taskId,
                    deviceId
            );
        }

        return removed;
    }

    public boolean completeResult(
            TaskResult result) {

        CompletableFuture<TaskResult> future =
                pendingResults.get(result.taskId());

        if (future == null || future.isDone()) {
            System.out.printf(
                    "[TaskQueueService] dropping stale result for %s%n",
                    result.taskId()
            );
            return false;
        }

        future.complete(result);
        return true;
    }

    public TaskResult awaitResult(
            String taskId,
            Duration timeout) throws Exception {

        CompletableFuture<TaskResult> future =
                pendingResults.get(taskId);

        if (future == null) {
            throw new IllegalStateException(
                    "No pending task: " + taskId
            );
        }

        try {
            return future.get(
                    timeout.toMillis(),
                    TimeUnit.MILLISECONDS
            );
        } finally {
            pendingResults.remove(taskId, future);
        }
    }

    public int queueLength(String deviceId) {
        LinkedBlockingQueue<TaskDescriptor> queue =
                queuesByDeviceId.get(deviceId);

        return queue == null ? 0 : queue.size();
    }
}
