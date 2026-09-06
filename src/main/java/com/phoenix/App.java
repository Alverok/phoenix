package com.phoenix;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

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
 * Day 2 vertical slice: Phoenix Core now orchestrates across a small
 * multi-node mesh instead of talking to one hand-picked adapter.
 *
 * <pre>
 * Day 1: App -&gt; IHardwareAdapter -&gt; execute
 * Day 2: App -&gt; TaskDispatcher -&gt; Scheduler -&gt; DeviceRegistry -&gt; AdapterRegistry -&gt; IHardwareAdapter -&gt; execute
 * </pre>
 *
 * <p>
 * Mid-review update: Phoenix Core now opens a real {@link RegistrationServer}
 * on {@code REGISTRATION_PORT} so genuine external devices (a phone via
 * Termux, another Linux box, etc.) can announce themselves over HTTP,
 * exactly matching the "today simulated in-process, later arrives over
 * MQTT/REST" note in {@link NodeSimulator}'s javadoc. The simulated fleet
 * still registers immediately for baseline heterogeneity comparisons, but
 * real devices that connect during the wait window are tagged and called
 * out distinctly in the final summary and in a live console banner
 * (see {@link RegistrationServer#printLiveDeviceBanner}, called internally
 * on each successful real registration).
 *
 * <p>
 * Also refreshes Linux-01's live {@link com.phoenix.model.ResourceStatus}
 * right before dispatch (a mini heartbeat), so the demo visibly shows real
 * telemetry changing between boot-time registration and dispatch-time
 * rather than a frozen mock value.
 */
public class App {

    private static final int REGISTRATION_PORT = 8080;
    private static final int WAIT_FOR_REAL_DEVICES_MS = 15_000;

    public static void main(String[] args) throws Exception {
        System.out.println("=== Phoenix Core starting ===");

        Injector injector = Guice.createInjector(new PhoenixModule());

        // Open the REST registration endpoint so real devices can announce
        // themselves over the network, not just via the in-process simulator.
        RegistrationServer registrationServer = injector.getInstance(RegistrationServer.class);
        registrationServer.start(REGISTRATION_PORT);

        String ip = getLocalIpAddress();
        System.out.println("=========================================================");
        System.out.println("[Phoenix] Real-device registration endpoint:");
        System.out.println("          http://" + ip + ":" + REGISTRATION_PORT + "/register");
        System.out.println("[Phoenix] Waiting " + (WAIT_FOR_REAL_DEVICES_MS / 1000)
                + "s for real devices to announce themselves...");
        System.out.println("=========================================================");

        // Simulated fleet still registers immediately, same as before.
        NodeSimulator simulator = injector.getInstance(NodeSimulator.class);
        simulator.bootstrapDemoFleet();

        // Give a real phone/laptop time to curl in during this window.
        Thread.sleep(WAIT_FOR_REAL_DEVICES_MS);

        DeviceRegistry registry = injector.getInstance(DeviceRegistry.class);

        System.out.println();
        System.out.println("#########################################################");
        System.out.println("#  PHOENIX NODE SUMMARY                                #");
        System.out.println("#########################################################");
        for (NodeRecord node : registry.list()) {
            String tag;
            if (registrationServer.getLiveNodeIds().contains(node.nodeId())) {
                tag = "[LIVE - NETWORK DEVICE]";
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
        System.out.println();

        // --- Heartbeat demo: refresh Linux-01's live status right before
        // dispatch, so the console visibly shows real telemetry changing
        // between boot-time registration and dispatch-time.
        String linuxNodeId = findLinuxOneNodeId(registry);
        if (linuxNodeId != null) {
            AdapterRegistry adapterRegistry = injector.getInstance(AdapterRegistry.class);
            IHardwareAdapter linuxAdapter = adapterRegistry.resolve("linux");

            System.out.println("[Heartbeat] Refreshing live status for Linux-01 before dispatch...");
            registry.updateStatus(linuxNodeId, linuxAdapter.getResourceStatus());
            System.out.println("[Heartbeat] Linux-01 status refreshed: "
                    + registry.get(linuxNodeId).orElseThrow().status());
        }

        TaskDescriptor task = new TaskDescriptor("task-001", "Monte-Carlo-Pi", 2, 512);
        TaskDispatcher dispatcher = injector.getInstance(TaskDispatcher.class);
        DispatchResult dispatchResult = dispatcher.dispatch(task);

        System.out.printf(
                "[Phoenix] Task complete: node=%s status=%s output=%s%n",
                dispatchResult.nodeDisplayName(),
                dispatchResult.result().status(),
                dispatchResult.result().outputData());

        registrationServer.stop();
        System.out.println("=== Phoenix Core shutting down ===");
    }

    /** Finds the nodeId of the node registered with displayName "Linux-01". */
    private static String findLinuxOneNodeId(DeviceRegistry registry) {
        return registry.list().stream()
                .filter(n -> "Linux-01".equals(n.info().displayName()))
                .map(NodeRecord::nodeId)
                .findFirst()
                .orElse(null);
    }

    /**
     * Finds this machine's LAN IPv4 address so it can be printed for other devices
     * to dial into.
     */
    private static String getLocalIpAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp())
                    continue;
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr.getAddress().length == 4 && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return "127.0.0.1";
    }
}