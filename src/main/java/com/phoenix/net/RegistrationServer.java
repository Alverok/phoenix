package com.phoenix.net;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
 * same deviceId is treated as a heartbeat/status refresh on the existing
 * node rather than a brand-new registration.
 *
 * <p>Review-eve hardening pass: three fixes on top of the original prototype.
 * <ol>
 *   <li>Identity keys off {@code deviceId} (a value the agent generates once
 *       and persists locally), not {@code displayName}. Two phones with the
 *       same model name no longer collide into one NodeRecord. Falls back to
 *       displayName only if an older agent build doesn't send deviceId, so
 *       this stays backward compatible.</li>
 *   <li>A heartbeat now recovers a DEGRADED node back to ONLINE (previously
 *       only {@link DeviceRegistry#updateHeartbeat} did that; the heartbeat
 *       path here was calling {@link DeviceRegistry#updateStatus} instead,
 *       which refreshes data but never flips state back).</li>
 *   <li>{@code cpuUsagePercent < 0} is treated as "device couldn't measure
 *       it" (Termux/Android commonly denies /proc/stat) rather than passed
 *       through as-is. This matters beyond display: {@code
 *       WeightedPlacementCostModel} computes {@code freeCapacityFraction =
 *       1 - cpuUsagePercent/100} with no upper clamp, so an unmeasured value
 *       reported as 0.0 (or worse, a negative sentinel) makes the scheduler
 *       see the node as more idle than any real measurement could show —
 *       i.e. missing telemetry was silently winning placement decisions.
 *       We substitute a neutral 50% for scheduling purposes and say so in
 *       the banner, rather than fabricate an extreme in either direction.</li>
 * </ol>
 */
@Singleton
public class RegistrationServer {

    private static final long STALE_AFTER_MS = 20_000; // no heartbeat in 20s -> DEGRADED
    private static final double NEUTRAL_CPU_FALLBACK = 50.0;

    private final DeviceRegistry deviceRegistry;
    private final Gson gson = new Gson();
    private HttpServer server;

    private final Set<String> liveNodeIds = ConcurrentHashMap.newKeySet();
    private final java.util.Map<String, Instant> lastSeen = new ConcurrentHashMap<>();

    // deviceId -> nodeId, so repeat announcements from the same physical device upsert
    // instead of creating a new NodeRecord per POST.
    private final java.util.Map<String, String> nodeIdByDeviceKey = new ConcurrentHashMap<>();

    @Inject
    public RegistrationServer(DeviceRegistry deviceRegistry) {
        this.deviceRegistry = deviceRegistry;
    }

    public void start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/register", this::handleRegister);
        server.setExecutor(null);
        server.start();

        System.out.println(
                "[RegistrationServer] Listening for real device registrations on port "
                        + port);

        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "heartbeat-monitor");
            t.setDaemon(true);
            return t;
        }).scheduleAtFixedRate(
                this::checkStaleness,
                5,
                5,
                java.util.concurrent.TimeUnit.SECONDS);
    }

    private void checkStaleness() {
        Instant now = Instant.now();

        for (String nodeId : liveNodeIds) {
            Instant seen = lastSeen.get(nodeId);

            if (seen == null) {
                continue;
            }

            long ageMs = now.toEpochMilli() - seen.toEpochMilli();

            deviceRegistry.get(nodeId).ifPresent(record -> {
                if (ageMs > STALE_AFTER_MS
                        && record.state() == com.phoenix.model.NodeStatus.ONLINE) {

                    System.out.printf(
                            "[RegistrationServer] No heartbeat from %s in %ds -> marking DEGRADED%n",
                            record.info().displayName(),
                            ageMs / 1000);

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
            DeviceAnnouncement a =
                    gson.fromJson(body, DeviceAnnouncement.class);

            String remoteAddr =
                    exchange.getRemoteAddress().getAddress().getHostAddress();

            // Key on deviceId when the agent sends one; fall back to displayName
            // for backward compatibility with older agent builds.
            String deviceKey =
                    (a.deviceId != null && !a.deviceId.isBlank())
                            ? a.deviceId
                            : a.displayName;

            boolean cpuMeasured = a.cpuUsagePercent >= 0;

            double effectiveCpu =
                    cpuMeasured
                            ? a.cpuUsagePercent
                            : NEUTRAL_CPU_FALLBACK;

            ResourceStatus status = ResourceStatus.of(
                    effectiveCpu,
                    a.availableRamMb,
                    0,
                    0,
                    a.networkLatencyMs,
                    a.energyCostEstimate);

            String existingNodeId =
                    nodeIdByDeviceKey.get(deviceKey);

            Optional<NodeRecord> existing =
                    existingNodeId != null
                            ? deviceRegistry.get(existingNodeId)
                            : deviceRegistry.findByDisplayName(a.displayName);
            // legacy-agent fallback path

            String nodeId;

            if (existing.isPresent()) {
                nodeId = existing.get().nodeId();

                NodeRecord record = existing.get();

                boolean wasDegraded =
                        record.state() == com.phoenix.model.NodeStatus.DEGRADED;

                deviceRegistry.updateStatus(nodeId, status);
                deviceRegistry.updateHeartbeat(nodeId);

                nodeIdByDeviceKey.putIfAbsent(deviceKey, nodeId);

                System.out.printf(
                        "[RegistrationServer] Heartbeat from %s (%s): cpu=%s freeRam=%dMB%n",
                        a.displayName,
                        remoteAddr,
                        cpuMeasured
                                ? String.format("%.1f%%", a.cpuUsagePercent)
                                : "N/A (assumed 50% for scheduling)",
                        a.availableRamMb);

                if (wasDegraded) {
                    System.out.printf(
                            "[RegistrationServer] Heartbeat resumed from %s -> ONLINE%n",
                            a.displayName);
                }
            } else {
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
                nodeIdByDeviceKey.put(deviceKey, nodeId);

                printLiveDeviceBanner(
                        info,
                        status,
                        remoteAddr,
                        cpuMeasured);
            }

            lastSeen.put(nodeId, Instant.now());

            response =
                    "{\"status\":\"ok\",\"nodeId\":\"" + nodeId + "\"}";

            statusCode = 200;

        } catch (Exception e) {
            System.out.println(
                    "[RegistrationServer] Rejected bad registration: "
                            + e.getMessage());

            response =
                    "{\"status\":\"error\",\"message\":\""
                            + e.getMessage()
                            + "\"}";

            statusCode = 400;
        }

        exchange.getResponseHeaders().add(
                "Content-Type",
                "application/json");

        byte[] bytes =
                response.getBytes(StandardCharsets.UTF_8);

        exchange.sendResponseHeaders(
                statusCode,
                bytes.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void printLiveDeviceBanner(
            NodeInfo info,
            ResourceStatus status,
            String remoteAddr,
            boolean cpuMeasured) {

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

        System.out.printf(
                "  CPU Usage      : %s%n",
                cpuMeasured
                        ? String.format("%.1f%%", status.cpuUsagePercent())
                        : "N/A (platform restricts /proc/stat; assumed 50% for scheduling)");

        System.out.printf(
                "  Registered At  : %s%n",
                status.observedAt());

        System.out.println(border);
        System.out.println();
    }

    private static class DeviceAnnouncement {

        String deviceId;
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
