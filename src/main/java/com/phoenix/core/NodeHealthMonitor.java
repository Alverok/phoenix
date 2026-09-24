package com.phoenix.core;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.phoenix.model.NodeRecord;
import com.phoenix.model.NodeStatus;

/**
 * Owns transport-independent node lifecycle policy.
 *
 * <p>The monitor is responsible for deciding when a node is:
 *
 * <ul>
 *     <li>ONLINE</li>
 *     <li>DEGRADED</li>
 *     <li>OFFLINE</li>
 * </ul>
 *
 * <p>Heartbeat and resource data are stored in {@link DeviceRegistry}.
 * This class reads that authoritative state and applies lifecycle
 * transitions according to heartbeat freshness.
 *
 * <p>No transport-specific code belongs here. HTTP, MQTT, or any future
 * transport only needs to feed heartbeat data into the common telemetry path.
 */
@Singleton
public class NodeHealthMonitor {

    static final long DEGRADED_AFTER_MS = 20_000;
    static final long OFFLINE_AFTER_MS = 60_000;

    private static final long CHECK_INTERVAL_SECONDS = 5;

    private final DeviceRegistry deviceRegistry;

    private ScheduledExecutorService executor;

    @Inject
    public NodeHealthMonitor(DeviceRegistry deviceRegistry) {
        this.deviceRegistry = deviceRegistry;
    }

    /**
     * Start periodic lifecycle evaluation.
     */
    public synchronized void start() {
        if (executor != null && !executor.isShutdown()) {
            return;
        }

        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(
                    runnable,
                    "phoenix-health-monitor"
            );

            thread.setDaemon(true);
            return thread;
        });

        executor.scheduleAtFixedRate(
                this::checkStaleness,
                CHECK_INTERVAL_SECONDS,
                CHECK_INTERVAL_SECONDS,
                TimeUnit.SECONDS
        );
    }

    /**
     * Stop periodic lifecycle evaluation.
     */
    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    /**
     * Handle a heartbeat received from a node.
     *
     * <p>The telemetry path records the heartbeat timestamp first.
     * This method applies the lifecycle policy that a reachable node
     * is ONLINE again.
     */
    public void onHeartbeat(String nodeId) {
        deviceRegistry.get(nodeId).ifPresent(record -> {
            if (record.state() != NodeStatus.ONLINE) {
                deviceRegistry.markOnline(nodeId);
            }
        });
    }

    /**
     * Evaluate every registered node against the current wall-clock time.
     */
    public void checkStaleness() {
        try {
            evaluate(Instant.now());
        } catch (RuntimeException exception) {
            System.err.println(
                    "[NodeHealthMonitor] Staleness check failed: "
                            + exception.getMessage()
            );
        }
    }

    /**
     * Evaluate lifecycle state against an explicit instant.
     *
     * <p>Package-visible so tests can exercise the lifecycle thresholds
     * deterministically without sleeping in real time.
     */
    void evaluate(Instant now) {
        for (NodeRecord record : deviceRegistry.list()) {
            String nodeId = record.nodeId();

            long ageMs =
                    now.toEpochMilli()
                            - record.lastHeartbeat().toEpochMilli();

            if (ageMs >= OFFLINE_AFTER_MS) {
                if (record.state() != NodeStatus.OFFLINE) {
                    System.out.printf(
                            "[NodeHealthMonitor] No heartbeat from %s "
                                    + "in %ds -> OFFLINE%n",
                            record.info().displayName(),
                            ageMs / 1000
                    );

                    deviceRegistry.markOffline(nodeId);
                }

                continue;
            }

            if (ageMs >= DEGRADED_AFTER_MS
                    && record.state() == NodeStatus.ONLINE) {

                System.out.printf(
                        "[NodeHealthMonitor] No heartbeat from %s "
                                + "in %ds -> DEGRADED%n",
                        record.info().displayName(),
                        ageMs / 1000
                );

                deviceRegistry.markDegraded(nodeId);
            }
        }
    }
}
