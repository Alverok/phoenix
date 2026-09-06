package com.phoenix.net;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.Gson;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.phoenix.core.DeviceRegistry;
import com.phoenix.model.HardwareClass;
import com.phoenix.model.NodeInfo;
import com.phoenix.model.NodeRecord;
import com.phoenix.model.ResourceStatus;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * The REST half of the self-registration protocol. A real device (phone,
 * another Linux box) POSTs a JSON heartbeat repeatedly (every few seconds)
 * to /register. First POST creates the node; every subsequent POST from the
 * same displayName is treated as a heartbeat/status refresh on the existing
 * node rather than a brand-new registration — this is what makes the
 * connection "persistent" in the sense that matters: the node's live data
 * keeps updating in the registry for as long as the device keeps sending,
 * and is flagged stale if it stops.
 */
@Singleton
public class RegistrationServer {

    private static final long STALE_AFTER_MS = 20_000; // no heartbeat in 20s -> DEGRADED

    private final DeviceRegistry deviceRegistry;
    private final Gson gson = new Gson();
    private HttpServer server;

    // Tracks nodeIds that arrived over the real network (vs. the in-process simulator).
    private final Set<String> liveNodeIds = ConcurrentHashMap.newKeySet();
    // Tracks last-seen time per node, purely for the staleness monitor.
    private final java.util.Map<String, Instant> lastSeen = new ConcurrentHashMap<>();

    @Inject
    public RegistrationServer(DeviceRegistry deviceRegistry) {
        this.deviceRegistry = deviceRegistry;
    }

    public void start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/register", this::handleRegister);
        server.setExecutor(null);
        server.start();
        System.out.println("[RegistrationServer] Listening for real device registrations on port " + port);

        // Background staleness monitor: marks a live device DEGRADED if it
        // hasn't sent a heartbeat in STALE_AFTER_MS. Only touches nodes that
        // arrived over the network — simulated nodes never heartbeat and
        // shouldn't be penalized for it.
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "heartbeat-monitor");
            t.setDaemon(true);
            return t;
        }).scheduleAtFixedRate(this::checkStaleness, 5, 5, java.util.concurrent.TimeUnit.SECONDS);
    }

    private void checkStaleness() {
        Instant now = Instant.now();
        for (String nodeId : liveNodeIds) {
            Instant seen = lastSeen.get(nodeId);
            if (seen == null) continue;
            long ageMs = now.toEpochMilli() - seen.toEpochMilli();
            deviceRegistry.get(nodeId).ifPresent(record -> {
                if (ageMs > STALE_AFTER_MS && record.state() == com.phoenix.model.NodeStatus.ONLINE) {
                    System.out.printf("[RegistrationServer] No heartbeat from %s in %ds -> marking DEGRADED%n",
                            record.info().displayName(), ageMs / 1000);
                    deviceRegistry.markDegraded(nodeId);
                }
            });
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** NodeIds that registered over the real network, in registration order. */
    public Set<String> getLiveNodeIds() {
        return liveNodeIds;
    }

    private void handleRegister(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        String body;
        try (var is = exchange.getRequestBody()) {
            body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        String response;
        int statusCode;
        try {
            DeviceAnnouncement a = gson.fromJson(body, DeviceAnnouncement.class);
            String remoteAddr = exchange.getRemoteAddress().getAddress().getHostAddress();

            ResourceStatus status = ResourceStatus.of(
                    a.cpuUsagePercent, a.availableRamMb, 0, 0,
                    a.networkLatencyMs, a.energyCostEstimate);

            Optional<NodeRecord> existing = deviceRegistry.findByDisplayName(a.displayName);

            String nodeId;
            if (existing.isPresent()) {
                // Heartbeat on an already-known device: refresh live status only.
                nodeId = existing.get().nodeId();
                deviceRegistry.updateStatus(nodeId, status);
                System.out.printf("[RegistrationServer] Heartbeat from %s (%s): cpu=%.1f%% freeRam=%dMB%n",
                        a.displayName, remoteAddr, a.cpuUsagePercent, a.availableRamMb);
            } else {
                // First time seeing this displayName: real registration.
                NodeInfo info = new NodeInfo(
                        UUID.randomUUID().toString(),
                        a.displayName,
                        HardwareClass.valueOf(a.hardwareClass.toUpperCase()),
                        a.architecture,
                        a.cpuCores,
                        a.memoryMb,
                        a.adapterType,
                        Set.of());

                deviceRegistry.register(info, status);
                nodeId = info.nodeId();
                liveNodeIds.add(nodeId);
                printLiveDeviceBanner(info, status, remoteAddr);
            }

            lastSeen.put(nodeId, Instant.now());
            response = "{\"status\":\"ok\",\"nodeId\":\"" + nodeId + "\"}";
            statusCode = 200;
        } catch (Exception e) {
            System.out.println("[RegistrationServer] Rejected bad registration: " + e.getMessage());
            response = "{\"status\":\"error\",\"message\":\"" + e.getMessage() + "\"}";
            statusCode = 400;
        }

        exchange.getResponseHeaders().add("Content-Type", "application/json");
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void printLiveDeviceBanner(NodeInfo info, ResourceStatus status, String remoteAddr) {
        String border = "=".repeat(65);
        System.out.println();
        System.out.println(border);
        System.out.println("  >>> LIVE DEVICE CONNECTED OVER NETWORK <<<");
        System.out.println(border);
        System.out.printf("  Name           : %s%n", info.displayName());
        System.out.printf("  Node ID        : %s%n", info.nodeId());
        System.out.printf("  Source IP      : %s%n", remoteAddr);
        System.out.printf("  Hardware Class : %s%n", info.hardwareClass());
        System.out.printf("  Architecture   : %s%n", info.architecture());
        System.out.printf("  CPU Cores      : %d%n", info.cpuCores());
        System.out.printf("  Total Memory   : %d MB%n", info.memoryMb());
        System.out.printf("  Free Memory    : %d MB%n", status.availableRamMb());
        System.out.printf("  CPU Usage      : %.1f%%%n", status.cpuUsagePercent());
        System.out.printf("  Registered At  : %s%n", status.observedAt());
        System.out.println(border);
        System.out.println();
    }

    private static class DeviceAnnouncement {
        String displayName;
        String hardwareClass;
        String architecture;
        int cpuCores;
        long memoryMb;
        String adapterType;
        double cpuUsagePercent;
        long availableRamMb;
        double networkLatencyMs;
        double energyCostEstimate;
    }
}