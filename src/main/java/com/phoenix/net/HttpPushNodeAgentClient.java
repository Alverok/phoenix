package com.phoenix.net;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import com.google.gson.Gson;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.phoenix.model.NodeExecutionTarget;
import com.phoenix.model.TaskDescriptor;
import com.phoenix.model.TaskFailureReason;
import com.phoenix.model.TaskResult;

/**
 * Push-style transport for directly addressable LAN nodes such as Linux.
 */
@Singleton
public class HttpPushNodeAgentClient
        implements NodeAgentClient {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int EXECUTE_TIMEOUT_MS = 35_000;

    private final RegistrationServer registrationServer;
    private final Gson gson = new Gson();

    @Inject
    public HttpPushNodeAgentClient(
            RegistrationServer registrationServer) {
        this.registrationServer = registrationServer;
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
                        + "\",\"samples\":2000000}";

        System.out.printf(
                "[HttpPushNodeAgentClient] Pushing %s directly to %s (%s)%n",
                task.id(),
                target.displayName(),
                callbackAddress.get()
        );

        try {
            HttpURLConnection conn =
                    (HttpURLConnection) new URL(url).openConnection();

            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(EXECUTE_TIMEOUT_MS);
            conn.setRequestProperty(
                    "Content-Type",
                    "application/json"
            );
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(
                        requestBody.getBytes(
                                StandardCharsets.UTF_8
                        )
                );
            }

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

            if (code != 200) {
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
                return new TaskResult(
                        task.id(),
                        "FAILED",
                        "Node returned an invalid result body",
                        TaskFailureReason.TRANSPORT_ERROR
                );
            }

            return new TaskResult(
                    task.id(),
                    body.status,
                    body.outputData,
                    "SUCCESS".equalsIgnoreCase(body.status)
                            ? TaskFailureReason.NONE
                            : TaskFailureReason.EXECUTION_ERROR
            );

        } catch (IOException exception) {
            return new TaskResult(
                    task.id(),
                    "FAILED",
                    "Push to "
                            + target.displayName()
                            + " failed: "
                            + exception.getMessage(),
                    TaskFailureReason.NODE_UNREACHABLE
            );
        }
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
