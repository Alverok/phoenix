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
 * Recovery policy for failed execution attempts.
 *
 * <p>This component decides whether a failure is retryable and asks the
 * existing scheduler to choose an alternative node. It does not execute
 * tasks and does not know about concrete adapters or transports.
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

        this.registry =
                registry;

        this.scheduler =
                scheduler;

        this.maxAttempts =
                Integer.getInteger(
                        "phoenix.maxAttempts",
                        3
                );
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    /**
     * Infrastructure failures are retryable.
     *
     * <p>Ordinary task execution errors are deliberately not retried
     * automatically.
     */
    public boolean isRecoverable(
            TaskResult result) {

        return result != null
                && result.isRetryable();
    }

    /**
     * Ask the normal scheduler to select another currently schedulable
     * node while excluding nodes that already failed this logical task.
     */
    public Optional<NodeRecord> chooseAlternative(
            TaskDescriptor task,
            Set<String> excludedNodeIds) {

        List<NodeRecord> candidates =
                registry.listSchedulable()
                        .stream()
                        .filter(
                                node ->
                                        !excludedNodeIds.contains(
                                                node.nodeId()
                                        )
                        )
                        .toList();

        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        return scheduler.selectNode(
                task,
                candidates
        );
    }
}
