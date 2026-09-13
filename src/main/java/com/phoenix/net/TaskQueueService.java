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

@Singleton
public class TaskQueueService {

    private final Map<String, LinkedBlockingQueue<TaskDescriptor>> queuesByDeviceId =
            new ConcurrentHashMap<>();

    private final Map<String, CompletableFuture<TaskResult>> pendingResults =
            new ConcurrentHashMap<>();

    public void submit(String deviceId, TaskDescriptor task) {

        // Create the future BEFORE making the task visible to the agent.
        // This removes a small race where the agent could poll immediately
        // after enqueue but before the future was installed.

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
    }

    public Optional<TaskDescriptor> poll(String deviceId) {

        LinkedBlockingQueue<TaskDescriptor> queue =
                queuesByDeviceId.get(deviceId);

        if (queue == null) {
            return Optional.empty();
        }

        return Optional.ofNullable(
                queue.poll()
        );
    }

    public boolean completeResult(TaskResult result) {

        /*
         * Do NOT remove the future here.
         *
         * awaitResult() may not have called future.get() yet.
         * Keeping the completed future in the map allows awaitResult()
         * to retrieve it even if the result arrives very quickly.
         */
        CompletableFuture<TaskResult> future =
                pendingResults.get(result.taskId());

        if (future == null) {
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

            // The future is removed only after the result has been consumed
            // or the wait has timed out/interrupted.
            pendingResults.remove(taskId);
        }
    }
}
