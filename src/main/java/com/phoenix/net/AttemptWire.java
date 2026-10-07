package com.phoenix.net;

/**
 * Wire-level switch for the optional execution-attempt identifier.
 *
 * <p>When enabled, Core adds {@code "attempt": N} to the task it hands to a
 * node (pull response / push request body). A node that echoes it back in
 * {@code POST /tasks/started} and {@code POST /results} lets Core reject a
 * late message from an abandoned attempt even after the task has been
 * re-delivered to another node.
 *
 * <p>It is additive and optional in both directions, but it is <b>off by
 * default</b> because Phoenix cannot know whether every deployed agent
 * tolerates an unknown JSON field. Enable with
 * {@code -Dphoenix.attemptOnWire=true} once your agents are verified.
 * Core still tracks attempts internally either way.
 */
final class AttemptWire {

    private AttemptWire() {
    }

    /** Read on every call so tests (and operators) can flip it at runtime. */
    static boolean enabled() {
        return Boolean.getBoolean("phoenix.attemptOnWire");
    }

    /** {@code ,"attempt":N} when enabled, otherwise the empty string. */
    static String jsonField(int attempt) {
        return enabled()
                ? ",\"attempt\":" + attempt
                : "";
    }
}
