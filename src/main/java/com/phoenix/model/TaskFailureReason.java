package com.phoenix.model;

/**
 * Describes why a task execution attempt did not succeed.
 *
 * <p>This distinction is important because a failed task does not
 * necessarily imply a failed node.
 */
public enum TaskFailureReason {

    /**
     * The execution succeeded.
     */
    NONE,

    /**
     * The node received the task, but the task itself failed.
     * This is not automatically considered a node failure.
     */
    EXECUTION_ERROR,

    /**
     * Phoenix waited too long for the execution result.
     */
    TIMEOUT,

    /**
     * Phoenix could not reach the node.
     */
    NODE_UNREACHABLE,

    /**
     * The transport mechanism failed.
     */
    TRANSPORT_ERROR,

    /**
     * The registry already considers the selected node offline.
     */
    NODE_OFFLINE
}
