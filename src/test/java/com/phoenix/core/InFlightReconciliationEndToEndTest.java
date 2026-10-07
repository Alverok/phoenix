package com.phoenix.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.phoenix.model.NodeExecutionTarget;
import com.phoenix.model.TaskDescriptor;
import com.phoenix.model.TaskFailureReason;
import com.phoenix.model.TaskLifecycleState;
import com.phoenix.model.TaskResult;
import com.phoenix.net.HttpNodeAgentClient;
import com.phoenix.net.RegistrationServer;
import com.phoenix.net.TaskQueueService;

/**
 * End-to-end over the real pull transport:
 * RegistrationServer + TaskQueueService + HttpNodeAgentClient +
 * NodeHealthMonitor + InFlightAttemptReconciler, driven like an Android agent.
 *
 * <p>The delivery and execution timeouts are set to 30 s on purpose: every
 * early return asserted here can only come from reconciliation, never from a
 * timeout.
 *
 * <p>Lives in com.phoenix.core to use the monitor's package-private
 * {@code evaluate(Instant)} instead of waiting a real minute.
 */
class InFlightReconciliationEndToEndTest {

    private static final String DEVICE_ID = "android-e2e-device";

    private final HttpClient http = HttpClient.newHttpClient();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private DeviceRegistry registry;
    private NodeHealthMonitor monitor;
    private TaskLifecycleService lifecycle;
    private TaskQueueService queue;
    private RegistrationServer server;
    private HttpNodeAgentClient client;
    private NodeExecutionTarget target;
    private String nodeId;

    @BeforeEach
    void start() throws Exception {

        registry = new DeviceRegistry();
        monitor = new NodeHealthMonitor(registry);
        lifecycle = new TaskLifecycleService();
        queue = new TaskQueueService(lifecycle);

        new InFlightAttemptReconciler(lifecycle, monitor).start();

        server = new RegistrationServer(
                new NodeTelemetryService(registry, monitor), queue);
        server.start(0);

        client = new HttpNodeAgentClient(
                server, queue, lifecycle,
                new TaskTimeoutPolicy(
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(15)));

        String response = post("/register",
                "{\"deviceId\":\"" + DEVICE_ID + "\","
                        + "\"displayName\":\"e2e-phone\","
                        + "\"hardwareClass\":\"ANDROID\","
                        + "\"architecture\":\"arm64\","
                        + "\"cpuCores\":4,\"memoryMb\":4096,"
                        + "\"adapterType\":\"android\","
                        + "\"capabilities\":[]}").body();

        nodeId = response.replaceAll(".*\"nodeId\":\"([^\"]+)\".*", "$1");
        target = new NodeExecutionTarget(nodeId, "e2e-phone", "android");
    }

    @AfterEach
    void stop() {
        System.clearProperty("phoenix.attemptOnWire");
        executor.shutdownNow();
        server.stop();
    }

    /** The instant at which the node's heartbeat is exactly 60s old. */
    private Instant offlineInstant() {
        return registry.get(nodeId).orElseThrow()
                .lastHeartbeat().plusSeconds(60);
    }

    @Test
    void queuedUndeliveredTaskIsRecoveredAsSoonAsTheNodeIsOffline()
            throws Exception {

        Future<TaskResult> attempt = run("task-001");
        waitUntilQueued();

        long start = System.nanoTime();
        monitor.evaluate(offlineInstant());

        TaskResult result = attempt.get(3, TimeUnit.SECONDS);

        assertTrue(Duration.ofNanos(System.nanoTime() - start)
                .compareTo(Duration.ofSeconds(2)) < 0,
                "must return on reconciliation, not on the 30s timeout");
        assertEquals(TaskFailureReason.NODE_OFFLINE, result.failureReason());
        assertTrue(result.isRetryable());
        assertFalse(result.implicatesNodeHealth());
        assertTrue(result.outputData().contains("queued and undelivered"));
        assertEquals(0, queue.queueLength(DEVICE_ID));
        assertEquals(TaskLifecycleState.ABANDONED,
                lifecycle.snapshot("task-001").orElseThrow().state());
    }

    @Test
    void deliveredAndExecutingTaskIsRecoveredAsSoonAsTheNodeIsOffline()
            throws Exception {

        Future<TaskResult> attempt = run("task-001");
        pollUntilTask();
        post("/tasks/started", "{\"taskId\":\"task-001\"}");

        monitor.evaluate(offlineInstant());

        TaskResult result = attempt.get(3, TimeUnit.SECONDS);

        assertEquals(TaskFailureReason.NODE_OFFLINE, result.failureReason());
        assertTrue(result.outputData().contains("executing it"));

        // The node comes back and reports the abandoned attempt late.
        assertEquals(404, post("/results",
                "{\"taskId\":\"task-001\",\"status\":\"SUCCESS\","
                        + "\"outputData\":\"late\"}").statusCode());
    }

    @Test
    void aDegradedNodeKeepsItsInFlightTask() throws Exception {

        Future<TaskResult> attempt = run("task-001");
        pollUntilTask();

        // 30s without a heartbeat: DEGRADED, not OFFLINE.
        monitor.evaluate(registry.get(nodeId).orElseThrow()
                .lastHeartbeat().plusSeconds(30));

        try {
            attempt.get(500, TimeUnit.MILLISECONDS);
            throw new AssertionError("a DEGRADED node's task was cancelled");
        } catch (TimeoutException expected) {
            // still running, as required
        }

        // ... and it can still finish normally.
        assertEquals(200, post("/results",
                "{\"taskId\":\"task-001\",\"status\":\"SUCCESS\","
                        + "\"outputData\":\"ok\"}").statusCode());
        assertTrue(attempt.get(3, TimeUnit.SECONDS).isSuccess());
    }

    @Test
    void theRetryGetsAFreshAttemptAndAFreshTimeout() throws Exception {

        Future<TaskResult> first = run("task-001");
        pollUntilTask();
        monitor.evaluate(offlineInstant());
        first.get(3, TimeUnit.SECONDS);

        // The node is back; recovery re-places the same task.
        registry.markOnline(nodeId);
        Future<TaskResult> second = run("task-001");

        assertTrue(pollUntilTask().contains("task-001"));
        assertEquals(2, lifecycle.snapshot("task-001").orElseThrow().attempt());

        post("/results",
                "{\"taskId\":\"task-001\",\"status\":\"SUCCESS\","
                        + "\"outputData\":\"ok\"}");

        assertTrue(second.get(3, TimeUnit.SECONDS).isSuccess());
    }

    @Test
    void withAttemptEchoALateResultFromTheOldAttemptIsRejected()
            throws Exception {

        System.setProperty("phoenix.attemptOnWire", "true");

        Future<TaskResult> first = run("task-001");
        assertTrue(pollUntilTask().contains("\"attempt\":1"));
        monitor.evaluate(offlineInstant());
        first.get(3, TimeUnit.SECONDS);

        registry.markOnline(nodeId);
        Future<TaskResult> second = run("task-001");
        assertTrue(pollUntilTask().contains("\"attempt\":2"));

        // The old attempt's result arrives while attempt 2 is delivered:
        // without the echo this would be taken for attempt 2's result.
        assertEquals(404, post("/results",
                "{\"taskId\":\"task-001\",\"attempt\":1,"
                        + "\"status\":\"SUCCESS\",\"outputData\":\"old\"}")
                .statusCode());

        assertEquals(200, post("/results",
                "{\"taskId\":\"task-001\",\"attempt\":2,"
                        + "\"status\":\"SUCCESS\",\"outputData\":\"new\"}")
                .statusCode());

        assertEquals("new", second.get(3, TimeUnit.SECONDS).outputData());
    }

    @Test
    void theAttemptFieldIsAbsentFromTheWireByDefault() throws Exception {

        Future<TaskResult> attempt = run("task-001");

        assertFalse(pollUntilTask().contains("attempt"));

        post("/results",
                "{\"taskId\":\"task-001\",\"status\":\"SUCCESS\","
                        + "\"outputData\":\"ok\"}");
        attempt.get(3, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private Future<TaskResult> run(String taskId) {
        return executor.submit(() -> client.execute(
                target,
                new TaskDescriptor(taskId, "Monte-Carlo-Pi", 1, 128)));
    }

    private void waitUntilQueued() throws Exception {
        long deadline = System.currentTimeMillis() + 2000;
        while (queue.queueLength(DEVICE_ID) == 0
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
    }

    private String pollUntilTask() throws Exception {
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<String> response =
                    get("/tasks/poll?deviceId=" + DEVICE_ID);
            if (response.statusCode() == 200) {
                return response.body();
            }
            Thread.sleep(10);
        }
        throw new AssertionError("no task was delivered to the node");
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder(uri(path))
                        .timeout(Duration.ofSeconds(3)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String json)
            throws Exception {
        return http.send(
                HttpRequest.newBuilder(uri(path))
                        .timeout(Duration.ofSeconds(3))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.port() + path);
    }
}
