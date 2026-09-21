package com.phoenix.dispatch;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.inject.Singleton;
import com.phoenix.model.TaskDescriptor;

/**
 * Phoenix-level logical work queue, kept separate from TaskQueueService
 * (a transport queue belonging to one pull-based node).
 *
 * <p>Backed by LinkedHashMap: O(1)-average lookup/removal by task ID while
 * preserving strict insertion order for FIFO recovery. Synchronized because
 * TUI submission threads and the PendingTaskScheduler tick thread both
 * touch this concurrently.
 */
@Singleton
public class PendingTaskQueue {

    private final Map<String, Pending> waiting = new LinkedHashMap<>();

    private final Duration maxWait =
            Duration.ofSeconds(Integer.getInteger("phoenix.noNodeWaitSeconds", 60));

    /** Adds to the tail. Re-enqueueing an already-pending task is a no-op —
     *  it must NOT move to the back and lose its place in line. */
    public synchronized void enqueue(TaskDescriptor task) {
        waiting.putIfAbsent(task.id(), new Pending(task, Instant.now()));
    }

    public synchronized boolean remove(String taskId) {
        return waiting.remove(taskId) != null;
    }

    /** Oldest pending task, without removing it — what the scheduler
     *  uses to enforce strict FIFO. */
    public synchronized Pending peek() {
        for (Pending pending : waiting.values()) return pending;
        return null;
    }

    public synchronized List<Pending> snapshot() {
        return List.copyOf(waiting.values());
    }

    public boolean isExpired(Pending pending) {
        return Duration.between(pending.waitingSince(), Instant.now()).compareTo(maxWait) > 0;
    }

    public Duration maxWait() { return maxWait; }

    public synchronized int size() { return waiting.size(); }

    public record Pending(TaskDescriptor task, Instant waitingSince) {}
}
