package com.phoenix.adapter.impl;

import java.util.Set;

import com.phoenix.adapter.IHardwareAdapter;
import com.phoenix.model.HardwareClass;
import com.phoenix.model.NodeExecutionTarget;
import com.phoenix.model.NodeInfo;
import com.phoenix.model.ResourceStatus;
import com.phoenix.model.TaskDescriptor;
import com.phoenix.model.TaskResult;

public class LinuxAdapter implements IHardwareAdapter {

    @Override
    public NodeInfo registerNode() {
        int cores = Runtime.getRuntime().availableProcessors();
        long totalMemMb = getTotalPhysicalMemoryMb();
        String arch = System.getProperty("os.arch");

        System.out.printf("[LinuxAdapter] Detected real host: %d cores, %dMB RAM, arch=%s%n",
                cores, totalMemMb, arch);

        return new NodeInfo(
                "template", "Linux-node", HardwareClass.LINUX, arch,
                cores, totalMemMb, "linux", Set.of());
    }

    @Override
    public ResourceStatus getResourceStatus() {
        com.sun.management.OperatingSystemMXBean osBean =
                (com.sun.management.OperatingSystemMXBean)
                java.lang.management.ManagementFactory.getOperatingSystemMXBean();

        double cpuLoad = osBean.getCpuLoad() * 100.0; // returns -1 if not available yet
        if (cpuLoad < 0) cpuLoad = 0.0;
        long freeMemMb = osBean.getFreeMemorySize() / (1024 * 1024);

        // networkLatencyMs and energyCostEstimate are still mocked for now —
        // real values need actual network probing / power profiling (future work).
        return ResourceStatus.of(cpuLoad, freeMemMb, 0, 0, 5.0, 4.5);
    }

    private long getTotalPhysicalMemoryMb() {
        com.sun.management.OperatingSystemMXBean osBean =
                (com.sun.management.OperatingSystemMXBean)
                java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        return osBean.getTotalMemorySize() / (1024 * 1024);
    }

    @Override
    public TaskResult executeTask(TaskDescriptor task, NodeExecutionTarget target) {
        long start = System.nanoTime();
        long samples = 2_000_000;
        long inside = 0;
        java.util.Random rnd = new java.util.Random();
        for (long i = 0; i < samples; i++) {
            double x = rnd.nextDouble();
            double y = rnd.nextDouble();
            if (x * x + y * y <= 1.0) inside++;
        }
        double pi = 4.0 * inside / samples;
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        System.out.printf("[LinuxAdapter] Executing task %s on node %s (%s)%n",
                task.id(), target.displayName(), target.nodeId());
        System.out.printf("[LinuxAdapter] Task %s finished in %dms (%d samples)%n",
                task.id(), elapsedMs, samples);

        return new TaskResult(task.id(), "SUCCESS",
                String.format("Computed Monte-Carlo pi ~= %.5f (in %dms, %d samples)", pi, elapsedMs, samples));
    }

    @Override
    public void reportResult(TaskResult result) {
        System.out.println("[LinuxAdapter] Result reported: " + result.status());
    }
}