package com.phoenix;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.phoenix.adapter.IHardwareAdapter;
import com.phoenix.core.PhoenixModule;
import com.phoenix.model.NodeInfo;
import com.phoenix.model.TaskDescriptor;
import com.phoenix.model.TaskResult;

/**
 * Day 1 vertical slice: Guice resolves whichever {@link IHardwareAdapter}
 * is selected, App never mentions LinuxAdapter/AndroidAdapter/Esp32Adapter
 * by name anywhere in this class.
 */
public class App {

    public static void main(String[] args) {
        System.out.println("=== Phoenix Core starting ===");

        Injector injector = Guice.createInjector(new PhoenixModule());
        IHardwareAdapter adapter = injector.getInstance(IHardwareAdapter.class);

        NodeInfo node = adapter.registerNode();
        System.out.printf(
                "[Phoenix] Node registered: id=%s class=%s cpuCores=%d memoryMb=%d%n",
                node.id(), node.hardwareClass(), node.cpuCores(), node.memoryMb());

        TaskDescriptor task = new TaskDescriptor("task-001", "Monte-Carlo-Pi", 2, 512);
        System.out.printf("[Phoenix] Submitting task: id=%s type=%s%n", task.id(), task.type());

        TaskResult result = adapter.executeTask(task);
        adapter.reportResult(result);

        System.out.printf(
                "[Phoenix] Task complete: status=%s output=%s%n",
                result.status(), result.outputData());

        System.out.println("=== Phoenix Core shutting down ===");
    }
}
