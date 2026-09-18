package com.phoenix.model;

/**
 * Why one execution attempt did not complete successfully.
 */
public enum TaskFailureReason {
    NONE,
    EXECUTION_ERROR,
    TIMEOUT,
    NODE_UNREACHABLE,
    TRANSPORT_ERROR,
    NODE_OFFLINE,
    NO_ELIGIBLE_NODE
}
