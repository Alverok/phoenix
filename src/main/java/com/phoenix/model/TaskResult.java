package com.phoenix.model;

import java.util.Objects;

/**
 * Result of one task execution attempt.
 *
 * <p>The four-argument form carries explicit failure semantics.
 * The three-argument constructor is retained for compatibility with
 * existing callers.
 */
public record TaskResult(
        String taskId,
        String status,
        String outputData,
        TaskFailureReason failureReason
) {

    /**
     * Backward-compatible constructor.
     *
     * <p>Successful results are assigned NONE. Any generic failure created
     * through this constructor is classified as EXECUTION_ERROR because
     * the caller has not provided enough information to blame the transport
     * or node.
     */
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

    public TaskResult {

        Objects.requireNonNull(
                taskId,
                "taskId"
        );

        Objects.requireNonNull(
                status,
                "status"
        );

        Objects.requireNonNull(
                failureReason,
                "failureReason"
        );
    }

    public boolean isSuccess() {
        return "SUCCESS".equalsIgnoreCase(
                status
        );
    }

    /**
     * Whether this failure represents an infrastructure problem for which
     * retrying the task elsewhere is meaningful.
     */
    public boolean isRetryable() {

        return switch (failureReason) {

            case TIMEOUT,
                 NODE_UNREACHABLE,
                 TRANSPORT_ERROR,
                 NODE_OFFLINE ->
                    true;

            case NONE,
                 EXECUTION_ERROR ->
                    false;
        };
    }
}
