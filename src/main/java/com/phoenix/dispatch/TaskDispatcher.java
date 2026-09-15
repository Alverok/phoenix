package com.phoenix.dispatch;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.phoenix.adapter.IHardwareAdapter;
import com.phoenix.core.AdapterRegistry;
import com.phoenix.core.DeviceRegistry;
import com.phoenix.model.NodeExecutionTarget;
import com.phoenix.model.NodeRecord;
import com.phoenix.model.NodeStatus;
import com.phoenix.model.TaskDescriptor;
import com.phoenix.model.TaskFailureReason;
import com.phoenix.model.TaskResult;
import com.phoenix.recovery.TaskRecoveryManager;
import com.phoenix.scheduler.TaskScheduler;
import com.phoenix.tui.TaskJournal;

/**
 * Coordinates task placement and execution.
 *
 * <p>The dispatcher still delegates placement to TaskScheduler and execution
 * to IHardwareAdapter. Fault-aware recovery adds a retry loop around those
 * existing abstractions without coupling the dispatcher to transport code.
 */
@Singleton
public class TaskDispatcher {

    private final DeviceRegistry registry;
    private final TaskScheduler scheduler;
    private final AdapterRegistry adapterRegistry;
    private final TaskJournal taskJournal;
    private final TaskRecoveryManager recoveryManager;

    @Inject
    public TaskDispatcher(
            DeviceRegistry registry,
            TaskScheduler scheduler,
            AdapterRegistry adapterRegistry,
            TaskJournal taskJournal,
            TaskRecoveryManager recoveryManager) {

        this.registry =
                registry;

        this.scheduler =
                scheduler;

        this.adapterRegistry =
                adapterRegistry;

        this.taskJournal =
                taskJournal;

        this.recoveryManager =
                recoveryManager;
    }

    public DispatchResult dispatch(
            TaskDescriptor task) {

        taskJournal.submitted(
                task
        );

        System.out.printf(
                "[Dispatcher] Task submitted: id=%s type=%s%n",
                task.id(),
                task.type()
        );

        Set<String> excludedNodeIds =
                new HashSet<>();

        DispatchResult lastResult =
                null;

        for (
                int attempt = 1;
                attempt <= recoveryManager.maxAttempts();
                attempt++
        ) {

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

            Optional<NodeRecord> selected =
                    scheduler.selectNode(
                            task,
                            candidates
                    );

            if (selected.isEmpty()) {

                if (lastResult != null) {

                    taskJournal.completed(
                            task.id(),
                            lastResult.result()
                    );

                    System.out.printf(
                            "[Recovery] No eligible alternative "
                                    + "remains for %s after %d attempt(s)%n",
                            task.id(),
                            attempt - 1
                    );

                    return lastResult;
                }

                throw new NoEligibleNodeException(
                        task.id()
                );
            }

            NodeRecord node =
                    selected.get();

            String nodeId =
                    node.nodeId();

            String nodeName =
                    node.info().displayName();

            taskJournal.assigned(
                    task.id(),
                    nodeName
            );

            IHardwareAdapter adapter =
                    adapterRegistry.resolve(
                            node.info().adapterType()
                    );

            NodeExecutionTarget target =
                    new NodeExecutionTarget(
                            nodeId,
                            nodeName,
                            node.info().adapterType()
                    );

            if (attempt == 1) {

                System.out.printf(
                        "[Dispatcher] Resolved adapter '%s' "
                                + "for node %s (id=%s)%n",
                        target.adapterType(),
                        target.displayName(),
                        target.nodeId()
                );

            } else {

                System.out.printf(
                        "[Recovery] Retry %d/%d: "
                                + "assigning %s to %s (id=%s)%n",
                        attempt,
                        recoveryManager.maxAttempts(),
                        task.id(),
                        target.displayName(),
                        target.nodeId()
                );
            }

            TaskResult result;

            try {

                result =
                        adapter.executeTask(
                                task,
                                target
                        );

            } catch (Exception exception) {

                result =
                        new TaskResult(
                                task.id(),
                                "FAILED",
                                "Adapter execution failed: "
                                        + exception.getMessage(),
                                TaskFailureReason.TRANSPORT_ERROR
                        );
            }

            adapter.reportResult(
                    result
            );

            lastResult =
                    new DispatchResult(
                            nodeId,
                            nodeName,
                            result
                    );

            if (result.isSuccess()) {

                taskJournal.completed(
                        task.id(),
                        result
                );

                System.out.printf(
                        "[Dispatcher] Task completed: status=%s%n",
                        result.status()
                );

                return lastResult;
            }

            System.out.printf(
                    "[Recovery] Task %s failed on %s "
                            + "reason=%s%n",
                    task.id(),
                    nodeName,
                    result.failureReason()
            );

            /*
             * A task execution error is not automatically evidence
             * that the node itself is broken.
             */
            if (!recoveryManager.isRecoverable(
                    result
            )) {

                taskJournal.completed(
                        task.id(),
                        result
                );

                System.out.printf(
                        "[Dispatcher] Task %s ended with terminal "
                                + "failure reason=%s%n",
                        task.id(),
                        result.failureReason()
                );

                return lastResult;
            }

            /*
             * This node is excluded for the remainder of this logical task.
             */
            excludedNodeIds.add(
                    nodeId
            );

            /*
             * If infrastructure failure came from an otherwise ONLINE node,
             * mark it DEGRADED so the normal scheduler also stops assigning
             * unrelated NEW tasks to it until a heartbeat recovers it.
             */
            registry.get(
                    nodeId
            ).ifPresent(
                    current -> {

                        if (current.state()
                                == NodeStatus.ONLINE) {

                            System.out.printf(
                                    "[Recovery] Node %s failed "
                                            + "task %s -> DEGRADED%n",
                                    nodeName,
                                    task.id()
                            );

                            registry.markDegraded(
                                    nodeId
                            );
                        }
                    }
            );

            if (
                    attempt
                            >= recoveryManager.maxAttempts()
            ) {

                break;
            }

            taskJournal.retrying(
                    task.id(),
                    nodeName,
                    attempt + 1,
                    result.failureReason().name()
            );

            System.out.printf(
                    "[Recovery] Re-attempting %s...%n",
                    task.id()
            );
        }

        /*
         * All attempts exhausted.
         */
        if (lastResult != null) {

            taskJournal.completed(
                    task.id(),
                    lastResult.result()
            );

            System.out.printf(
                    "[Recovery] Task %s exhausted %d attempt(s)%n",
                    task.id(),
                    recoveryManager.maxAttempts()
            );

            return lastResult;
        }

        throw new NoEligibleNodeException(
                task.id()
        );
    }
}
