package com.phoenix.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.phoenix.model.TaskLifecycleState;
import com.phoenix.testsupport.MutableClock;

class TaskLifecycleServiceTest {

    private final MutableClock clock = new MutableClock();
    private final TaskLifecycleService lifecycle =
            new TaskLifecycleService(clock);

    private TaskLifecycleService.Snapshot snap(String id) {
        return lifecycle.snapshot(id).orElseThrow();
    }

    @Test
    void unknownTaskHasNoSnapshot() {
        assertTrue(lifecycle.snapshot("nope").isEmpty());
    }

    @Test
    void acceptedTaskHasNotReachedTheNode() {

        lifecycle.accepted("task-001");

        TaskLifecycleService.Snapshot snapshot = snap("task-001");

        assertEquals(TaskLifecycleState.ACCEPTED, snapshot.state());
        assertFalse(snapshot.reachedNode());
        assertFalse(snapshot.startAcknowledged());
        assertNotNull(snapshot.acceptedAt());
    }

    @Test
    void walksTheFullLifecycleWithTimestamps() {

        lifecycle.accepted("task-001");

        clock.advance(Duration.ofSeconds(3));
        lifecycle.delivered("task-001");

        clock.advance(Duration.ofSeconds(1));
        lifecycle.started("task-001");

        clock.advance(Duration.ofSeconds(2));
        lifecycle.completed("task-001");

        TaskLifecycleService.Snapshot snapshot = snap("task-001");

        assertEquals(TaskLifecycleState.COMPLETED, snapshot.state());
        assertTrue(snapshot.reachedNode());
        assertTrue(snapshot.startAcknowledged());
        assertEquals(
                Duration.ofSeconds(3),
                Duration.between(
                        snapshot.acceptedAt(),
                        snapshot.deliveredAt())
        );
        assertEquals(
                Duration.ofSeconds(6),
                Duration.between(
                        snapshot.acceptedAt(),
                        snapshot.finishedAt())
        );
    }

    @Test
    void startedImpliesDelivered() {

        lifecycle.accepted("task-001");
        lifecycle.started("task-001");

        TaskLifecycleService.Snapshot snapshot = snap("task-001");

        assertEquals(TaskLifecycleState.STARTED, snapshot.state());
        assertTrue(snapshot.reachedNode());
    }

    @Test
    void completedWithoutAnyAckStillImpliesDelivered() {

        // Push transport: no separate delivered/started signal may arrive.
        lifecycle.accepted("task-001");
        lifecycle.completed("task-001");

        assertTrue(snap("task-001").reachedNode());
        assertFalse(snap("task-001").startAcknowledged());
    }

    @Test
    void failedBeforeDeliveryDoesNotClaimDelivery() {

        lifecycle.accepted("task-001");
        lifecycle.failed("task-001");

        TaskLifecycleService.Snapshot snapshot = snap("task-001");

        assertEquals(TaskLifecycleState.FAILED, snapshot.state());
        assertFalse(snapshot.reachedNode());
        assertNull(snapshot.deliveredAt());
    }

    @Test
    void transitionsNeverMoveBackwards() {

        lifecycle.accepted("task-001");
        lifecycle.started("task-001");
        lifecycle.delivered("task-001");   // late duplicate
        lifecycle.accepted("task-002");    // unrelated task

        assertEquals(
                TaskLifecycleState.STARTED,
                snap("task-001").state()
        );
    }

    @Test
    void terminalStateIsFinal() {

        lifecycle.accepted("task-001");
        lifecycle.failed("task-001");
        lifecycle.completed("task-001");   // late result must not resurrect it

        assertEquals(
                TaskLifecycleState.FAILED,
                snap("task-001").state()
        );
    }

    @Test
    void reportsForUnknownTasksAreIgnored() {

        lifecycle.delivered("ghost");
        lifecycle.completed("ghost");

        assertTrue(lifecycle.snapshot("ghost").isEmpty());
    }

    @Test
    void acceptingAgainStartsAFreshAttempt() {

        lifecycle.accepted("task-001");
        lifecycle.delivered("task-001");
        lifecycle.failed("task-001");

        // Recovery re-places the same task.
        lifecycle.accepted("task-001");

        TaskLifecycleService.Snapshot snapshot = snap("task-001");

        assertEquals(TaskLifecycleState.ACCEPTED, snapshot.state());
        assertFalse(snapshot.reachedNode());
    }

    @Test
    void forgetRemovesTheTask() {

        lifecycle.accepted("task-001");
        lifecycle.forget("task-001");

        assertTrue(lifecycle.snapshot("task-001").isEmpty());
    }
}
