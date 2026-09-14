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
import com.phoenix.model.TaskResult;

/**
 * Push-style transport: Core makes a direct outbound call to the node's own
 * listening agent and gets the TaskResult back as the HTTP response body,
 * synchronously — the open connection is the correlation, no queue needed.
 * Only viable for directly-addressable nodes (Linux on the LAN); Android
 * uses HttpNodeAgentClient (pull) instead because phones behind NAT usually
 * can't accept inbound connections. Bound under @PushTransport.
 */
@Singleton
public class HttpPushNodeAgentClient implements NodeAgentClient {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int EXECUTE_TIMEOUT_MS = 35_000;

    private final RegistrationServer registrationServer;
    private final Gson gson = new Gson();

    @Inject
    public HttpPushNodeAgentClient(RegistrationServer registrationServer) {
        this.registrationServer = registrationServer;
    }

    @Override
    public TaskResult execute(NodeExecutionTarget target, TaskDescriptor task) {
        var callbackAddress = registrationServer.callbackAddressForNode(target.nodeId());
        if (callbackAddress.isEmpty()) {
            return new TaskResult(task.id(), "FAILED",
                    "Node " + target.displayName() + " has no registered callback address");
        }

        String url = "http://" + callbackAddress.get() + "/execute";
        String requestBody = "{\"taskId\":\"" + escape(task.id()) + "\",\"type\":\"" + escape(task.type()) + "\",\"samples\":2000000}";

        System.out.printf("[HttpPushNodeAgentClient] Pushing %s directly to %s (%s)%n",
                task.id(), target.displayName(), callbackAddress.get());

        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(EXECUTE_TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBody.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            var stream = code == 200 ? conn.getInputStream() : conn.getErrorStream();
            String response = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

            if (code != 200) {
                return new TaskResult(task.id(), "FAILED", "Node returned HTTP " + code + ": " + response);
            }
            ResultBody body = gson.fromJson(response, ResultBody.class);
            return new TaskResult(task.id(), body.status, body.outputData);

        } catch (IOException e) {
            return new TaskResult(task.id(), "FAILED", "Push to " + target.displayName() + " failed: " + e.getMessage());
        }
    }

    private static String escape(String v) {
        return v == null ? "" : v.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static class ResultBody {
        String status;
        String outputData;
    }
}