package com.phoenix.scheduler;

import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;

import com.google.inject.Inject;
import com.phoenix.model.NodeRecord;
import com.phoenix.model.TaskDescriptor;
import com.phoenix.model.TaskRequirements;

/**
 * Greedy argmin implementation of Phoenix's weighted placement model.
 */
public class GreedyCostScheduler implements TaskScheduler {

    private final PlacementCostModel costModel;

    @Inject
    public GreedyCostScheduler(
            PlacementCostModel costModel) {
        this.costModel = costModel;
    }

    @Override
    public Optional<NodeRecord> selectNode(
            TaskDescriptor task,
            List<NodeRecord> candidates) {

        List<NodeRecord> eligible = candidates.stream()
                .filter(node -> isEligible(node, task))
                .toList();

        if (eligible.isEmpty()) {
            System.out.println(
                    "[Scheduler] No eligible nodes for task "
                            + task.id()
            );
            return Optional.empty();
        }

        NodeRecord best = null;
        double bestCost = Double.POSITIVE_INFINITY;
        StringJoiner summary = new StringJoiner(" | ");

        for (NodeRecord node : eligible) {
            PlacementCostModel.CostBreakdown breakdown =
                    costModel.breakdown(node, task);

            summary.add(
                    String.format(
                            "%s{C=%.3f,T=%.2f,E=%.2f,L=%.2f,R=%.2f}",
                            node.info().displayName(),
                            breakdown.total(),
                            breakdown.executionTime(),
                            breakdown.energyCost(),
                            breakdown.loadPenalty(),
                            breakdown.networkLatency()
                    )
            );

            if (breakdown.total() < bestCost) {
                bestCost = breakdown.total();
                best = node;
            }
        }

        System.out.printf(
                "[Placement] %s candidates=%d | %s | SELECTED=%s cost=%.3f%n",
                task.id(),
                eligible.size(),
                summary,
                best.info().displayName(),
                bestCost
        );

        return Optional.of(best);
    }

    private boolean isEligible(
            NodeRecord node,
            TaskDescriptor task) {

        TaskRequirements req = task.requirements();

        boolean cpuOk =
                node.info().cpuCores() >= req.cpuCores();

        boolean memOk =
                node.status().availableRamMb()
                        >= req.memoryMb();

        boolean hardwareOk =
                req.requiredHardwareClass()
                        .map(required ->
                                required == node.info().hardwareClass())
                        .orElse(true);

        return cpuOk && memOk && hardwareOk;
    }
}
