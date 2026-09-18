package com.phoenix.dispatch;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.inject.Singleton;
import com.phoenix.model.TaskDescriptor;

/**
 * Phoenix-level logical work queue.
 *
 * <p>This is deliberately separate from TaskQueueService, which is a
 * transport queue belonging to an individual pull-based node.
 */
@Singleton
public class PendingTaskQueue {

    private final Map<String, Pending> waiting =
            new ConcurrentHashMap<>();

    private final Duration maxWait = Duration.ofSeconds(
            Integer.getInteger("phoenix.noNodeWaitSeconds", 60)
    );

    public void enqueue(TaskDescriptor task) {
        waiting.putIfAbsent(
                task.id(),
                new Pending(task, Instant.now())
        );
    }

    public void remove(String taskId) {
        waiting.remove(taskId);
    }

    public List<Pending> snapshot() {
        return List.copyOf(waiting.values());
    }

    public boolean isExpired(Pending pending) {
        return Duration.between(
                pending.waitingSince(),
                Instant.now()
        ).compareTo(maxWait) > 0;
    }

    public Duration maxWait() {
        return maxWait;
    }

    public int size() {
        return waiting.size();
    }

    public record Pending(
            TaskDescriptor task,
            Instant waitingSince) {}
}
