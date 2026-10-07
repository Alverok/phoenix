package com.phoenix.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.phoenix.model.TaskLifecycleState;

/**
 * Attempt identity and abandonment in the transport-neutral ledger.
 */
class TaskLifecycleAttemptTest {

    private final TaskLifecycleService lifecycle =
            new TaskLifecycleService();

    private TaskLifecycleService.Snapshot snap(String id) {
        return lifecycle.snapshot(id).orElseThrow();
    }

    @Test
    void attemptsAreNumberedPerTask() {

        assertEquals(1, lifecycle.accepted("task-001", "n1").number());
        assertEquals(1, lifecycle.accepted("task-002", "n1").number());

        lifecycle.failed("task-001");

        assertEquals(2, lifecycle.accepted("task-001", "n2").number());
        assertEquals(2, snap("task-001").attempt());
        assertEquals("n2", snap("task-001").nodeId());
    }

    @Test
    void acceptedWithoutNodeIsNeverBoundToOne() {

        TaskLifecycleService.Attempt attempt =
                lifecycle.accepted("task-001");

        assertTrue(attempt.nodeId().isEmpty());
        assertNull(snap("task-001").nodeId());
        assertTrue(lifecycle.abandonAttemptsOn("n1").isEmpty());
    }

    @Test
    void abandonOnlyTouchesNonTerminalAttemptsOnThatNode() {

        lifecycle.accepted("queued-on-n1", "n1");

        lifecycle.accepted("delivered-on-n1", "n1");
        lifecycle.delivered("delivered-on-n1");

        lifecycle.accepted("done-on-n1", "n1");
        lifecycle.completed("done-on-n1");

        lifecycle.accepted("failed-on-n1", "n1");
        lifecycle.failed("failed-on-n1");

        lifecycle.accepted("queued-on-n2", "n2");

        List<TaskLifecycleService.Snapshot> abandoned =
                lifecycle.abandonAttemptsOn("n1");

        assertEquals(
                java.util.Set.of("queued-on-n1", "delivered-on-n1"),
                abandoned.stream()
                        .map(TaskLifecycleService.Snapshot::taskId)
                        .collect(java.util.stream.Collectors.toSet())
        );

        assertEquals(TaskLifecycleState.ABANDONED, snap("queued-on-n1").state());
        assertEquals(TaskLifecycleState.ABANDONED, snap("delivered-on-n1").state());
        assertEquals(TaskLifecycleState.COMPLETED, snap("done-on-n1").state());
        assertEquals(TaskLifecycleState.FAILED, snap("failed-on-n1").state());
        assertEquals(TaskLifecycleState.ACCEPTED, snap("queued-on-n2").state());
    }

    @Test
    void abandonedAttemptDoesNotClaimDeliveryItNeverHad() {

        lifecycle.accepted("task-001", "n1");
        lifecycle.abandonAttemptsOn("n1");

        assertFalse(snap("task-001").reachedNode());
    }

    @Test
    void abandonedAttemptPreservesWhatWasObserved() {

        lifecycle.accepted("task-001", "n1");
        lifecycle.delivered("task-001");
        lifecycle.started("task-001");
        lifecycle.abandonAttemptsOn("n1");

        TaskLifecycleService.Snapshot snapshot = snap("task-001");

        assertTrue(snapshot.reachedNode());
        assertTrue(snapshot.startAcknowledged());
    }

    @Test
    void abandonedIsTerminalSoLateReportsAreIgnored() {

        lifecycle.accepted("task-001", "n1");
        lifecycle.abandonAttemptsOn("n1");

        lifecycle.delivered("task-001");
        lifecycle.completed("task-001");
        lifecycle.failed("task-001");

        assertEquals(TaskLifecycleState.ABANDONED, snap("task-001").state());
    }

    @Test
    void abandonRunsHooksOnceAndFlagsTheAttemptFirst() {

        TaskLifecycleService.Attempt attempt =
                lifecycle.accepted("task-001", "n1");

        AtomicInteger runs = new AtomicInteger();
        boolean[] flagSeenByHook = new boolean[1];

        attempt.onAbandon(() -> {
            runs.incrementAndGet();
            flagSeenByHook[0] = attempt.isAbandoned();
        });

        assertFalse(attempt.isAbandoned());

        lifecycle.abandonAttemptsOn("n1");
        lifecycle.abandonAttemptsOn("n1");

        assertEquals(1, runs.get());
        assertTrue(flagSeenByHook[0]);
        assertTrue(attempt.isAbandoned());
    }

    @Test
    void hookRegisteredAfterAbandonmentRunsImmediately() {

        TaskLifecycleService.Attempt attempt =
                lifecycle.accepted("task-001", "n1");

        lifecycle.abandonAttemptsOn("n1");

        AtomicInteger runs = new AtomicInteger();
        attempt.onAbandon(runs::incrementAndGet);

        assertEquals(1, runs.get());
    }

    @Test
    void aFailingHookDoesNotPreventOtherHooks() {

        TaskLifecycleService.Attempt attempt =
                lifecycle.accepted("task-001", "n1");

        AtomicInteger runs = new AtomicInteger();

        attempt.onAbandon(() -> {
            throw new IllegalStateException("boom");
        });
        attempt.onAbandon(runs::incrementAndGet);

        lifecycle.abandonAttemptsOn("n1");

        assertEquals(1, runs.get());
    }

    @Test
    void reportsFromAReplacedAttemptCannotCorruptTheNewOne() {

        TaskLifecycleService.Attempt first =
                lifecycle.accepted("task-001", "n1");
        lifecycle.failed(first);

        TaskLifecycleService.Attempt second =
                lifecycle.accepted("task-001", "n2");

        // A stale reporter still holding attempt 1:
        lifecycle.delivered(first);
        lifecycle.completed(first);
        lifecycle.failed(first);

        assertEquals(TaskLifecycleState.ACCEPTED, snap("task-001").state());
        assertEquals(2, snap("task-001").attempt());

        // The current attempt's reports still work.
        lifecycle.delivered(second);
        assertEquals(TaskLifecycleState.DELIVERED, snap("task-001").state());
    }

    @Test
    void acceptingAgainReleasesAnAttemptThatNeverFinished() {

        TaskLifecycleService.Attempt first =
                lifecycle.accepted("task-001", "n1");

        AtomicInteger runs = new AtomicInteger();
        first.onAbandon(runs::incrementAndGet);

        lifecycle.accepted("task-001", "n2");

        assertTrue(first.isAbandoned());
        assertEquals(1, runs.get());
    }

    @Test
    void currentAttemptReturnsTheLatestHandle() {

        lifecycle.accepted("task-001", "n1");
        TaskLifecycleService.Attempt second =
                lifecycle.accepted("task-001", "n2");

        assertSame(second, lifecycle.currentAttempt("task-001").orElseThrow());
        assertTrue(lifecycle.currentAttempt("ghost").isEmpty());
    }
}
