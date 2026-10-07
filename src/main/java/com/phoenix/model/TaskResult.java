package com.phoenix.model;

/**
 * Outcome of one execution attempt.
 */
public record TaskResult(
        String taskId,
        String status,
        String outputData,
        TaskFailureReason failureReason
) {

    /** Backward-compatible constructor used by existing agents/transports. */
    public TaskResult(
            String taskId,
            String status,
            String outputData) {
        this(
                taskId,
                status,
                outputData,
                "SUCCESS".equalsIgnoreCase(status)
                        ? TaskFailureReason.NONE
                        : TaskFailureReason.EXECUTION_ERROR
        );
    }

    public boolean isSuccess() {
        return "SUCCESS".equalsIgnoreCase(status);
    }

    /**
     * Whether the task may safely be re-placed on another node.
     *
     * <p>Every timeout flavour is retryable: a task that was never delivered
     * has not run anywhere, and a task that was delivered but silent is
     * recovered under Phoenix's existing at-least-once recovery semantics.
     */
    public boolean isRetryable() {
        return switch (failureReason) {
            case TIMEOUT,
                 DELIVERY_TIMEOUT,
                 EXECUTION_TIMEOUT,
                 NODE_UNREACHABLE,
                 TRANSPORT_ERROR,
                 NODE_OFFLINE -> true;
            case NONE,
                 EXECUTION_ERROR,
                 NO_ELIGIBLE_NODE -> false;
        };
    }

    /**
     * Whether this failure is evidence against the node itself.
     *
     * <p>This is deliberately separate from {@link #isRetryable()}:
     *
     * <ul>
     *     <li>{@link TaskFailureReason#DELIVERY_TIMEOUT} is retryable (the
     *         task should be placed elsewhere) but is only queue backlog on
     *         a node that is still in contact, so it must not move a healthy
     *         node to DEGRADED.</li>
     *     <li>{@link TaskFailureReason#NODE_OFFLINE} is retryable but is
     *         <em>derived from</em> node health: Core abandoned the attempt
     *         because {@code NodeHealthMonitor} had already declared the node
     *         OFFLINE. Feeding it back would re-penalise a node whose
     *         liveness has since been re-established by a fresh heartbeat.</li>
     * </ul>
     *
     * Every other retryable failure keeps its existing meaning.
     */
    public boolean implicatesNodeHealth() {
        return switch (failureReason) {
            case TIMEOUT,
                 EXECUTION_TIMEOUT,
                 NODE_UNREACHABLE,
                 TRANSPORT_ERROR -> true;
            case DELIVERY_TIMEOUT,
                 NODE_OFFLINE,
                 NONE,
                 EXECUTION_ERROR,
                 NO_ELIGIBLE_NODE -> false;
        };
    }
}
