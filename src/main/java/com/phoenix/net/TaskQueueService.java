package com.phoenix.net;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.phoenix.core.TaskLifecycleService;
import com.phoenix.model.TaskDescriptor;
import com.phoenix.model.TaskResult;

/**
 * Per-device transport queues for pull-based node agents.
 *
 * <p>This is deliberately not Phoenix's logical work queue. A logical task
 * can leave this queue when a node polls; if execution later fails, recovery
 * is handled by PendingTaskQueue/TaskDispatcher.
 *
 * <p>Issue #28: this class is where the HTTP pull transport can actually
 * <em>observe</em> task lifecycle, so it reports into the transport-neutral
 * {@link TaskLifecycleService}:
 *
 * <pre>
 * submit()          -> ACCEPTED   (queued; has not reached the node)
 * poll() hands task -> DELIVERED  (the node took it)
 * markStarted()     -> STARTED    (optional node acknowledgement)
 * completeResult()  -> COMPLETED
 * </pre>
 *
 * <p>It also remembers when each device last polled, which is the HTTP
 * transport's evidence that a node is still in contact even while a task is
 * waiting to be delivered.
 */
@Singleton
public class TaskQueueService {

    private final Map<String, LinkedBlockingQueue<TaskDescriptor>>
            queuesByDeviceId = new ConcurrentHashMap<>();

    private final Map<String, PendingTask>
            pendingTasks = new ConcurrentHashMap<>();

    private final Map<String, Instant>
            lastPollByDeviceId = new ConcurrentHashMap<>();

    private final TaskLifecycleService lifecycle;
    private final Clock clock;

    @Inject
    public TaskQueueService(TaskLifecycleService lifecycle) {
        this(lifecycle, Clock.systemUTC());
    }

    /** Test seam: deterministic time. */
    public TaskQueueService(
            TaskLifecycleService lifecycle,
            Clock clock) {

        this.lifecycle = lifecycle;
        this.clock = clock;
    }

    public void submit(
            String deviceId,
            TaskDescriptor task) {

        // Install the pending state before making the task visible to the agent.
        pendingTasks.put(
                task.id(),
                new PendingTask()
        );

        lifecycle.accepted(task.id());

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

    /**
     * Records that a device is in contact. Called for every poll, including
     * polls that receive no work (and polls from a node the Core currently
     * refuses to hand work to): a polling node is a reachable node.
     */
    public void recordPoll(String deviceId) {
        lastPollByDeviceId.put(deviceId, clock.instant());
    }

    /** Time since this device last polled; empty if it never has. */
    public Optional<Duration> timeSinceLastPoll(String deviceId) {
        Instant last = lastPollByDeviceId.get(deviceId);

        return last == null
                ? Optional.empty()
                : Optional.of(Duration.between(last, clock.instant()));
    }

    public Optional<TaskDescriptor> poll(
            String deviceId) {

        recordPoll(deviceId);

        LinkedBlockingQueue<TaskDescriptor> queue =
                queuesByDeviceId.get(deviceId);

        if (queue == null) {
            return Optional.empty();
        }

        TaskDescriptor task = queue.poll();

        if (task == null) {
            return Optional.empty();
        }

        PendingTask pending = pendingTasks.get(task.id());

        if (pending != null) {
            pending.delivered.complete(null);
            lifecycle.delivered(task.id());
        }

        return Optional.of(task);
    }

    /**
     * Optional node acknowledgement that execution began. Only meaningful for
     * a task that has already been delivered in its current attempt.
     */
    public boolean markStarted(String taskId) {

        PendingTask pending = pendingTasks.get(taskId);

        if (pending == null
                || !pending.delivered.isDone()
                || pending.result.isDone()) {

            return false;
        }

        lifecycle.started(taskId);
        return true;
    }

    /**
     * Remove a task if it is still waiting in a pull-node transport queue.
     * If the node has already pulled it, this returns false and the in-flight
     * execution is allowed to finish harmlessly.
     *
     * <p>Removal is atomic with respect to {@link #poll(String)}: exactly one
     * of them obtains the task, so a {@code true} result guarantees the node
     * never received it.
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
            pendingTasks.remove(taskId);
            System.out.printf(
                    "[TaskQueueService] removed stale queued task %s from %s%n",
                    taskId,
                    deviceId
            );
        }

        return removed;
    }

    /**
     * Accepts a node-reported result for the current attempt of a task.
     *
     * <p>A result is rejected as stale if there is no pending attempt, the
     * attempt already has a result, or the current attempt has not been
     * delivered to any node yet. The last case matters after recovery: a late
     * result from a previous attempt on another node must not complete a
     * re-queued attempt that no node has even seen.
     */
    public boolean completeResult(
            TaskResult result) {

        PendingTask pending =
                pendingTasks.get(result.taskId());

        if (pending == null || pending.result.isDone()) {
            System.out.printf(
                    "[TaskQueueService] dropping stale result for %s%n",
                    result.taskId()
            );
            return false;
        }

        if (!pending.delivered.isDone()) {
            System.out.printf(
                    "[TaskQueueService] dropping result for %s: "
                            + "current attempt was never delivered%n",
                    result.taskId()
            );
            return false;
        }

        if (!pending.result.complete(result)) {
            return false;
        }

        lifecycle.completed(result.taskId());
        return true;
    }

    /**
     * Phase 1: wait for the node to take the task.
     *
     * @return true if the task was delivered within the timeout, false if it
     *         is still waiting in the queue (caller decides what that means)
     */
    public boolean awaitDelivery(
            String taskId,
            Duration timeout) throws InterruptedException {

        PendingTask pending = requirePending(taskId);

        try {
            pending.delivered.get(
                    timeout.toMillis(),
                    TimeUnit.MILLISECONDS
            );
            return true;

        } catch (TimeoutException exception) {
            return false;

        } catch (java.util.concurrent.ExecutionException exception) {
            // The delivery signal is only ever completed normally.
            throw new IllegalStateException(exception);
        }
    }

    /**
     * Phase 2: wait for the node's result. Call after the task has been
     * delivered; the timeout is therefore an execution timeout.
     */
    public TaskResult awaitResult(
            String taskId,
            Duration timeout) throws Exception {

        PendingTask pending = requirePending(taskId);

        try {
            return pending.result.get(
                    timeout.toMillis(),
                    TimeUnit.MILLISECONDS
            );
        } finally {
            pendingTasks.remove(taskId, pending);
        }
    }

    /** Drops any pending state for the task; safe if already gone. */
    public void discard(String taskId) {
        pendingTasks.remove(taskId);
    }

    public int queueLength(String deviceId) {
        LinkedBlockingQueue<TaskDescriptor> queue =
                queuesByDeviceId.get(deviceId);

        return queue == null ? 0 : queue.size();
    }

    private PendingTask requirePending(String taskId) {

        PendingTask pending = pendingTasks.get(taskId);

        if (pending == null) {
            throw new IllegalStateException(
                    "No pending task: " + taskId
            );
        }

        return pending;
    }

    /** State of one task attempt inside the pull transport. */
    private static final class PendingTask {

        /** Completed when a node takes the task off the queue. */
        final CompletableFuture<Void> delivered =
                new CompletableFuture<>();

        /** Completed when the node reports a result. */
        final CompletableFuture<TaskResult> result =
                new CompletableFuture<>();
    }
}
