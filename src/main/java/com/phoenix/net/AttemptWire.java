package com.phoenix.net;

import com.phoenix.core.AttemptFencing;

/**
 * What Core puts on the wire to identify an attempt.
 *
 * <p>Fields are sent only to nodes whose attempt is
 * {@link AttemptFencing#ENFORCED}, i.e. nodes that announced the
 * {@link ProtocolFeatures#ATTEMPT_ID} feature at registration and are
 * therefore known to cope with, and echo, them:
 *
 * <ul>
 *     <li>{@code "attemptId"} - an opaque, globally unique id. This is the
 *         identity. The node echoes it verbatim in {@code POST /tasks/started}
 *         and {@code POST /results}; Core rejects a report with a different
 *         id, or with none, as stale - even after the task was re-delivered,
 *         and even after a Core restart reused the same task id.</li>
 *     <li>{@code "attemptNumber"} - 1, 2, 3 ... for this task. Informational
 *         only (logs, UI). Core never uses it to match a report.</li>
 * </ul>
 *
 * <p>A node that announced nothing is {@link AttemptFencing#LEGACY}: it never
 * sees an unknown JSON field, and its reports are accepted unfenced.
 */
final class AttemptWire {

    private AttemptWire() {
    }

    /**
     * {@code ,"attemptId":"...","attemptNumber":N} for an enforced attempt,
     * otherwise the empty string.
     */
    static String jsonFields(
            AttemptFencing fencing,
            String attemptId,
            int attemptNumber) {

        return fencing == AttemptFencing.ENFORCED
                ? ",\"attemptId\":\"" + escape(attemptId) + "\""
                        + ",\"attemptNumber\":" + attemptNumber
                : "";
    }

    private static String escape(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }
}
