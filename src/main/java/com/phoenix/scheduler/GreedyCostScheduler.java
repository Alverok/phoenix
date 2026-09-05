package com.phoenix.scheduler;

import java.util.List;
import java.util.Optional;

import com.google.inject.Inject;
import com.phoenix.model.NodeRecord;
import com.phoenix.model.TaskDescriptor;
import com.phoenix.model.TaskRequirements;

/**
 * Day-2 scheduler: a simple greedy argmin heuristic over the weighted-cost
 * formula, exactly matching the "simple greedy argmin heuristic, not the
 * full Memetic Algorithm" scope decision in the submitted design.
 *
 * <pre>
 * for each candidate node:
 *     if node satisfies task's capability requirements:
 *         cost = costModel.cost(node, task)
 * choose the node with the minimum cost
 * </pre>
 *
 * <p>Filtering and cost computation are intentionally two separate, small
 * steps (see {@link #isEligible}) rather than folded into one big loop body
 * — a future scheduler (Memetic, sustainability-aware) can reuse
 * {@link #isEligible} while replacing only the selection strategy.
 */
public class GreedyCostScheduler implements TaskScheduler {

    private final PlacementCostModel costModel;

    @Inject
    public GreedyCostScheduler(PlacementCostModel costModel) {
        this.costModel = costModel;
    }

    @Override
    public Optional<NodeRecord> selectNode(TaskDescriptor task, List<NodeRecord> candidates) {
        List<NodeRecord> eligible = candidates.stream()
                .filter(node -> isEligible(node, task))
                .toList();

        if (eligible.isEmpty()) {
            System.out.println("[Scheduler] No eligible nodes for task " + task.id());
            return Optional.empty();
        }

        System.out.println("[Scheduler] Evaluating " + eligible.size() + " eligible node(s) for " + task.id() + ":");
        NodeRecord best = null;
        double bestCost = Double.POSITIVE_INFINITY;

        for (NodeRecord node : eligible) {
            PlacementCostModel.CostBreakdown breakdown = costModel.breakdown(node, task);
            System.out.printf(
                    "  %-16s cost=%.3f (time=%.2f energy=%.2f load=%.2f latency=%.2f)%n",
                    node.info().displayName(), breakdown.total(), breakdown.executionTime(),
                    breakdown.energyCost(), breakdown.loadPenalty(), breakdown.networkLatency());

            if (breakdown.total() < bestCost) {
                bestCost = breakdown.total();
                best = node;
            }
        }

        System.out.println("[Scheduler] Selected node: " + best.info().displayName());
        return Optional.of(best);
    }

    /**
     * Capability eligibility check, ahead of cost computation. Matches the
     * design document's "eligible if capabilities satisfy requirements"
     * rule. Deliberately conservative for Day 2: CPU cores and memory only.
     * {@link TaskRequirements}'s latency/hardware-class/capability-tag
     * fields are already modeled so this method can grow into them without
     * changing its signature.
     */
    private boolean isEligible(NodeRecord node, TaskDescriptor task) {
        TaskRequirements req = task.requirements();
        boolean cpuOk = node.info().cpuCores() >= req.cpuCores();
        boolean memOk = node.status().availableRamMb() >= req.memoryMb();
        boolean hardwareOk = req.requiredHardwareClass()
                .map(required -> required == node.info().hardwareClass())
                .orElse(true);
        return cpuOk && memOk && hardwareOk;
    }
}
