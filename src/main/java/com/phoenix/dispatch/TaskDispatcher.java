package com.phoenix.dispatch;

import java.util.List;
import java.util.Optional;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.phoenix.adapter.IHardwareAdapter;
import com.phoenix.core.AdapterRegistry;
import com.phoenix.core.DeviceRegistry;
import com.phoenix.model.NodeExecutionTarget;
import com.phoenix.model.NodeRecord;
import com.phoenix.model.TaskDescriptor;
import com.phoenix.model.TaskResult;
import com.phoenix.scheduler.TaskScheduler;

@Singleton
public class TaskDispatcher {

    private final DeviceRegistry registry;
    private final TaskScheduler scheduler;
    private final AdapterRegistry adapterRegistry;

    @Inject
    public TaskDispatcher(DeviceRegistry registry, TaskScheduler scheduler, AdapterRegistry adapterRegistry) {
        this.registry = registry;
        this.scheduler = scheduler;
        this.adapterRegistry = adapterRegistry;
    }

    public DispatchResult dispatch(TaskDescriptor task) {
        System.out.printf("[Dispatcher] Task submitted: id=%s type=%s%n", task.id(), task.type());

        List<NodeRecord> candidates = registry.listSchedulable();
        Optional<NodeRecord> selected = scheduler.selectNode(task, candidates);

        NodeRecord node = selected.orElseThrow(() -> new NoEligibleNodeException(task.id()));

        IHardwareAdapter adapter = adapterRegistry.resolve(node.info().adapterType());

        NodeExecutionTarget target = new NodeExecutionTarget(
                node.nodeId(),
                node.info().displayName(),
                node.info().adapterType());

        System.out.printf("[Dispatcher] Resolved adapter '%s' for node %s (id=%s)%n",
                target.adapterType(), target.displayName(), target.nodeId());

        TaskResult result = adapter.executeTask(task, target);
        adapter.reportResult(result);

        System.out.printf("[Dispatcher] Task completed: status=%s%n", result.status());

        return new DispatchResult(node.nodeId(), node.info().displayName(), result);
    }
}
