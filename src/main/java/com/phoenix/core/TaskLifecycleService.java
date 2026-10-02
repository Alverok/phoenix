package com.phoenix.core;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.phoenix.model.TaskLifecycleState;

/**
 * Core-owned, transport-neutral record of how far each task attempt has
 * progressed (Issue #28).
 *
 * <p>Transports (HTTP pull today, MQTT later) <em>report into</em> this
 * service as they observe lifecycle transitions; Core decides what a timeout
 * means by reading it back. Nothing here is HTTP-specific, and nothing here
 * performs scheduling or node-health policy, mirroring how
 * {@link NodeTelemetryService} is the neutral entry point for telemetry.
 *
 * <p>A transport reports only the transitions it can actually observe.
 * Skipped transitions are fine: {@code STARTED} implies {@code DELIVERED},
 * and {@code COMPLETED} implies {@code DELIVERED}.
 *
 * <p>State is keyed by task id and describes the <em>current attempt</em>:
 * calling {@link #accepted(String)} again for the same task (a recovery
 * re-placement) starts a fresh attempt. Transitions never move backwards and
 * terminal states are final, so late or duplicate reports are ignored.
 */
@Singleton
public class TaskLifecycleService {

    /** Bound the ledger the same cheap way the rest of the prototype stays bounded. */
    private static final int PRUNE_THRESHOLD = 4096;

    private final Clock clock;
    private final Map<String, Snapshot> attempts =
            new ConcurrentHashMap<>();

    @Inject
    public TaskLifecycleService() {
        this(Clock.systemUTC());
    }

    /** Test seam: deterministic time. */
    public TaskLifecycleService(Clock clock) {
        this.clock = clock;
    }

    /** The transport took responsibility for the task; starts a new attempt. */
    public void accepted(String taskId) {

        if (attempts.size() > PRUNE_THRESHOLD) {
            attempts.values().removeIf(
                    snapshot -> snapshot.state().isTerminal()
            );
        }

        attempts.put(
                taskId,
                new Snapshot(
                        taskId,
                        TaskLifecycleState.ACCEPTED,
                        clock.instant(),
                        null,
                        null,
                        null
                )
        );

        System.out.printf(
                "[Lifecycle] %s ACCEPTED%n",
                taskId
        );
    }

    /** The task reached the node. */
    public void delivered(String taskId) {
        advance(taskId, TaskLifecycleState.DELIVERED);
    }

    /** The node acknowledged that execution started (optional). */
    public void started(String taskId) {
        advance(taskId, TaskLifecycleState.STARTED);
    }

    /** The node reported a result. */
    public void completed(String taskId) {
        advance(taskId, TaskLifecycleState.COMPLETED);
    }

    /** The attempt ended without a node result. */
    public void failed(String taskId) {
        advance(taskId, TaskLifecycleState.FAILED);
    }

    public Optional<Snapshot> snapshot(String taskId) {
        return Optional.ofNullable(attempts.get(taskId));
    }

    public void forget(String taskId) {
        attempts.remove(taskId);
    }

    private void advance(
            String taskId,
            TaskLifecycleState target) {

        Instant now = clock.instant();

        // computeIfPresent is atomic per key, so concurrent reports
        // (poll thread vs. result thread) cannot interleave a transition.
        TaskLifecycleState[] before = new TaskLifecycleState[1];
        TaskLifecycleState[] after = new TaskLifecycleState[1];

        attempts.computeIfPresent(
                taskId,
                (ignored, current) -> {
                    before[0] = current.state();
                    Snapshot next = current.advancedTo(target, now);
                    after[0] = next.state();
                    return next;
                }
        );

        if (before[0] != null && before[0] != after[0]) {
            System.out.printf(
                    "[Lifecycle] %s %s -> %s%n",
                    taskId,
                    before[0],
                    after[0]
            );
        }
    }

    /**
     * Immutable view of one task attempt's lifecycle.
     *
     * <p>Timestamps are {@code null} until the corresponding transition has
     * been observed, which is how Core tells "delivered but never started"
     * apart from "never delivered".
     */
    public record Snapshot(
            String taskId,
            TaskLifecycleState state,
            Instant acceptedAt,
            Instant deliveredAt,
            Instant startedAt,
            Instant finishedAt
    ) {

        /** True once the task is known to have reached the node. */
        public boolean reachedNode() {
            return deliveredAt != null;
        }

        /** True once the node acknowledged that execution began. */
        public boolean startAcknowledged() {
            return startedAt != null;
        }

        Snapshot advancedTo(
                TaskLifecycleState target,
                Instant now) {

            // Terminal is final; transitions never move backwards.
            if (state.isTerminal()
                    || target.ordinal() <= state.ordinal()) {
                return this;
            }

            Instant delivered = deliveredAt;
            Instant started = startedAt;

            // STARTED and COMPLETED both imply the task reached the node.
            if (target != TaskLifecycleState.FAILED
                    && delivered == null) {
                delivered = now;
            }

            if (target == TaskLifecycleState.STARTED) {
                started = now;
            }

            Instant finished =
                    target.isTerminal() ? now : finishedAt;

            return new Snapshot(
                    taskId,
                    target,
                    acceptedAt,
                    delivered,
                    started,
                    finished
            );
        }
    }
}
