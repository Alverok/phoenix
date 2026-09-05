package com.phoenix.dispatch;

/**
 * Thrown by {@link TaskDispatcher} when the {@link com.phoenix.scheduler.TaskScheduler}
 * cannot find any registered node able to satisfy a task's requirements.
 *
 * <p>Deliberately a plain, unchecked exception for Day 2 — retry/backoff/
 * requeue policy is explicitly future work (design discussion, item 13);
 * for now callers just need to know placement failed and why.
 */
public class NoEligibleNodeException extends RuntimeException {
    public NoEligibleNodeException(String taskId) {
        super("No eligible node found for task '" + taskId + "'");
    }
}
