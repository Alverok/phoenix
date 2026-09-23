package com.phoenix.net;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.Gson;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.phoenix.core.NodeTelemetryService;
import com.phoenix.model.NodeRecord;
import com.phoenix.model.NodeRegistrationRequest;
import com.phoenix.model.NodeStatus;
import com.phoenix.model.ResourceStatus;
import com.phoenix.model.TaskDescriptor;
import com.phoenix.model.TaskResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Core-side HTTP gateway for Phoenix Node Agents.
 *
 * <p>The server intentionally knows about the transport format, but does not
 * perform scheduling or hardware-specific execution, and — per Issue #15 —
 * does not own node lifecycle policy either. It decodes REGISTER/HEARTBEAT
 * messages, extracts node/device identity, and translates the payload into
 * transport-neutral calls on {@link NodeTelemetryService}, which is the
 * single authoritative path into the Phoenix domain model shared by every
 * transport (HTTP today, MQTT/other later). Lifecycle transitions
 * (ONLINE/DEGRADED/OFFLINE) are decided by {@code NodeHealthMonitor}, not
 * here.
 *
 * <p>{@code nodeIdByDeviceId} and {@code callbackAddressByNodeId} remain
 * HTTP-specific: they resolve this transport's device identity and push
 * callback routing, which is out of scope for the Issue #15 lifecycle
 * refactor.
 */
@Singleton
public class RegistrationServer {

    private final NodeTelemetryService nodeTelemetryService;
    private final TaskQueueService taskQueue;
    private final Gson gson = new Gson();

    private final Map<String, String> nodeIdByDeviceId =
            new ConcurrentHashMap<>();

    private final Map<String, String> callbackAddressByNodeId =
        new ConcurrentHashMap<>();

    private HttpServer server;

    @Inject
    public RegistrationServer(
            NodeTelemetryService nodeTelemetryService,
            TaskQueueService taskQueue) {

        this.nodeTelemetryService = nodeTelemetryService;
        this.taskQueue = taskQueue;
    }

    public void start(int port) throws IOException {
        server = HttpServer.create(
                new InetSocketAddress(port),
                0
        );

        server.createContext("/register", this::handleRegister);
        server.createContext("/heartbeat", this::handleHeartbeat);
        server.createContext("/tasks/poll", this::handlePoll);
        server.createContext("/results", this::handleResult);

        // The built-in executor is sufficient for this lightweight prototype.
        server.setExecutor(null);
        server.start();

        System.out.println(
                "[RegistrationServer] Listening for Phoenix Node Agents on port "
                        + port
        );
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    public Optional<String> deviceIdForNode(String nodeId) {
        return nodeIdByDeviceId.entrySet()
                .stream()
                .filter(entry -> entry.getValue().equals(nodeId))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    public Optional<String> callbackAddressForNode(String nodeId) {
        return Optional.ofNullable(
            callbackAddressByNodeId.get(nodeId)
    );
}

    private void handleRegister(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(
                    exchange,
                    405,
                    """
                    {"status":"error","message":"POST required"}
                    """
            );
            return;
        }

        try {
            String body = readBody(exchange);
            DeviceAnnouncement announcement =
                    gson.fromJson(body, DeviceAnnouncement.class);

            validateAnnouncement(announcement);

            if (nodeIdByDeviceId.containsKey(announcement.deviceId)) {
                String existingNodeId =
                        nodeIdByDeviceId.get(announcement.deviceId);

                nodeTelemetryService.get(existingNodeId).ifPresent(record -> {
                    System.out.printf(
                            "[RegistrationServer] Device %s already registered as node %s%n",
                            announcement.deviceId,
                            existingNodeId
                    );
                });

                // ADDED: refresh callback address when an existing device reconnects.
                storeCallbackAddressIfPresent(
                        announcement,
                        existingNodeId,
                        exchange
                );

                sendJson(
                        exchange,
                        200,
                        "{\"status\":\"ok\",\"nodeId\":\""
                                + existingNodeId
                                + "\",\"message\":\"already_registered\"}"
                );
                return;
            }

            ResourceStatus initialStatus = ResourceStatus.of(
                    -1.0,
                    0,
                    0,
                    0,
                    0.0,
                    3.0
            );

            NodeRegistrationRequest request =
                    new NodeRegistrationRequest(
                            null,
                            announcement.displayName,
                            announcement.hardwareClass,
                            announcement.architecture,
                            announcement.cpuCores,
                            announcement.memoryMb,
                            announcement.adapterType,
                            announcement.capabilities != null
                                    ? announcement.capabilities
                                    : Set.of(),
                            initialStatus
                    );

            var nodeInfo = nodeTelemetryService.register(request);

            nodeIdByDeviceId.put(
                    announcement.deviceId,
                    nodeInfo.nodeId()
            );

            // ADDED: remember where this node can receive pushed tasks.
            storeCallbackAddressIfPresent(
                    announcement,
                    nodeInfo.nodeId(),
                    exchange
            );

            String remoteAddress =
                    exchange.getRemoteAddress()
                            .getAddress()
                            .getHostAddress();

            System.out.printf(
                    "[RegistrationServer] Device registered: %s nodeId=%s source=%s%n",
                    announcement.displayName,
                    nodeInfo.nodeId(),
                    remoteAddress
            );

            sendJson(
                    exchange,
                    200,
                    "{\"status\":\"ok\",\"nodeId\":\""
                            + nodeInfo.nodeId()
                            + "\"}"
            );

        } catch (Exception exception) {
            System.out.println(
                    "[RegistrationServer] Registration rejected: "
                            + exception.getMessage()
            );

            sendJson(
                    exchange,
                    400,
                    "{\"status\":\"error\",\"message\":\""
                            + escapeJson(exception.getMessage())
                            + "\"}"
            );
        }
    }

    private void storeCallbackAddressIfPresent(
        DeviceAnnouncement announcement,
        String nodeId,
        HttpExchange exchange) {

    if (announcement.callbackPort == null) {
        return;
    }

    String host =
            exchange.getRemoteAddress()
                    .getAddress()
                    .getHostAddress();

    callbackAddressByNodeId.put(
            nodeId,
            host + ":" + announcement.callbackPort
    );

    System.out.printf(
            "[RegistrationServer] Node %s reachable for push at %s:%d%n",
            nodeId,
            host,
            announcement.callbackPort
    );
}

    private void handleHeartbeat(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(
                    exchange,
                    405,
                    """
                    {"status":"error","message":"POST required"}
                    """
            );
            return;
        }

        try {
            String body = readBody(exchange);

            HeartbeatMessage heartbeat =
                    gson.fromJson(body, HeartbeatMessage.class);

            if (heartbeat.deviceId == null
                    || heartbeat.deviceId.isBlank()) {
                throw new IllegalArgumentException(
                        "deviceId is required"
                );
            }

            String nodeId =
                    nodeIdByDeviceId.get(heartbeat.deviceId);

            if (nodeId == null) {
                sendJson(
                        exchange,
                        404,
                        """
                        {"status":"error","message":"unknown_device"}
                        """
                );
                return;
            }

            // Keep an unknown Android CPU value as unknown (-1) rather than
            // inventing a 50% utilization figure. The cost model handles the
            // unknown signal explicitly.
            double cpuUsage = heartbeat.cpuUsagePercent;

            ResourceStatus status = ResourceStatus.of(
                    cpuUsage,
                    heartbeat.availableRamMb,
                    heartbeat.activeTasks,
                    heartbeat.queueLength,
                    heartbeat.networkLatencyMs,
                    heartbeat.energyCostEstimate
            );

            Optional<NodeRecord> existing =
                    nodeTelemetryService.get(nodeId);

            if (existing.isEmpty()) {
                nodeIdByDeviceId.remove(heartbeat.deviceId);
                sendJson(
                        exchange,
                        404,
                        """
                        {"status":"error","message":"node_not_found"}
                        """
                );
                return;
            }

            NodeStatus previousState =
                    existing.get().state();

            nodeTelemetryService.reportHeartbeat(
                    nodeId,
                    status
            );

            if (previousState == NodeStatus.DEGRADED
                    || previousState == NodeStatus.OFFLINE) {

                System.out.printf(
                        "[RegistrationServer] Heartbeat resumed from node %s -> ONLINE%n",
                        nodeId
                );
            }

            System.out.printf(
                    "[RegistrationServer] Heartbeat from %s: cpu=%s freeRam=%dMB%n",
                    heartbeat.deviceId,
                    heartbeat.cpuUsagePercent >= 0
                            ? String.format(
                                    "%.1f%%",
                                    heartbeat.cpuUsagePercent
                            )
                            : "N/A",
                    heartbeat.availableRamMb
            );

            sendJson(
                    exchange,
                    200,
                    "{\"status\":\"ok\",\"nodeId\":\""
                            + nodeId
                            + "\"}"
            );

        } catch (Exception exception) {
            System.out.println(
                    "[RegistrationServer] Heartbeat rejected: "
                            + exception.getMessage()
            );

            sendJson(
                    exchange,
                    400,
                    "{\"status\":\"error\",\"message\":\""
                            + escapeJson(exception.getMessage())
                            + "\"}"
            );
        }
    }

    /**
     * Poll for the next task assigned to a specific device.
     *
     * GET /tasks/poll?deviceId=<device-id>
     *
     * Returns:
     *   200 + TaskDescriptor when a task exists
     *   204 when the queue is empty
     *   400 when deviceId is missing
     *   404 when deviceId is unknown
     */
    private void handlePoll(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
        }

        try {
                String query = exchange.getRequestURI().getQuery();
                String deviceId = extractQueryParameter(query, "deviceId");

                if (deviceId == null || deviceId.isBlank()) {
                sendJson(exchange, 400, "{\"status\":\"error\",\"message\":\"deviceId is required\"}");
                return;
                }

                String nodeId = nodeIdByDeviceId.get(deviceId);
                if (nodeId == null) {
                sendJson(exchange, 404, "{\"status\":\"error\",\"message\":\"unknown_device\"}");
                return;
                }

                Optional<NodeRecord> record = nodeTelemetryService.get(nodeId);
                if (record.isEmpty() || record.get().state() != NodeStatus.ONLINE) {
                exchange.sendResponseHeaders(204, -1);
                return;
                }

                Optional<TaskDescriptor> task = taskQueue.poll(deviceId);
                if (task.isEmpty()) {
                exchange.sendResponseHeaders(204, -1);
                return;
                }

                TaskDescriptor descriptor = task.get();
                // Hand-built JSON — sidesteps any Gson record-serialization surprises
                // and matches the documented protocol shape exactly (id/type/samples).
                String json = String.format(
                        "{\"taskId\":\"%s\",\"type\":\"%s\",\"samples\":2000000}",
                        escapeJson(descriptor.id()), escapeJson(descriptor.type()));

                sendJson(exchange, 200, json);

        } catch (Exception e) {
                System.out.println("[RegistrationServer] /tasks/poll crashed: " + e);
                e.printStackTrace();
                sendJson(exchange, 500, "{\"status\":\"error\",\"message\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
        }

    /**
     * Extract a single URL query parameter.
     */
    private static String extractQueryParameter(
            String query,
            String requestedName) {

        if (query == null || query.isBlank()) {
            return null;
        }

        for (String part : query.split("&")) {
            String[] pair = part.split("=", 2);

            if (pair.length != 2) {
                continue;
            }

            if (pair[0].equals(requestedName)) {
                return URLDecoder.decode(
                        pair[1],
                        StandardCharsets.UTF_8
                );
            }
        }

        return null;
    }

    /**
     * Receive a completed task result from a Node Agent.
     *
     * POST /results
     */
    private void handleResult(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        try {
            String body = readBody(exchange);

            ResultBody resultBody =
                    gson.fromJson(body, ResultBody.class);

            if (resultBody == null
                    || resultBody.taskId == null
                    || resultBody.status == null) {

                sendJson(
                        exchange,
                        400,
                        """
                        {"status":"error","message":"invalid_result"}
                        """
                );
                return;
            }

            TaskResult result = new TaskResult(
                    resultBody.taskId,
                    resultBody.status,
                    resultBody.outputData
            );

            boolean matched =
                    taskQueue.completeResult(result);

            if (!matched) {
                sendJson(
                        exchange,
                        404,
                        """
                        {"status":"error","message":"unknown_task"}
                        """
                );
                return;
            }

            System.out.printf(
                    "[RegistrationServer] Result received: task=%s status=%s%n",
                    result.taskId(),
                    result.status()
            );

            sendJson(
                    exchange,
                    200,
                    """
                    {"status":"ok"}
                    """
            );

        } catch (Exception exception) {
            sendJson(
                    exchange,
                    400,
                    "{\"status\":\"error\",\"message\":\""
                            + escapeJson(exception.getMessage())
                            + "\"}"
            );
        }
    }

    /**
     * Transport representation of a result received from a Node Agent.
     *
     * executionTimeMs is accepted from the HTTP payload for forward
     * compatibility, but is not currently part of TaskResult.
     */
    private static class ResultBody {
        String taskId;
        String status;
        String outputData;
        long executionTimeMs;
    }

    private static String readBody(
            HttpExchange exchange) throws IOException {

        try (var inputStream = exchange.getRequestBody()) {
            return new String(
                    inputStream.readAllBytes(),
                    StandardCharsets.UTF_8
            );
        }
    }

    private static void sendJson(
            HttpExchange exchange,
            int statusCode,
            String body) throws IOException {

        byte[] bytes =
                body.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders()
                .set("Content-Type", "application/json");

        exchange.sendResponseHeaders(
                statusCode,
                bytes.length
        );

        try (OutputStream outputStream =
                     exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }

    private static void validateAnnouncement(
            DeviceAnnouncement announcement) {

        if (announcement == null) {
            throw new IllegalArgumentException(
                    "request body is required"
            );
        }

        if (announcement.deviceId == null
                || announcement.deviceId.isBlank()) {
            throw new IllegalArgumentException(
                    "deviceId is required"
            );
        }

        if (announcement.displayName == null
                || announcement.displayName.isBlank()) {
            throw new IllegalArgumentException(
                    "displayName is required"
            );
        }

        if (announcement.hardwareClass == null) {
            throw new IllegalArgumentException(
                    "hardwareClass is required"
            );
        }

        if (announcement.cpuCores <= 0) {
            throw new IllegalArgumentException(
                    "cpuCores must be greater than zero"
            );
        }

        if (announcement.memoryMb <= 0) {
            throw new IllegalArgumentException(
                    "memoryMb must be greater than zero"
            );
        }
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }

        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    public static class HeartbeatMessage {

        public String deviceId;
        public double cpuUsagePercent;
        public long availableRamMb;
        public int activeTasks;
        public int queueLength;
        public double networkLatencyMs;
        public double energyCostEstimate;
    }
}
