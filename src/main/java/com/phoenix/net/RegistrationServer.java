package com.phoenix.net;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.phoenix.core.DeviceRegistry;
import com.phoenix.model.HardwareClass;
import com.phoenix.model.NodeInfo;
import com.phoenix.model.ResourceStatus;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

@Singleton
public class RegistrationServer {

    private final DeviceRegistry deviceRegistry;
    private final Gson gson = new Gson();
    private HttpServer server;

    // NEW: tracks nodeIds that registered over the real network (not the simulator),
    // so App.java can show a distinct "live devices" summary at the end.
    private final Set<String> liveNodeIds = new LinkedHashSet<>();

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

            NodeInfo info = new NodeInfo(
                    UUID.randomUUID().toString(),
                    a.displayName,
                    HardwareClass.valueOf(a.hardwareClass.toUpperCase()),
                    a.architecture,
                    a.cpuCores,
                    a.memoryMb,
                    a.adapterType,
                    Set.of());

            ResourceStatus status = ResourceStatus.of(
                    a.cpuUsagePercent, a.availableRamMb, 0, 0,
                    a.networkLatencyMs, a.energyCostEstimate);

            deviceRegistry.register(info, status);
            liveNodeIds.add(info.nodeId());

            String remoteAddr = exchange.getRemoteAddress().getAddress().getHostAddress();
            printLiveDeviceBanner(info, status, remoteAddr);

            response = "{\"status\":\"registered\",\"nodeId\":\"" + info.nodeId() + "\"}";
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

    /** Prints a prominent, boxed banner so a real device connecting stands out from the rest of the log. */
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
        System.out.printf("  Network Latency: %.1f ms%n", status.networkLatencyMs());
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