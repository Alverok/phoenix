package com.phoenix;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.Scanner;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.phoenix.adapter.IHardwareAdapter;
import com.phoenix.core.AdapterRegistry;
import com.phoenix.core.DeviceRegistry;
import com.phoenix.core.PhoenixModule;
import com.phoenix.dispatch.DispatchResult;
import com.phoenix.dispatch.TaskDispatcher;
import com.phoenix.model.NodeRecord;
import com.phoenix.model.TaskDescriptor;
import com.phoenix.net.RegistrationServer;
import com.phoenix.sim.NodeSimulator;

/**
 * Mid-review update: Phoenix Core now stays up and interactive instead of
 * running one fixed dispatch and exiting. Devices (real or simulated) can
 * join at any point; type commands at the console to inspect the live
 * fleet and submit tasks on demand, so a demo can show "connect phone now,
 * dispatch task now, watch it get picked" in real time rather than a
 * pre-baked sequence.
 */
public class App {

    private static final int REGISTRATION_PORT = 8080;
    private static int taskCounter = 1;

    public static void main(String[] args) throws Exception {
        System.out.println("=== Phoenix Core starting ===");

        Injector injector = Guice.createInjector(new PhoenixModule());

        RegistrationServer registrationServer = injector.getInstance(RegistrationServer.class);
        registrationServer.start(REGISTRATION_PORT);

        String ip = getLocalIpAddress();
        System.out.println("=========================================================");
        System.out.println("[Phoenix] Real-device registration endpoint:");
        System.out.println("          http://" + ip + ":" + REGISTRATION_PORT + "/register");
        System.out.println("[Phoenix] Devices can connect at any time from here on.");
        System.out.println("=========================================================");

        // Simulated baseline fleet registers immediately.
        NodeSimulator simulator = injector.getInstance(NodeSimulator.class);
        simulator.bootstrapDemoFleet();

        DeviceRegistry registry = injector.getInstance(DeviceRegistry.class);
        AdapterRegistry adapterRegistry = injector.getInstance(AdapterRegistry.class);
        TaskDispatcher dispatcher = injector.getInstance(TaskDispatcher.class);

        printMenu();
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("\nphoenix> ");
            String cmd = scanner.nextLine().trim().toLowerCase();

            switch (cmd) {
                case "list" -> printNodeSummary(registry, registrationServer);
                case "dispatch" -> {
                    refreshLinuxHeartbeat(registry, adapterRegistry);
                    runDispatch(dispatcher);
                }
                case "quit", "exit" -> {
                    registrationServer.stop();
                    System.out.println("=== Phoenix Core shutting down ===");
                    return;
                }
                case "help" -> printMenu();
                default -> System.out.println("Unknown command. Type 'help' for options.");
            }
        }
    }

    private static void printMenu() {
        System.out.println();
        System.out.println("Commands:");
        System.out.println("  list      - show all currently registered nodes (live + simulated)");
        System.out.println("  dispatch  - submit a new Monte-Carlo-Pi task and watch it get placed");
        System.out.println("  quit      - stop Phoenix Core");
    }

    private static void printNodeSummary(DeviceRegistry registry, RegistrationServer registrationServer) {
        System.out.println();
        System.out.println("#########################################################");
        System.out.println("#  PHOENIX NODE SUMMARY                                #");
        System.out.println("#########################################################");
        for (NodeRecord node : registry.list()) {
            String tag;
            if (registrationServer.getLiveNodeIds().contains(node.nodeId())) {
                tag = "[LIVE - NETWORK DEVICE] state=" + node.state();
            } else if ("Linux-01".equals(node.info().displayName())) {
                tag = "[LIVE - LOCAL HOST]";
            } else {
                tag = "[SIMULATED - MOCK DATA]";
            }
            System.out.printf("  %-22s %-9s %-8s %2dc / %5dMB   %s%n",
                    node.info().displayName(),
                    node.info().hardwareClass(),
                    node.info().adapterType(),
                    node.info().cpuCores(),
                    node.info().memoryMb(),
                    tag);
        }
        System.out.println("#########################################################");
    }

    private static void refreshLinuxHeartbeat(DeviceRegistry registry, AdapterRegistry adapterRegistry) {
        String linuxNodeId = registry.list().stream()
                .filter(n -> "Linux-01".equals(n.info().displayName()))
                .map(NodeRecord::nodeId)
                .findFirst()
                .orElse(null);
        if (linuxNodeId != null) {
            IHardwareAdapter linuxAdapter = adapterRegistry.resolve("linux");
            registry.updateStatus(linuxNodeId, linuxAdapter.getResourceStatus());
        }
    }

    private static void runDispatch(TaskDispatcher dispatcher) {
        String taskId = "task-" + String.format("%03d", taskCounter++);
        TaskDescriptor task = new TaskDescriptor(taskId, "Monte-Carlo-Pi", 2, 512);

        DispatchResult result = dispatcher.dispatch(task);

        System.out.printf(
                "[Phoenix] Task complete: node=%s status=%s output=%s%n",
                result.nodeDisplayName(),
                result.result().status(),
                result.result().outputData());
    }

    private static String getLocalIpAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr.getAddress().length == 4 && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "127.0.0.1";
    }
}