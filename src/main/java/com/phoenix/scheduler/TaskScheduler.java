package com.phoenix.scheduler;

import java.util.List;
import java.util.Optional;

import com.phoenix.model.NodeRecord;
import com.phoenix.model.TaskDescriptor;

/**
 * The Phoenix "brain": picks which registered node should run a given task.
 *
 * <p>Deliberately an interface, not a single Greedy class, per the design
 * discussion (items 7-9): the end goal is a <em>pluggable scheduling
 * architecture</em>, not a specific algorithm.
 *
 * <pre>
 *                TaskScheduler
 *                     ▲
 *      ┌───────────────┼────────────────────┐
 *      │                │                    │
 * GreedyCostScheduler  MemeticPlacementScheduler   SustainabilityAwareScheduler
 *      (Day 2)              (future — Goudarzi)         (future — Chen et al.)
 * </pre>
 *
 * <p>{@link com.phoenix.dispatch.TaskDispatcher} depends only on this
 * interface, exactly the way {@link com.phoenix.adapter.IHardwareAdapter}
 * decouples the Core from concrete hardware — this is Unikraft's "modular
 * APIs, swappable implementations" lesson applied to the scheduling layer.
 */
public interface TaskScheduler {

    /**
     * Selects the best candidate node for {@code task} out of
     * {@code candidates}, or empty if none are eligible.
     *
     * <p>Callers should pass an already-filtered-for-liveness candidate list
     * (e.g. {@code DeviceRegistry.listSchedulable()}); the scheduler is
     * still responsible for capability eligibility (CPU/memory/hardware
     * class fit) on top of that.
     */
    Optional<NodeRecord> selectNode(TaskDescriptor task, List<NodeRecord> candidates);
}
