package com.phoenix.net;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

import com.google.inject.Singleton;
import com.phoenix.model.TaskDescriptor;
import com.phoenix.model.TaskResult;

/**
 * In-memory task queues for pull-based Node Agents.
 *
 * <p>Each device gets its own queue. A task result is correlated back to
 * the dispatcher using taskId.
 */
@Singleton
public class TaskQueueService {

    private final Map<String, LinkedBlockingQueue<TaskDescriptor>>
            queuesByDeviceId =
            new ConcurrentHashMap<>();

    private final Map<String, CompletableFuture<TaskResult>>
            pendingResultsByTaskId =
            new ConcurrentHashMap<>();

    /**
     * Submit work to a node's queue and return a future that will be
     * completed when POST /results arrives.
     */
    public CompletableFuture<TaskResult> submit(
            String deviceId,
            TaskDescriptor task) {

        LinkedBlockingQueue<TaskDescriptor> queue =
                queuesByDeviceId.computeIfAbsent(
                        deviceId,
                        ignored ->
                                new LinkedBlockingQueue<>()
                );

        CompletableFuture<TaskResult> resultFuture =
                new CompletableFuture<>();

        pendingResultsByTaskId.put(
                task.id(),
                resultFuture
        );

        queue.offer(task);

        System.out.printf(
                "[TaskQueueService] SUBMIT key='%s' task=%s%n",
                deviceId,
                task.id()
        );

        return resultFuture;
    }

    /**
     * Poll one task for a specific device.
     */
    public Optional<TaskDescriptor> poll(
            String deviceId) {

        LinkedBlockingQueue<TaskDescriptor> queue =
                queuesByDeviceId.get(
                        deviceId
                );

        if (queue == null) {
            return Optional.empty();
        }

        TaskDescriptor task =
                queue.poll();

        return Optional.ofNullable(task);
    }

    /**
     * Complete the future belonging to a task result.
     *
     * @return true when the task was known to Phoenix.
     */
    public boolean completeResult(
            TaskResult result) {

        CompletableFuture<TaskResult> future =
                pendingResultsByTaskId.remove(
                        result.taskId()
                );

        if (future == null) {
            return false;
        }

        future.complete(result);

        return true;
    }

    /**
     * Remove a task that has not yet been pulled by its node.
     *
     * <p>If the task is still waiting in the queue, its pending future is
     * also removed because that execution attempt has timed out.
     *
     * @return true when the task was actually removed from a queue.
     */
    public boolean cancelQueuedTask(
            String deviceId,
            String taskId) {

        LinkedBlockingQueue<TaskDescriptor> queue =
                queuesByDeviceId.get(
                        deviceId
                );

        if (queue == null) {
            return false;
        }

        boolean removed =
                queue.removeIf(
                        task ->
                                task.id().equals(taskId)
                );

        if (removed) {

            pendingResultsByTaskId.remove(
                    taskId
            );

            System.out.printf(
                    "[TaskQueueService] CANCEL key='%s' task=%s%n",
                    deviceId,
                    taskId
            );
        }

        return removed;
    }

    /**
     * Number of queued tasks for one node.
     */
    public int queueLength(
            String deviceId) {

        LinkedBlockingQueue<TaskDescriptor> queue =
                queuesByDeviceId.get(
                        deviceId
                );

        return queue == null
                ? 0
                : queue.size();
    }

    /**
     * Whether Phoenix currently has a pending result waiter for a task.
     */
    public boolean hasPendingResult(
            String taskId) {

        return pendingResultsByTaskId.containsKey(
                taskId
        );
    }

    /**
     * Remove all queued work belonging to a device.
     */
    public void clearDeviceQueue(
            String deviceId) {

        LinkedBlockingQueue<TaskDescriptor> queue =
                queuesByDeviceId.get(
                        deviceId
                );

        if (queue != null) {
            queue.clear();
        }
    }
}
