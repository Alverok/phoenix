package com.phoenix;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.Scanner;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.phoenix.core.DeviceRegistry;
import com.phoenix.core.PhoenixModule;
import com.phoenix.dispatch.DispatchResult;
import com.phoenix.dispatch.TaskDispatcher;
import com.phoenix.model.NodeRecord;
import com.phoenix.model.TaskDescriptor;
import com.phoenix.net.RegistrationServer;
import com.phoenix.sim.NodeSimulator;

/**
 * Phoenix Core entry point.
 *
 * <p>The Core starts its node-agent HTTP endpoint, boots the simulated
 * baseline fleet, then remains interactive so physical node agents can
 * register and send heartbeats while tasks are submitted manually.
 */
public class App {

    private static final int REGISTRATION_PORT = 8080;

    private static int taskCounter = 1;

    public static void main(String[] args) throws Exception {

        System.out.println("=== Phoenix Core starting ===");

        Injector injector =
                Guice.createInjector(new PhoenixModule());

        RegistrationServer registrationServer =
                injector.getInstance(RegistrationServer.class);

        registrationServer.start(REGISTRATION_PORT);

        String advertisedAddress =
                getAdvertisedAddress();

        System.out.println("=========================================================");
        System.out.println("[Phoenix] Node-agent registration endpoint:");
        System.out.println(
                "          http://"
                        + advertisedAddress
                        + ":"
                        + REGISTRATION_PORT
        );
        System.out.println("          POST /register");
        System.out.println("          POST /heartbeat");
        System.out.println("=========================================================");

        NodeSimulator simulator =
                injector.getInstance(NodeSimulator.class);

        simulator.bootstrapDemoFleet();

        DeviceRegistry registry =
                injector.getInstance(DeviceRegistry.class);

        TaskDispatcher dispatcher =
                injector.getInstance(TaskDispatcher.class);

        printMenu();

        try (Scanner scanner = new Scanner(System.in)) {

            while (true) {

                System.out.print("\nphoenix> ");

                if (!scanner.hasNextLine()) {
                    break;
                }

                String command =
                        scanner.nextLine()
                                .trim()
                                .toLowerCase();

                switch (command) {

                    case "list" ->
                            printNodeSummary(registry);

                    case "dispatch" ->
                            dispatchTask(dispatcher);

                    case "help" ->
                            printMenu();

                    case "quit", "exit" -> {
                        registrationServer.stop();

                        System.out.println(
                                "=== Phoenix Core shutting down ==="
                        );

                        return;
                    }

                    default ->
                            System.out.println(
                                    "Unknown command. Type 'help' for options."
                            );
                }
            }
        } finally {
            registrationServer.stop();
        }
    }

    private static void printMenu() {

        System.out.println();
        System.out.println("Commands:");
        System.out.println(
                "  list     - show registered nodes"
        );
        System.out.println(
                "  dispatch - submit Monte-Carlo-Pi"
        );
        System.out.println(
                "  quit     - stop Phoenix Core"
        );
    }

    private static void printNodeSummary(
            DeviceRegistry registry) {

        System.out.println();
        System.out.println(
                "#########################################################"
        );
        System.out.println(
                "#  PHOENIX NODE SUMMARY                                #"
        );
        System.out.println(
                "#########################################################"
        );

        for (NodeRecord node : registry.list()) {

            System.out.printf(
                    "  %-22s %-9s %-8s %2dc / %5dMB  state=%-9s%n",
                    node.info().displayName(),
                    node.info().hardwareClass(),
                    node.info().adapterType(),
                    node.info().cpuCores(),
                    node.info().memoryMb(),
                    node.state()
            );
        }

        System.out.println(
                "#########################################################"
        );
    }

    private static void dispatchTask(
            TaskDispatcher dispatcher) {

        String taskId =
                "task-"
                        + String.format(
                        "%03d",
                        taskCounter++
                );

        TaskDescriptor task =
                new TaskDescriptor(
                        taskId,
                        "Monte-Carlo-Pi",
                        2,
                        512
                );

        DispatchResult result =
                dispatcher.dispatch(task);

        System.out.printf(
                "[Phoenix] Task complete: node=%s status=%s output=%s%n",
                result.nodeDisplayName(),
                result.result().status(),
                result.result().outputData()
        );
    }

    private static String getAdvertisedAddress() {

        String override =
                System.getProperty("phoenix.advertiseAddress");

        if (override != null && !override.isBlank()) {
            return override;
        }

        try {
            Enumeration<NetworkInterface> interfaces =
                    NetworkInterface.getNetworkInterfaces();

            while (interfaces.hasMoreElements()) {

                NetworkInterface networkInterface =
                        interfaces.nextElement();

                if (!networkInterface.isUp()
                        || networkInterface.isLoopback()) {
                    continue;
                }

                Enumeration<InetAddress> addresses =
                        networkInterface.getInetAddresses();

                while (addresses.hasMoreElements()) {

                    InetAddress address =
                            addresses.nextElement();

                    if (address instanceof Inet4Address
                            && !address.isLoopbackAddress()) {
                        return address.getHostAddress();
                    }
                }
            }

        } catch (Exception ignored) {
        }

        return "127.0.0.1";
    }
}