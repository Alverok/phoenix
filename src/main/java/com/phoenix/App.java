package com.phoenix;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.phoenix.core.DeviceRegistry;
import com.phoenix.core.PhoenixModule;
import com.phoenix.dispatch.DispatchResult;
import com.phoenix.dispatch.TaskDispatcher;
import com.phoenix.model.NodeRecord;
import com.phoenix.model.TaskDescriptor;
import com.phoenix.sim.NodeSimulator;

/**
 * Day 2 vertical slice: Phoenix Core now orchestrates across a small
 * simulated multi-node mesh instead of talking to one hand-picked adapter.
 *
 * <pre>
 * Day 1: App -&gt; IHardwareAdapter -&gt; execute
 * Day 2: App -&gt; TaskDispatcher -&gt; Scheduler -&gt; DeviceRegistry -&gt; AdapterRegistry -&gt; IHardwareAdapter -&gt; execute
 * </pre>
 *
 * <p>Notice what App no longer does: it never picks an adapter, never reads
 * {@code -Dphoenix.adapter}, and never calls {@code adapter.executeTask()}
 * directly. It submits a task to {@link TaskDispatcher} and lets the
 * registry + scheduler decide which of several registered nodes should run
 * it — that is the entire Day-2 milestone in one sentence.
 */
public class App {

    public static void main(String[] args) {
        System.out.println("=== Phoenix Core starting ===");

        Injector injector = Guice.createInjector(new PhoenixModule());

        // Simulated self-registration: several real, distinct node
        // instances announce themselves to the registry. No network
        // scanning, no MQTT yet — see NodeSimulator's javadoc.
        NodeSimulator simulator = injector.getInstance(NodeSimulator.class);
        simulator.bootstrapDemoFleet();

        DeviceRegistry registry = injector.getInstance(DeviceRegistry.class);
        System.out.println("[Registry] " + registry.size() + " node(s) currently online:");
        for (NodeRecord node : registry.list()) {
            System.out.printf("  - %s (%s, %s, %dc/%dMB)%n",
                    node.info().displayName(), node.info().hardwareClass(),
                    node.info().adapterType(), node.info().cpuCores(), node.info().memoryMb());
        }

        TaskDescriptor task = new TaskDescriptor("task-001", "Monte-Carlo-Pi", 2, 512);
        TaskDispatcher dispatcher = injector.getInstance(TaskDispatcher.class);
        DispatchResult dispatchResult = dispatcher.dispatch(task);

        System.out.printf(
                "[Phoenix] Task complete: node=%s status=%s output=%s%n",
                dispatchResult.nodeDisplayName(),
                dispatchResult.result().status(),
                dispatchResult.result().outputData());

        System.out.println("=== Phoenix Core shutting down ===");
    }
}
