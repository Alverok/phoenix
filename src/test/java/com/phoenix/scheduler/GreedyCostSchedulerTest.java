package com.phoenix.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.phoenix.model.HardwareClass;
import com.phoenix.model.NodeInfo;
import com.phoenix.model.NodeRecord;
import com.phoenix.model.ResourceStatus;
import com.phoenix.model.TaskDescriptor;

/**
 * This test IS the Day-2 "Brain" milestone: it proves the scheduler filters
 * out ineligible nodes and picks the lowest-cost eligible node, using the
 * weighted-cost formula from the design document.
 */
class GreedyCostSchedulerTest {

    private final GreedyCostScheduler scheduler =
            new GreedyCostScheduler(new WeightedPlacementCostModel());

    private NodeRecord node(String id, String name, int cores, long memMb, ResourceStatus status) {
        NodeInfo info = new NodeInfo(id, name, HardwareClass.LINUX, "x86_64", cores, memMb, "linux", Set.of());
        return NodeRecord.newlyRegistered(info, status);
    }

    @Test
    void picksTheLowerCostOfTwoOtherwiseEqualNodes() {
        NodeRecord idle = node("n1", "Idle-Linux", 8, 16_384,
                ResourceStatus.of(5.0, 16_000, 0, 0, 5.0, 2.0));
        NodeRecord busy = node("n2", "Busy-Linux", 8, 16_384,
                ResourceStatus.of(90.0, 16_000, 6, 4, 5.0, 2.0));

        TaskDescriptor task = new TaskDescriptor("task-1", "test", 2, 512);
        Optional<NodeRecord> selected = scheduler.selectNode(task, List.of(idle, busy));

        assertTrue(selected.isPresent());
        assertEquals("n1", selected.get().nodeId());
    }

    @Test
    void filtersOutNodesThatDoNotMeetMemoryRequirement() {
        NodeRecord tooSmall = node("n1", "ESP32-ish", 1, 256,
                ResourceStatus.of(10.0, 200, 0, 0, 5.0, 1.0));
        TaskDescriptor task = new TaskDescriptor("task-1", "test", 1, 512);

        Optional<NodeRecord> selected = scheduler.selectNode(task, List.of(tooSmall));

        assertTrue(selected.isEmpty());
    }

    @Test
    void returnsEmptyWhenNoCandidatesGiven() {
        TaskDescriptor task = new TaskDescriptor("task-1", "test", 1, 128);
        assertTrue(scheduler.selectNode(task, List.of()).isEmpty());
    }
}
