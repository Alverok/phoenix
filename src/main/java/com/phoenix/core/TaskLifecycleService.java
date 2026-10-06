package com.phoenix.core;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.phoenix.model.TaskLifecycleState;

/**
 * Core-owned, transport-neutral record of how far each task attempt has
 * progressed.
 *
 * <p>Transports (HTTP pull today, MQTT later) <em>report into</em> this
 * service as they observe lifecycle transitions; Core decides what a timeout
 * means by reading it back. Nothing here is HTTP-specific, and nothing here
 * performs scheduling or node-health policy, mirroring how
 * {@link NodeTelemetryService} is the neutral entry point for telemetry.
 *
 * <h2>Attempts</h2>
 *
 * <p>Every call to {@link #accepted(String, String)} starts a new
 * <em>attempt</em> of the task, numbered 1, 2, 3 ... per task id, and returns
 * an {@link Attempt} handle. The handle is the attempt's identity (a fencing
 * token): reports made through it are ignored once a newer attempt has
 * replaced it, so a stale reporter can never corrupt the ledger of a retry.
 *
 * <h2>Reconciliation with node liveness</h2>
 *
 * <p>An attempt is bound to the node it was placed on. When that node is
 * declared OFFLINE, {@link #abandonAttemptsOn(String)} moves every
 * non-terminal attempt on it to {@link TaskLifecycleState#ABANDONED} and runs
 * the cancellation hooks the owning transport registered with
 * {@link Attempt#onAbandon(Runnable)}, which wake the thread blocked in that
 * transport so recovery can begin immediately instead of at the next timeout.
 *
 * <p>Transitions never move backwards and terminal states are final, so late
 * or duplicate reports are ignored.
 */
@Singleton
public class TaskLifecycleService {

    /**
     * Bounds the ledger the same cheap way the rest of the prototype stays
     * bounded. Pruned tasks restart their attempt numbering, which is safe
     * because it takes thousands of intervening tasks to reach it.
     */
    private static final int PRUNE_THRESHOLD = 4096;

    private final Clock clock;
    private final Map<String, Entry> attempts =
            new ConcurrentHashMap<>();

    @Inject
    public TaskLifecycleService() {
        this(Clock.systemUTC());
    }

    /** Test seam: deterministic time. */
    public TaskLifecycleService(Clock clock) {
        this.clock = clock;
    }

    /** Starts a new attempt that is not bound to any node. */
    public Attempt accepted(String taskId) {
        return accepted(taskId, null);
    }

    /**
     * The transport took responsibility for the task; starts a new attempt
     * bound to {@code nodeId}.
     *
     * @param nodeId the node the attempt was placed on, or {@code null} if
     *        unknown (such an attempt is never reconciled by node liveness)
     */
    public Attempt accepted(
            String taskId,
            String nodeId) {

        if (attempts.size() > PRUNE_THRESHOLD) {
            attempts.values().removeIf(
                    entry -> entry.snapshot().state().isTerminal()
            );
        }

        Instant now = clock.instant();
        Attempt[] superseded = new Attempt[1];

        Entry created = attempts.compute(
                taskId,
                (ignored, old) -> {

                    int number =
                            old == null
                                    ? 1
                                    : old.attempt().number() + 1;

                    if (old != null
                            && !old.snapshot().state().isTerminal()) {
                        superseded[0] = old.attempt();
                    }

                    return new Entry(
                            new Snapshot(
                                    taskId,
                                    TaskLifecycleState.ACCEPTED,
                                    now,
                                    null,
                                    null,
                                    null,
                                    number,
                                    nodeId
                            ),
                            new Attempt(taskId, number, nodeId)
                    );
                }
        );

        // A new attempt replaced one that never reached a terminal state:
        // release whatever that older attempt still holds.
        if (superseded[0] != null) {
            superseded[0].abandon();
        }

        System.out.printf(
                created.attempt().number() == 1
                        ? "[Lifecycle] %s ACCEPTED%n"
                        : "[Lifecycle] %s ACCEPTED (attempt %d)%n",
                taskId,
                created.attempt().number()
        );

        return created.attempt();
    }

    /** The task reached the node (current attempt). */
    public void delivered(String taskId) {
        advance(taskId, null, TaskLifecycleState.DELIVERED);
    }

    /** The task reached the node; ignored if {@code attempt} is stale. */
    public void delivered(Attempt attempt) {
        advance(attempt.taskId(), attempt, TaskLifecycleState.DELIVERED);
    }

    /** The node acknowledged that execution started (optional). */
    public void started(String taskId) {
        advance(taskId, null, TaskLifecycleState.STARTED);
    }

    public void started(Attempt attempt) {
        advance(attempt.taskId(), attempt, TaskLifecycleState.STARTED);
    }

    /** The node reported a result. */
    public void completed(String taskId) {
        advance(taskId, null, TaskLifecycleState.COMPLETED);
    }

    public void completed(Attempt attempt) {
        advance(attempt.taskId(), attempt, TaskLifecycleState.COMPLETED);
    }

    /** The attempt ended without a node result. */
    public void failed(String taskId) {
        advance(taskId, null, TaskLifecycleState.FAILED);
    }

    public void failed(Attempt attempt) {
        advance(attempt.taskId(), attempt, TaskLifecycleState.FAILED);
    }

    /**
     * Abandons every non-terminal attempt that is bound to {@code nodeId}.
     *
     * <p>Called when the node is declared OFFLINE. Each affected attempt moves
     * to {@link TaskLifecycleState#ABANDONED}, then its cancellation hooks
     * run (outside any lock) so the blocked transport thread wakes up.
     *
     * @return the abandoned attempts as they were at the moment of
     *         abandonment, in no particular order
     */
    public List<Snapshot> abandonAttemptsOn(String nodeId) {

        Instant now = clock.instant();

        List<Snapshot> abandoned = new ArrayList<>();
        List<Attempt> toRelease = new ArrayList<>();

        for (String taskId : List.copyOf(attempts.keySet())) {

            attempts.computeIfPresent(
                    taskId,
                    (ignored, entry) -> {

                        if (entry.snapshot().state().isTerminal()
                                || !nodeId.equals(
                                        entry.attempt().nodeId()
                                                .orElse(null))) {
                            return entry;
                        }

                        Snapshot next =
                                entry.snapshot().advancedTo(
                                        TaskLifecycleState.ABANDONED,
                                        now
                                );

                        abandoned.add(next);
                        toRelease.add(entry.attempt());

                        return new Entry(next, entry.attempt());
                    }
            );
        }

        // The state change is visible before any hook runs, so a woken
        // transport can already observe Attempt#isAbandoned().
        toRelease.forEach(Attempt::abandon);

        return abandoned;
    }

    public Optional<Snapshot> snapshot(String taskId) {
        Entry entry = attempts.get(taskId);

        return entry == null
                ? Optional.empty()
                : Optional.of(entry.snapshot());
    }

    /** The current attempt handle for a task, if one exists. */
    public Optional<Attempt> currentAttempt(String taskId) {
        Entry entry = attempts.get(taskId);

        return entry == null
                ? Optional.empty()
                : Optional.of(entry.attempt());
    }

    public void forget(String taskId) {
        attempts.remove(taskId);
    }

    private void advance(
            String taskId,
            Attempt expected,
            TaskLifecycleState target) {

        Instant now = clock.instant();

        // computeIfPresent is atomic per key, so concurrent reports
        // (poll thread vs. result thread) cannot interleave a transition.
        TaskLifecycleState[] before = new TaskLifecycleState[1];
        TaskLifecycleState[] after = new TaskLifecycleState[1];
        int[] number = new int[1];

        attempts.computeIfPresent(
                taskId,
                (ignored, current) -> {

                    // A report from an attempt that has since been replaced.
                    if (expected != null
                            && current.attempt() != expected) {
                        return current;
                    }

                    before[0] = current.snapshot().state();
                    Snapshot next =
                            current.snapshot().advancedTo(target, now);
                    after[0] = next.state();
                    number[0] = next.attempt();

                    return next == current.snapshot()
                            ? current
                            : new Entry(next, current.attempt());
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

    private record Entry(
            Snapshot snapshot,
            Attempt attempt
    ) {}

    /**
     * Identity of one execution attempt, handed to the transport that runs it.
     *
     * <p>Besides acting as a fencing token for ledger reports, it is the
     * channel through which Core cancels a blocked transport: the transport
     * registers a hook with {@link #onAbandon(Runnable)} that releases
     * whatever it is waiting on.
     */
    public static final class Attempt {

        private final String taskId;
        private final int number;
        private final String nodeId;

        private final List<Runnable> hooks = new ArrayList<>();
        private volatile boolean abandoned;

        private Attempt(
                String taskId,
                int number,
                String nodeId) {

            this.taskId = taskId;
            this.number = number;
            this.nodeId = nodeId;
        }

        public String taskId() {
            return taskId;
        }

        /** 1 for the first attempt of a task, 2 for the first retry, ... */
        public int number() {
            return number;
        }

        /** The node this attempt was placed on, if known. */
        public Optional<String> nodeId() {
            return Optional.ofNullable(nodeId);
        }

        /** True once Core stopped waiting for this attempt. */
        public boolean isAbandoned() {
            return abandoned;
        }

        /**
         * Registers a cancellation hook. It runs at most once, on whichever
         * thread abandons the attempt; if the attempt is already abandoned it
         * runs immediately on the caller's thread. Hooks must be quick and
         * must not throw.
         */
        public void onAbandon(Runnable hook) {

            boolean runNow;

            synchronized (this) {
                runNow = abandoned;

                if (!runNow) {
                    hooks.add(hook);
                }
            }

            if (runNow) {
                run(hook);
            }
        }

        private void abandon() {

            List<Runnable> toRun;

            synchronized (this) {
                if (abandoned) {
                    return;
                }

                abandoned = true;
                toRun = List.copyOf(hooks);
                hooks.clear();
            }

            toRun.forEach(Attempt::run);
        }

        private static void run(Runnable hook) {
            try {
                hook.run();
            } catch (RuntimeException exception) {
                System.err.println(
                        "[Lifecycle] abandon hook failed: " + exception
                );
            }
        }
    }

    /**
     * Immutable view of one task attempt's lifecycle.
     *
     * <p>Timestamps are {@code null} until the corresponding transition has
     * been observed, which is how Core tells "delivered but never started"
     * apart from "never delivered".
     *
     * @param attempt attempt number (1 = first attempt)
     * @param nodeId  node the attempt was placed on; {@code null} if unknown
     */
    public record Snapshot(
            String taskId,
            TaskLifecycleState state,
            Instant acceptedAt,
            Instant deliveredAt,
            Instant startedAt,
            Instant finishedAt,
            int attempt,
            String nodeId
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

            // DELIVERED, STARTED and COMPLETED all mean the task reached the
            // node. FAILED and ABANDONED imply nothing: that is exactly the
            // information they are meant to preserve.
            if (target != TaskLifecycleState.FAILED
                    && target != TaskLifecycleState.ABANDONED
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
                    finished,
                    attempt,
                    nodeId
            );
        }
    }
}
