package com.phoenix.dispatch;

import java.util.HashSet;
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
import com.phoenix.tui.TaskJournal;

@Singleton
public class TaskDispatcher {

    private final DeviceRegistry registry;
    private final AdapterRegistry adapterRegistry;
    private final TaskJournal taskJournal;
    private final TaskRecoveryManager recoveryManager;
    private final PendingTaskQueue pendingQueue;

    @Inject
    public TaskDispatcher(
            DeviceRegistry registry,
            AdapterRegistry adapterRegistry,
            TaskJournal taskJournal,
            TaskRecoveryManager recoveryManager,
            PendingTaskQueue pendingQueue) {

        this.registry = registry;
        this.adapterRegistry = adapterRegistry;
        this.taskJournal = taskJournal;
        this.recoveryManager = recoveryManager;
        this.pendingQueue = pendingQueue;
    }

    /**
     * A dispatch either completes or becomes a logical waiting task.
     * Waiting is normal control flow, not an exception.
     */
    public Outcome dispatch(TaskDescriptor task) {
        taskJournal.submitted(task);
        System.out.printf(
                "[Dispatcher] Task submitted: id=%s type=%s%n",
                task.id(),
                task.type()
        );
        return place(task);
    }

    /** Re-placement path used by PendingTaskScheduler. */
    public Outcome replace(TaskDescriptor task) {
        return place(task);
    }

    private Outcome place(TaskDescriptor task) {

        Set<String> excludedNodeIds = new HashSet<>();
        DispatchResult lastResult = null;

        for (int attempt = 1;
             attempt <= recoveryManager.maxAttempts();
             attempt++) {

            Optional<NodeRecord> selected =
                    recoveryManager.chooseAlternative(
                            task,
                            excludedNodeIds
                    );

            if (selected.isEmpty()) {
                if (lastResult != null
                        && recoveryManager.isRetryable(lastResult.result())) {

                    taskJournal.waiting(
                            task.id(),
                            lastResult.result().failureReason().name()
                    );
                    pendingQueue.enqueue(task);

                    System.out.printf(
                            "[Recovery] %s WAITING: no eligible alternative after %d attempt(s)%n",
                            task.id(),
                            attempt - 1
                    );
                    return new Outcome.Waiting(task.id());
                }

                taskJournal.waiting(
                        task.id(),
                        TaskFailureReason.NO_ELIGIBLE_NODE.name()
                );
                pendingQueue.enqueue(task);

                System.out.printf(
                        "[Dispatcher] %s WAITING: no eligible node (will retry for %ds)%n",
                        task.id(),
                        pendingQueue.maxWait().toSeconds()
                );

                return new Outcome.Waiting(task.id());
            }

            NodeRecord node = selected.get();
            String nodeId = node.nodeId();
            String nodeName = node.info().displayName();

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
                        "[Dispatcher] Resolved adapter '%s' for node %s (id=%s)%n",
                        target.adapterType(),
                        target.displayName(),
                        target.nodeId()
                );
            } else {
                System.out.printf(
                        "[Recovery] Retry %d/%d: reassigning %s to %s (id=%s)%n",
                        attempt,
                        recoveryManager.maxAttempts(),
                        task.id(),
                        target.displayName(),
                        target.nodeId()
                );
            }

            TaskResult result;

            try {
                result = adapter.executeTask(
                        task,
                        target
                );
            } catch (Exception exception) {
                result = new TaskResult(
                        task.id(),
                        "FAILED",
                        "Adapter execution failed: "
                                + exception.getMessage(),
                        TaskFailureReason.TRANSPORT_ERROR
                );
            }

            adapter.reportResult(result);

            DispatchResult dispatchResult =
                    new DispatchResult(
                            nodeId,
                            nodeName,
                            result
                    );

            lastResult = dispatchResult;

            if (result.isSuccess()) {
                pendingQueue.remove(task.id());
                taskJournal.completed(
                        task.id(),
                        result
                );

                System.out.printf(
                        "[Dispatcher] Task completed: status=%s%n",
                        result.status()
                );

                return new Outcome.Completed(
                        dispatchResult
                );
            }

            System.out.printf(
                    "[Recovery] Task %s failed on %s reason=%s%n",
                    task.id(),
                    nodeName,
                    result.failureReason()
            );

            if (!recoveryManager.isRetryable(result)) {
                taskJournal.completed(
                        task.id(),
                        result
                );
                return new Outcome.Completed(
                        dispatchResult
                );
            }

            excludedNodeIds.add(nodeId);

            registry.get(nodeId).ifPresent(current -> {
                if (current.state() == NodeStatus.ONLINE) {
                    System.out.printf(
                            "[Recovery] Node %s failed task %s -> DEGRADED%n",
                            nodeName,
                            task.id()
                    );
                    registry.markDegraded(nodeId);
                }
            });

            if (attempt < recoveryManager.maxAttempts()) {
                taskJournal.retrying(
                        task.id(),
                        nodeName,
                        attempt + 1,
                        result.failureReason().name()
                );
            }
        }

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
            return new Outcome.Completed(lastResult);
        }

        taskJournal.waiting(
                task.id(),
                TaskFailureReason.NO_ELIGIBLE_NODE.name()
        );
        pendingQueue.enqueue(task);
        return new Outcome.Waiting(task.id());
    }

    public sealed interface Outcome
            permits Outcome.Completed, Outcome.Waiting {

        record Completed(DispatchResult result)
                implements Outcome {}

        record Waiting(String taskId)
                implements Outcome {}
    }
}
