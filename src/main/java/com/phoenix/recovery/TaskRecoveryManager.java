package com.phoenix.recovery;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.phoenix.core.DeviceRegistry;
import com.phoenix.model.NodeRecord;
import com.phoenix.model.TaskDescriptor;
import com.phoenix.model.TaskResult;
import com.phoenix.scheduler.TaskScheduler;

/**
 * Recovery policy and alternate-node selection for failed attempts.
 * The dispatcher owns execution; this component owns retry policy.
 */
@Singleton
public class TaskRecoveryManager {

    private final DeviceRegistry registry;
    private final TaskScheduler scheduler;
    private final int maxAttempts;

    @Inject
    public TaskRecoveryManager(
            DeviceRegistry registry,
            TaskScheduler scheduler) {
        this.registry = registry;
        this.scheduler = scheduler;
        this.maxAttempts = Integer.getInteger("phoenix.maxAttempts", 3);
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public boolean isRetryable(TaskResult result) {
        return result != null && result.isRetryable();
    }

    public Optional<NodeRecord> chooseAlternative(
            TaskDescriptor task,
            Set<String> excludedNodeIds) {

        List<NodeRecord> candidates = registry.listSchedulable().stream()
                .filter(node -> !excludedNodeIds.contains(node.nodeId()))
                .toList();

        return scheduler.selectNode(task, candidates);
    }
}
