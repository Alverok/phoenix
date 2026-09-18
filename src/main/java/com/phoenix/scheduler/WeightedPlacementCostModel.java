package com.phoenix.scheduler;

import com.google.inject.Inject;
import com.phoenix.model.NodeInfo;
import com.phoenix.model.NodeRecord;
import com.phoenix.model.ResourceStatus;
import com.phoenix.model.TaskDescriptor;

/**
 * Day-2 implementation of the weighted-cost placement formula from the
 * design document:
 *
 * <pre>
 * C(n) = w1*executionTime(n) + w2*energyCost(n) + w3*loadPenalty(n) + w4*networkLatency(n)
 * </pre>
 *
 * <p>Per the Day-2 rule, this does NOT build a prediction model. Every term
 * is derived from whatever {@link ResourceStatus} the node currently has
 * (which is mocked/hardcoded by {@link com.phoenix.sim.NodeSimulator} for
 * now) using a simple, transparent formula — good enough to prove the
 * scheduler's argmin logic works and produce different, explainable
 * rankings across heterogeneous nodes.
 *
 * <ul>
 *   <li><b>executionTime</b> — scaled by how many CPU cores the task needs
 *       relative to what the node has free right now. A slower/busier node
 *       gets a higher number.</li>
 *   <li><b>energyCost</b> — read directly off the node's current
 *       {@code energyCostEstimate}. Real per-hardware-class energy profiling
 *       is future work (Chen et al.-inspired); Day 2 just needs the field to
 *       exist and participate in the formula.</li>
 *   <li><b>loadPenalty</b> — a simple function of active tasks + queue
 *       length, so a busy node is penalized even if its CPU % looks free at
 *       this instant.</li>
 *   <li><b>networkLatency</b> — read directly off {@code networkLatencyMs}.</li>
 * </ul>
 */
public class WeightedPlacementCostModel implements PlacementCostModel {

    private final SchedulingWeights weights;

    @Inject
    public WeightedPlacementCostModel() {
        this(SchedulingWeights.defaults());
    }

    public WeightedPlacementCostModel(SchedulingWeights weights) {
        this.weights = weights;
    }

    @Override
    public double cost(NodeRecord node, TaskDescriptor task) {
        return breakdown(node, task).total();
    }

    @Override
    public CostBreakdown breakdown(NodeRecord node, TaskDescriptor task) {
        NodeInfo info = node.info();
        ResourceStatus status = node.status();

        double executionTime = estimateExecutionTime(info, status, task);
        double energyCost = status.energyCostEstimate();
        double loadPenalty = estimateLoadPenalty(status);
        double networkLatency = status.networkLatencyMs();

        double total =
                weights.executionTimeWeight() * executionTime
                        + weights.energyCostWeight() * energyCost
                        + weights.loadPenaltyWeight() * loadPenalty
                        + weights.networkLatencyWeight() * networkLatency;

        return new CostBreakdown(info.nodeId(), executionTime, energyCost, loadPenalty, networkLatency, total);
    }

    /**
     * Rough proxy: baseline demand (requested CPU cores) divided by the
     * node's effective free capacity (its core count discounted by current
     * CPU usage). Busy or weaker nodes report a higher "time".
     */
    private double estimateExecutionTime(NodeInfo info, ResourceStatus status, TaskDescriptor task) {
        double requestedCores = Math.max(task.requirements().cpuCores(), 1);
        double cpu = status.cpuUsagePercent();
        double freeCapacityFraction = cpu < 0
                ? 1.0
                : Math.max(0.05, 1.0 - (cpu / 100.0));
        double effectiveCores = Math.max(0.1, info.cpuCores() * freeCapacityFraction);
        return requestedCores / effectiveCores;
    }

    private double estimateLoadPenalty(ResourceStatus status) {
        return status.activeTasks() * 0.5 + status.queueLength() * 0.25;
    }
}
