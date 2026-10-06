# Phoenix

Phoenix is a lightweight middleware and orchestration system for heterogeneous, resource-constrained edge devices.

The project explores a simple idea: instead of discarding older phones, tablets, laptops, and other devices, can we turn them into a cooperative edge-computing mesh and intelligently place work across them?

Phoenix is built around inversion of control, hardware adapters, a device registry, resource-aware scheduling, and pluggable node-agent transports.

## Current milestone

Phoenix currently has a working end-to-end path from the orchestrator to a **real Android device**:

```text
Android Agent
     |
     | REGISTER
     v
Phoenix Core
     |
     | HEARTBEAT
     v
Device Registry
     |
     v
Scheduler
     |
     | selects real Android node
     v
Task Queue
     |
     | Android polls
     v
Android Agent
     |
     | executes Monte-Carlo pi
     v
Result
     |
     | POST /results
     v
Phoenix Core
```

The current remote task transport is **HTTP pull**. It is a working experimental transport and a stepping stone toward the planned MQTT/TLS transport.

## What works as of now:

### Phoenix Core

- **Device Registry** — tracks real nodes, identity, live resource status, and lifecycle state.
- **Adapter Registry** — resolves hardware-class adapters without hard-coding concrete implementations into the dispatcher.
- **Task Dispatcher** — coordinates task submission, scheduling, execution, and result handling.
- **Greedy Cost Scheduler** — selects the currently lowest-cost eligible node.
- **Placement Cost Model** — combines execution time, energy, load, and network-related factors into a placement cost.
- **Node lifecycle monitoring** — nodes can move from `ONLINE` to `DEGRADED` when heartbeats stop and return to `ONLINE` when heartbeats resume.
- **Node-agent HTTP protocol** — real devices can register and report live state.

### Native Android Node Agent

The standalone Android application can:

- persist a stable device identity
- register itself with Phoenix
- report device metadata and memory information
- send periodic heartbeats
- poll Phoenix for assigned work
- execute Monte-Carlo pi estimation on the physical device
- send the real execution result back to Phoenix
- participate in heartbeat-based lifecycle monitoring

### End-to-end execution

A task can travel through the complete path:

```text
Phoenix dispatch
    -> Scheduler
    -> real Android node selected
    -> task queued for that node
    -> Android agent polls
    -> real computation on device
    -> result returned
    -> Phoenix completes task
```

No simulated node is required for this path.

## Architecture

```text
                         PHOENIX CORE
+------------------------------------------------------------+
|                                                            |
|  DeviceRegistry                                            |
|       |                                                    |
|       +--------------- Node state / identity               |
|       |                                                    |
|  TaskDispatcher ------> TaskScheduler                      |
|                              |                             |
|                              v                             |
|                       Placement Cost                       |
|                              |                             |
|                              v                             |
|                        Selected Node                       |
|                              |                             |
|                       Hardware Adapter                     |
|                              |                             |
|                       NodeAgentClient                      |
|                              |                             |
|                        HTTP transport                      |
|                              |                             |
+------------------------------+-----------------------------+
                               |
                               | REGISTER / HEARTBEAT
                               | TASK POLL / RESULT
                               v
                     +----------------------+
                     |    Android Agent     |
                     |                      |
                     | telemetry            |
                     | heartbeat            |
                     | task polling         |
                     | task execution       |
                     +----------------------+
```

The important transport boundary is:

```text
IHardwareAdapter
        |
        v
NodeAgentClient
        |
        +-- HttpNodeAgentClient   <- current
        +-- MqttNodeAgentClient   <- planned
```

The scheduler and dispatcher should not need to know whether a node is reached through HTTP, MQTT, or another future transport.

## Node identity and lifecycle

### Device identity

A node agent keeps a persistent `deviceId`, for example:

```text
android-7fbacc0c-bb51-4230-8790-79be08e3a18b
```

This identity belongs to the agent/device.

### Phoenix node identity

Phoenix assigns its own `nodeId` when the device registers. Phoenix remains authoritative over the node registry while the device keeps a stable identity across reconnects.

### Lifecycle

```text
              heartbeat
        +------------------+
        |                  v
      ONLINE ----------> ONLINE
        |
        | heartbeat timeout
        v
    DEGRADED
        |
        | heartbeat resumes
        v
      ONLINE
```

## Node Agent HTTP protocol

The current experimental transport uses four HTTP operations.

### Register

`POST /register`

Example:

```json
{
    "deviceId": "android-<stable-id>",
    "displayName": "samsung-SM-S916B",
    "hardwareClass": "ANDROID",
    "architecture": "arm64-v8a",
    "cpuCores": 8,
    "memoryMb": 7072,
    "adapterType": "android",
    "capabilities": []
}
```

Phoenix responds with its assigned node identity.

### Heartbeat

`POST /heartbeat`

Example:

```json
{
    "deviceId": "android-...",
    "cpuUsagePercent": -1.0,
    "availableRamMb": 1878,
    "activeTasks": 0,
    "queueLength": 0,
    "networkLatencyMs": 40.0,
    "energyCostEstimate": 3.0
}
```

`-1` means the platform cannot provide trustworthy CPU telemetry. Phoenix should not treat a fabricated number as a measurement.

### Task polling

`GET /tasks/poll?deviceId=android-...`

When work is waiting:

```json
{
    "id": "task-001",
    "type": "Monte-Carlo-Pi",
    "samples": 2000000
}
```

When no work is waiting, the normal response is `204 No Content`.

### Result submission

`POST /results`

Example:

```json
{
    "taskId": "task-001",
    "status": "SUCCESS",
    "outputData": "Computed Monte-Carlo pi ~= 3.1417",
    "executionTimeMs": 230
}
```

A result is accepted only for a task whose current attempt has actually been
delivered to a node. A late result for an attempt Phoenix already gave up on
(or re-queued elsewhere) is answered with `404 unknown_task`.

### Execution start acknowledgement (optional)

`POST /tasks/started`

```json
{ "taskId": "task-001" }
```

Sent by the agent right after it begins executing a task it polled. It is
**optional**: agents that never send it keep working, and Phoenix simply
reports the start as unacknowledged. Returns `404 unknown_task` for a task
that has not been delivered.

## Task lifecycle and timeout semantics

Phoenix no longer uses one end-to-end timer from queue submission to result.
A timeout can mean three different things, and Core now tells them apart
through a transport-neutral lifecycle contract (see `NodeAgentClient`):

```text
ACCEPTED -> DELIVERED -> STARTED -> COMPLETED
    \            \           \
     +------------+-----------+--> FAILED
```

| Situation                                     | Failure reason      | Re-placed? | Node marked `DEGRADED`? |
| --------------------------------------------- | ------------------- | ---------- | ----------------------- |
| Never delivered, node still polling (backlog) | `DELIVERY_TIMEOUT`  | Yes        | **No**                  |
| Never delivered, node not in contact          | `NODE_UNREACHABLE`  | Yes        | Yes                     |
| Delivered, no result in time                  | `EXECUTION_TIMEOUT` | Yes        | Yes                     |
| Transport that cannot tell the phases apart   | `TIMEOUT`           | Yes        | Yes (conservative)      |

Ordinary queue backlog on a healthy node is therefore no longer treated as
evidence that the node failed, while genuinely unreachable or silent nodes
are detected exactly as before. Node health itself is still owned by
heartbeat monitoring (`NodeHealthMonitor`); task timeouts only influence it
when the failure actually implicates the node.

Tunable system properties (seconds):

| Property                         | Default | Meaning                                                              |
| -------------------------------- | ------- | -------------------------------------------------------------------- |
| `phoenix.deliveryTimeoutSeconds` | 60      | Max time a task may wait for the node to take it                     |
| `phoenix.taskTimeoutSeconds`     | 12      | Max time **after delivery** for a result (was end-to-end before #28) |
| `phoenix.contactLivenessSeconds` | 15      | How recently a node must have polled to count as "in contact"        |

### Node `OFFLINE` and in-flight tasks

Timeouts decide what to do when a node is merely _slow_. A node that
`NodeHealthMonitor` declares **OFFLINE** is different: Core has decided it is
gone, so it stops waiting for it.

```text
ONLINE --(20s no heartbeat)--> DEGRADED --(60s no heartbeat)--> OFFLINE
                                  |                                |
                     suspicion only: nothing                 decision: every
                     in flight is touched                    in-flight attempt on
                                                             the node is abandoned
```

When a node goes `OFFLINE`, every attempt bound to it that has not finished
(queued, delivered or executing) is **abandoned** immediately instead of
waiting for its delivery/execution timeout. The blocked transport wakes up and
returns `NODE_OFFLINE`; the unchanged dispatcher then releases the placement
reservation and re-places the task through the same `TaskAdmission` boundary as
every other recovery.

| Aspect                        | Behaviour                                                                                                        |
| ----------------------------- | ---------------------------------------------------------------------------------------------------------------- |
| Trigger                       | `OFFLINE` only. `DEGRADED` never cancels work: the node may still be running it                                  |
| Retry                         | A **new attempt starts from the beginning** with its own fresh delivery/execution timeouts. No resume/checkpoint |
| Priority                      | No special scheduler. The task keeps its FIFO place in `PendingTaskQueue` and competes through normal admission  |
| Other nodes                   | Untouched. Only attempts bound to the OFFLINE node are abandoned                                                 |
| Single node                   | The task waits in `PendingTaskQueue` with a **fresh** `phoenix.noNodeWaitSeconds` window                         |
| Node health                   | `NODE_OFFLINE` is derived from node health, so it is never fed back into it                                      |
| Outcome of the old attempt    | `ABANDONED` = _unknown_. OFFLINE does not prove the computation stopped                                          |
| Late result from the old node | Rejected (`404`); the abandoned task is also withdrawn from the node's queue                                     |

Lifecycle with the new terminal state:

```text
ACCEPTED -> DELIVERED -> STARTED -> COMPLETED
    \            \           \
     +------------+-----------+--> FAILED      (known failure)
     +------------+-----------+--> ABANDONED   (outcome unknown; node OFFLINE)
```

#### Recovery window and budget

A task returned to waiting after a **failed attempt** gets a _fresh_
`phoenix.noNodeWaitSeconds` window (time spent at a node that then failed is not
time without a node) and keeps its place in line. A task that merely finds no
node on a recovery tick keeps its original window, so genuinely unplaceable
tasks still expire. Because a fresh window would otherwise allow endless
bouncing, failure-driven returns to waiting are budgeted:

| Property                    | Default | Meaning                                                          |
| --------------------------- | ------- | ---------------------------------------------------------------- |
| `phoenix.maxRecoveryCycles` | 3       | Failed-attempt cycles a task may go through before Core gives up |

An expired task is given up only after one fresh placement attempt also found no
eligible node.

#### Optional attempt identifier on the wire

Core always numbers attempts internally. To let Core reject a late message from
an old attempt even after the task was re-delivered to _another_ node, Core can
send the attempt number with the task and accept it back:

```json
{ "taskId": "task-001", "type": "Monte-Carlo-Pi", "samples": 2000000, "attempt": 2 }
```

```json
{ "taskId": "task-001", "attempt": 2, "status": "SUCCESS", "outputData": "..." }
```

The same optional `attempt` field is accepted by `POST /tasks/started`. It is
**off by default** (`-Dphoenix.attemptOnWire=true` enables it) because Phoenix
cannot know whether every deployed agent tolerates an unknown JSON field.
Agents that do not echo it keep working exactly as before.

## Why HTTP pull is used now

The current HTTP implementation is deliberately not the final transport design.

For the experimental milestone, pull was chosen because the Android device reliably initiates outbound connections:

```text
Android  ------>  Phoenix
```

This avoids depending on the laptop being able to open a new inbound connection to the phone across hotspot/NAT/firewall configurations.

The scheduler still performs the orchestration decision:

```text
Scheduler
   |
   v
select node X
   |
   v
queue task for node X
```

The Android agent only controls how it retrieves that already-assigned work.

## Planned MQTT/TLS transport

The long-term transport direction is MQTT secured with TLS.

The intended architecture is:

```text
                 Phoenix Core
                      |
                      | publish task
                      v
                 MQTT Broker
                      |
                      | node-specific topic
                      v
                 Android Agent
                      |
                      | execute
                      v
                 MQTT Broker
                      |
                      | publish result
                      v
                 Phoenix Core
```

The important improvement is that MQTT gives Phoenix **push-style task delivery** without requiring Phoenix to maintain direct inbound reachability to every phone. Nodes keep an MQTT connection outward to the broker, and the broker handles delivery.

`NodeAgentClient` is the seam that lets the project experiment with transports without rewriting the scheduler or dispatcher.

## Scheduling

The current scheduler is intentionally simple:

$$
n^* = \arg\min_{n \in E(t)} C(n,t)
$$

where `E(t)` is the set of eligible nodes and `C(n,t)` is the placement cost for node `n`.

The current cost model combines dimensions including:

- estimated execution time
- energy cost
- current load
- network latency

The present implementation is a **greedy argmin heuristic**. It is the practical baseline we can measure on real heterogeneous hardware before introducing a more sophisticated optimizer.

## Research direction

The intended progression is:

```text
Real node measurements
        |
        v
Greedy scheduling baseline
        |
        v
Normalized / calibrated cost model
        |
        v
Heterogeneous workload experiments
        |
        v
Advanced placement optimization
        |
        v
Memetic / research-backed scheduling comparison
```

The goal is to evaluate advanced scheduling against measured behavior rather than implement an optimization algorithm in isolation.

## Current status

| Component                                | Status      |
| ---------------------------------------- | ----------- |
| IoC / Guice adapter architecture         | Working     |
| Device registry                          | Working     |
| Greedy scheduler                         | Working     |
| Task dispatcher                          | Working     |
| Node registration                        | Working     |
| Heartbeat monitoring                     | Working     |
| `ONLINE -> DEGRADED -> ONLINE` lifecycle | Working     |
| Native Android node agent                | Working     |
| Android live telemetry                   | Working     |
| Android task execution                   | Working     |
| HTTP task polling                        | Working     |
| Result return to Phoenix                 | Working     |
| Linux native/remote agent                | In progress |
| Failure-aware task rescheduling          | In progress |
| Delivery vs execution timeout semantics  | Working     |
| In-flight recovery on node OFFLINE       | Working     |
| MQTT transport                           | Planned     |
| TLS-secured transport                    | Planned     |
| ESP32 real execution agent               | Future      |
| Memetic scheduling                       | Future      |

## Repository layout

```text
Directory structure:
└──phoenix/
    ├── README.md
    ├── build.gradle.kts
    ├── gradlew
    ├── gradlew.bat
    ├── settings.gradle.kts
    ├── docs/
    │   └── node-agent-protocol.md
    ├── gradle/
    │   └── wrapper/
    │       └── gradle-wrapper.properties
    └── src/
        ├── main/
        │   └── java/
        │       └── com/
        │           └── phoenix/
        │               ├── App.java
        │               ├── adapter/
        │               │   ├── IHardwareAdapter.java
        │               │   └── impl/
        │               │       ├── AndroidAdapter.java
        │               │       ├── Esp32Adapter.java
        │               │       └── LinuxAdapter.java
        │               ├── core/
        │               │   ├── AdapterRegistry.java
        │               │   ├── DeviceRegistry.java
        │               │   └── PhoenixModule.java
        │               ├── dispatch/
        │               │   ├── DispatchResult.java
        │               │   ├── NoEligibleNodeException.java
        │               │   └── TaskDispatcher.java
        │               ├── model/
        │               │   ├── HardwareClass.java
        │               │   ├── NodeExecutionTarget.java
        │               │   ├── NodeInfo.java
        │               │   ├── NodeRecord.java
        │               │   ├── NodeRegistrationRequest.java
        │               │   ├── NodeStatus.java
        │               │   ├── ResourceStatus.java
        │               │   ├── TaskDescriptor.java
        │               │   ├── TaskRequirements.java
        │               │   └── TaskResult.java
        │               ├── net/
        │               │   ├── DeviceAnnouncement.java
        │               │   ├── HttpNodeAgentClient.java
        │               │   ├── NodeAgentClient.java
        │               │   ├── RegistrationServer.java
        │               │   └── TaskQueueService.java
        │               └── scheduler/
        │                   ├── GreedyCostScheduler.java
        │                   ├── PlacementCostModel.java
        │                   ├── SchedulingWeights.java
        │                   ├── TaskScheduler.java
        │                   └── WeightedPlacementCostModel.java
        └── test/
            └── java/
                └── com/
                    └── phoenix/
                        ├── core/
                        │   ├── AdapterRegistryTest.java
                        │   └── DeviceRegistryTest.java
                        └── scheduler/
                            └── GreedyCostSchedulerTest.java

```

The native Android Agent is maintained as a standalone Android Studio project with its own Gradle build.

## Running the Core

Run tests first:

```bash
./gradlew clean test
```

Start Phoenix using the LAN address reachable by your node devices:

```bash
./gradlew --console=plain \
  -Dphoenix.advertiseAddress=<YOUR_LAN_IP> \
  run
```

For example:

```bash
./gradlew --console=plain \
  -Dphoenix.advertiseAddress=10.62.95.63 \
  run
```

Phoenix exposes the node-agent API at:

```text
http://<YOUR_LAN_IP>:8080
```

### Core CLI

```text
phoenix> list
```

Shows currently registered nodes.

```text
phoenix> dispatch
```

Submits a Monte-Carlo-Pi task and sends it through the scheduler/dispatcher.

```text
phoenix> quit
```

Stops Phoenix Core.

## Running the Android Agent

Open the standalone Phoenix Agent project in Android Studio.

Build:

```bash
./gradlew clean assembleDebug
```

Install onto a connected device:

```bash
./gradlew installDebug
```

The device and Phoenix Core must be able to reach each other over the chosen network.

In the Android app:

1. Enter the Core URL, for example `http://10.62.95.63:8080`.
2. Connect to Phoenix.
3. Wait for registration and heartbeat activity.
4. On Phoenix Core, run `list`.
5. Run `dispatch`.

A successful run shows a real task executed on the Android device and a real result returned to Phoenix.

## Design principles

- **Real nodes are authoritative** — Production Core does not manufacture a fake fleet. Nodes enter the registry by registering with Phoenix.

- **Hardware details stay behind adapters** — Core orchestration depends on `IHardwareAdapter`, not directly on concrete hardware implementations.

- **Transport is replaceable** — HTTP is the current working transport. MQTT/TLS is the planned production direction.

- **Scheduling is separate from execution** — The scheduler chooses a node. Adapters and node-agent transports perform execution.

- **Measure before optimizing** — The greedy scheduler is the current baseline. More advanced placement algorithms should be evaluated against real heterogeneous-device measurements.

## Milestone boundary

Phoenix has moved beyond a skeleton. It can now:

```text
real device
    |
    v
register
    |
    v
heartbeat
    |
    v
be considered by scheduler
    |
    v
receive assigned work
    |
    v
execute real computation
    |
    v
return real result
```

The next major engineering step is to extend this working execution model to additional node types and introduce secure message-oriented MQTT/TLS transport without breaking the existing orchestration abstractions.
