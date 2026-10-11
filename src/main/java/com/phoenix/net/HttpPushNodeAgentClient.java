package com.phoenix.net;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import com.google.gson.Gson;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.phoenix.core.TaskLifecycleService;
import com.phoenix.core.TaskTimeoutPolicy;
import com.phoenix.model.NodeExecutionTarget;
import com.phoenix.model.TaskDescriptor;
import com.phoenix.model.TaskFailureReason;
import com.phoenix.model.TaskResult;

/**
 * Push-style transport for directly addressable LAN nodes such as Linux.
 *
 * <p>Issue #28:
 *
 * <p>Push transport has no Core-side task queue, so there is no separate
 * delivery-wait phase like the HTTP pull transport. Once the HTTP connection
 * is successfully established and the request body is submitted, the task is
 * considered delivered. The shared {@link TaskTimeoutPolicy} then controls
 * how long Phoenix waits for the execution result.
 *
 * <pre>
 * accepted
 *     |
 *     v
 * connect + submit
 *     |
 *     +-- connection failure -> NODE_UNREACHABLE
 *     |
 *     v
 * delivered
 *     |
 *     +-- execution timeout -> EXECUTION_TIMEOUT
 *     |
 *     v
 * result
 *     |
 *     +-- SUCCESS -> COMPLETED
 *     +-- FAILED  -> FAILED
 * </pre>
 *
 * <p>The connection timeout is a transport-level concern. The execution
 * timeout is owned by {@link TaskTimeoutPolicy} so that all Phoenix transports
 * share the same Core-level execution semantics.
 */
@Singleton
public class HttpPushNodeAgentClient
        implements NodeAgentClient {

    /*
     * Connection establishment is a transport concern rather than a task
     * execution timeout. Five seconds bounds how long Core waits to establish
     * a TCP connection to the directly-addressable node.
     */
    private static final int CONNECT_TIMEOUT_MS = 5_000;

    private final RegistrationServer registrationServer;
    private final TaskLifecycleService lifecycle;
    private final TaskTimeoutPolicy timeouts;
    private final Gson gson = new Gson();

    @Inject
    public HttpPushNodeAgentClient(
            RegistrationServer registrationServer,
            TaskLifecycleService lifecycle,
            TaskTimeoutPolicy timeouts) {

        this.registrationServer = registrationServer;
        this.lifecycle = lifecycle;
        this.timeouts = timeouts;
    }

    @Override
    public TaskResult execute(
            NodeExecutionTarget target,
            TaskDescriptor task) {

        var callbackAddress =
                registrationServer.callbackAddressForNode(
                        target.nodeId()
                );

        if (callbackAddress.isEmpty()) {
            return new TaskResult(
                    task.id(),
                    "FAILED",
                    "Node " + target.displayName()
                            + " has no registered callback address",
                    TaskFailureReason.NODE_UNREACHABLE
            );
        }

        String url =
                "http://" + callbackAddress.get() + "/execute";

        String requestBody =
                "{\"taskId\":\""
                        + escape(task.id())
                        + "\",\"type\":\""
                        + escape(task.type())
                        + "\",\"samples\":2000000"
                        + "@@ATTEMPT@@}";

        System.out.printf(
                "[HttpPushNodeAgentClient] Pushing %s directly to %s (%s)%n",
                task.id(),
                target.displayName(),
                callbackAddress.get()
        );

        /*
         * Push has no Core-side queue.
         *
         * The transport has accepted responsibility for this execution
         * attempt once execute() begins processing a valid target.
         */
        TaskLifecycleService.Attempt attempt =
                lifecycle.accepted(
                        task.id(),
                        target.nodeId(),
                        registrationServer.fencingFor(target.nodeId()));

        /*
         * Optional attempt identity for nodes that echo it back.
         */
        requestBody = requestBody.replace(
                "@@ATTEMPT@@",
                AttemptWire.jsonFields(
                        attempt.fencing(),
                        attempt.id(),
                        attempt.number())
        );

        /*
         * This flag separates connection/delivery failure from failures that
         * happen after the task has reached the node.
         */
        boolean delivered = false;

        try {
            HttpURLConnection conn = openConnection(url);

            conn.setRequestMethod("POST");

            /*
             * In-flight reconciliation: if Core declares the node OFFLINE
             * while this thread is blocked on the node, closing the socket
             * is the only way to wake it before the execution timeout.
             */
            attempt.onAbandon(conn::disconnect);

            /*
             * Connection timeout is intentionally not controlled by
             * TaskTimeoutPolicy. It answers a different question:
             *
             * "Can Phoenix establish communication with this directly
             * addressable node?"
             */
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);

            /*
             * Execution timeout IS controlled by Core's shared policy.
             *
             * This replaces the old hard-coded 35-second timeout.
             */
            conn.setReadTimeout(
                    Math.toIntExact(
                            timeouts.executionTimeout().toMillis()
                    )
            );

            conn.setRequestProperty(
                    "Content-Type",
                    "application/json"
            );

            conn.setDoOutput(true);

            if (attempt.isAbandoned()) {
                return nodeOffline(target, task);
            }

            /*
             * Writing the request establishes communication with the node.
             * If this fails, the task was never delivered.
             */
            try (OutputStream os = conn.getOutputStream()) {
                os.write(
                        requestBody.getBytes(
                                StandardCharsets.UTF_8
                        )
                );
            }

            /*
             * At this point the request has been handed to the remote HTTP
             * endpoint. Push transport has no explicit application-level
             * delivery acknowledgement, so successful request submission is
             * our delivery boundary.
             */
            delivered = true;
            lifecycle.delivered(attempt);

            /*
             * From this point onward, waiting is execution waiting.
             */
            int code = conn.getResponseCode();

            var stream = code == 200
                    ? conn.getInputStream()
                    : conn.getErrorStream();

            String response = stream == null
                    ? ""
                    : new String(
                            stream.readAllBytes(),
                            StandardCharsets.UTF_8
                    );

            /*
             * An HTTP error means the node received the request but the
             * execution attempt did not complete successfully.
             */
            if (code != 200) {
                lifecycle.failed(attempt);

                return new TaskResult(
                        task.id(),
                        "FAILED",
                        "Node returned HTTP "
                                + code
                                + ": "
                                + response,
                        code >= 500
                                ? TaskFailureReason.TRANSPORT_ERROR
                                : TaskFailureReason.EXECUTION_ERROR
                );
            }

            ResultBody body =
                    gson.fromJson(
                            response,
                            ResultBody.class
                    );

            if (body == null || body.status == null) {
                lifecycle.failed(attempt);

                return new TaskResult(
                        task.id(),
                        "FAILED",
                        "Node returned an invalid result body",
                        TaskFailureReason.TRANSPORT_ERROR
                );
            }

            TaskResult reported =
                    new TaskResult(
                            task.id(),
                            body.status,
                            body.outputData,
                            "SUCCESS".equalsIgnoreCase(body.status)
                                    ? TaskFailureReason.NONE
                                    : TaskFailureReason.EXECUTION_ERROR
                    );

            // Same mapping as the pull transport (Issue #37).
            lifecycle.reported(attempt, reported);

            return reported;

        } catch (SocketTimeoutException exception) {

            if (attempt.isAbandoned()) {
                return nodeOffline(target, task);
            }

            lifecycle.failed(attempt);

            /*
             * SocketTimeoutException can represent either:
             *
             * 1. connection establishment timing out
             * 2. result/execution read timing out
             *
             * The delivered flag tells us which phase we were in.
             */
            if (delivered) {
                return new TaskResult(
                        task.id(),
                        "FAILED",
                        "No result from "
                                + target.displayName()
                                + " within "
                                + timeouts.executionTimeout().toSeconds()
                                + "s of delivery",
                        TaskFailureReason.EXECUTION_TIMEOUT
                );
            }

            return new TaskResult(
                    task.id(),
                    "FAILED",
                    "Push to "
                            + target.displayName()
                            + " failed before delivery: "
                            + exception.getMessage(),
                    TaskFailureReason.NODE_UNREACHABLE
            );

        } catch (RuntimeException exception) {

            /*
             * HttpURLConnection.disconnect() is not safe to race against a
             * thread that is still inside getResponseCode(): the JDK can null
             * its internal connection state mid-request and surface an
             * unchecked NullPointerException instead of an IOException.
             *
             * If Core abandoned this attempt, that is the abandonment we
             * asked for, not a transport failure, and it must not escape
             * execute() (it would be misclassified as TRANSPORT_ERROR and
             * would implicate the node's health).
             *
             * Anything else is a genuine programming error: rethrow it.
             */
            if (attempt.isAbandoned()) {
                return nodeOffline(target, task);
            }

            throw exception;

        } catch (IOException exception) {

            /*
             * A disconnect() issued by the abandon hook surfaces here as a
             * "socket closed" IOException. That is not a transport failure.
             */
            if (attempt.isAbandoned()) {
                return nodeOffline(target, task);
            }

            lifecycle.failed(attempt);

            /*
             * If delivery never happened, the node could not be reached.
             *
             * If delivery already happened, however, an arbitrary I/O failure
             * does not necessarily mean the node is unreachable. The request
             * may have executed and the connection may simply have failed
             * while returning the result. Treat that as a transport failure.
             */
            TaskFailureReason reason =
                    delivered
                            ? TaskFailureReason.TRANSPORT_ERROR
                            : TaskFailureReason.NODE_UNREACHABLE;

            return new TaskResult(
                    task.id(),
                    "FAILED",
                    "Push to "
                            + target.displayName()
                            + " failed: "
                            + exception.getMessage(),
                    reason
            );
        }
    }

    /**
     * Opens the connection to the node. Overridable so tests can simulate
     * JDK-level behaviour that cannot be provoked deterministically.
     */
    protected HttpURLConnection openConnection(String url)
            throws IOException {

        return (HttpURLConnection) new URL(url).openConnection();
    }

    /** Result for an attempt Core abandoned because its node went OFFLINE. */
    private TaskResult nodeOffline(
            NodeExecutionTarget target,
            TaskDescriptor task) {

        return new TaskResult(
                task.id(),
                "FAILED",
                "Node "
                        + target.displayName()
                        + " went OFFLINE during push execution; "
                        + "attempt abandoned",
                TaskFailureReason.NODE_OFFLINE
        );
    }

    private static String escape(String value) {
        return value == null
                ? ""
                : value
                        .replace("\\", "\\\\")
                        .replace("\"", "\\\"");
    }

    private static class ResultBody {
        String status;
        String outputData;
    }
}
