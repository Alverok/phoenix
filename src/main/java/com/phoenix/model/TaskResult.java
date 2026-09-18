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

    public boolean isRetryable() {
        return switch (failureReason) {
            case TIMEOUT,
                 NODE_UNREACHABLE,
                 TRANSPORT_ERROR,
                 NODE_OFFLINE -> true;
            case NONE,
                 EXECUTION_ERROR,
                 NO_ELIGIBLE_NODE -> false;
        };
    }
}
