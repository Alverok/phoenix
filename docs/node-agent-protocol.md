# Phoenix Node Agent Protocol v0.1

The **Phoenix Node Agent** is a device-side runtime responsible for identifying a physical device, reporting its resources and capabilities, receiving work, executing it, and returning results to **Phoenix Core**.

This protocol defines the contract between a node agent and Phoenix Core. The contract is **implementation- and transport-independent**.

A node may be implemented using:

- Termux
- Native Android
- Future embedded/firmware implementations such as ESP32
- Other supported runtimes

Phoenix Core does not depend on the implementation technology. **Termux and native Android are two implementations of the same node-agent contract, not two separate protocols.**

## 1. Identity Model

Every node has two identities.

### `deviceId`

The persistent physical-device identity owned by the node agent.

Requirements:

- Generated once when the node agent is first initialized.
- Persisted locally.
- Survives application restarts.
- Must remain stable across normal agent restarts.
- Must not be derived from the human-readable device name.
- Must be used to reconnect an existing node rather than create a new logical node.

Example:

```text
android-abc123
```

### `nodeId`

The Phoenix logical identity assigned by **Phoenix DeviceRegistry**.

Requirements:

- Assigned by Phoenix Core.
- Returned during registration.
- Used by Phoenix Core to identify the registered node.
- A reconnect using a known `deviceId` must resolve to the existing logical node rather than create a duplicate node.

Example:

```text
f30bfc75-...
```

### `displayName`

A human-readable label for the physical device.

Examples:

```text
Samsung-SM-S916B
Samsung S23
Pixel 8
```

`displayName` is informational only and **must never be used as the primary node identity**.

---

# 2. REGISTER

## Endpoint

```http
POST /register
```

## Purpose

Establish or restore the logical identity of a node with Phoenix Core.

Registration is the first protocol operation performed by a node agent.

A successful registration establishes the relationship:

```text
deviceId → nodeId
```

## Request

```json
{
  "deviceId": "android-abc123",
  "displayName": "Samsung-SM-S916B",
  "hardwareClass": "ANDROID",
  "architecture": "aarch64",
  "cpuCores": 8,
  "memoryMb": 11464,
  "adapterType": "android",
  "capabilities": []
}
```

## Fields

| Field | Type | Description |
|---|---|---|
| `deviceId` | string | Persistent identity generated and stored by the node agent. |
| `displayName` | string | Human-readable device label. |
| `hardwareClass` | string | Broad hardware/platform classification, e.g. `ANDROID`. |
| `architecture` | string | CPU architecture, e.g. `aarch64`. |
| `cpuCores` | integer | Number of CPU cores available to the runtime. |
| `memoryMb` | integer | Total memory reported by the node in MiB. |
| `adapterType` | string | Node-agent implementation/adapter type, e.g. `android` or `termux`. |
| `capabilities` | array | Supported task/execution capabilities. Empty if none are currently advertised. |

## Response

Successful registration returns HTTP `200`:

```json
{
  "status": "ok",
  "nodeId": "phoenix-assigned-node-id"
}
```

## Registration semantics

The first registration creates the logical node:

```text
new deviceId
      ↓
Phoenix DeviceRegistry
      ↓
new nodeId
```

Subsequent registration using the same persistent `deviceId` is a **reconnect**, not a new-node registration:

```text
known deviceId
      ↓
Phoenix DeviceRegistry
      ↓
existing nodeId
```

Phoenix Core **must not create duplicate logical nodes solely because an agent restarted or re-registered**.

If the agent loses its local `deviceId`, it may be treated as a new physical identity. Therefore, implementations must persist `deviceId` reliably.

---

# 3. HEARTBEAT

## Endpoint

```http
POST /heartbeat
```

## Purpose

Refresh node liveness and report the node's current resource and execution state.

The heartbeat is periodic and should be sent by every registered node while it is active.

## Request

```json
{
  "deviceId": "android-abc123",
  "cpuUsagePercent": -1,
  "availableRamMb": 3720,
  "activeTasks": 0,
  "queueLength": 0,
  "networkLatencyMs": 12.4,
  "energyCostEstimate": 3.0
}
```

The implementation may additionally provide `nodeId` where supported, but `deviceId` remains the persistent node-agent identity used for reconnect and identity resolution.

## Fields

| Field | Type | Description |
|---|---|---|
| `deviceId` | string | Persistent identity of the node agent. |
| `cpuUsagePercent` | number | Current CPU utilization percentage, or `-1` when reliable measurement is unavailable. |
| `availableRamMb` | integer | Currently available memory in MiB. |
| `activeTasks` | integer | Number of tasks currently executing. |
| `queueLength` | integer | Number of tasks waiting locally for execution. |
| `networkLatencyMs` | number | Recent measured network latency to Phoenix Core, when available. |
| `energyCostEstimate` | number | Node-provided estimate of relative energy cost for scheduling purposes. |

## CPU telemetry

A value of:

```json
{
  "cpuUsagePercent": -1
}
```

means:

> CPU utilization could not be measured reliably on this platform.

This is **not equivalent to 0% utilization**.

For example, an Android/Termux implementation may not have reliable access to the required system telemetry.

Phoenix Scheduler must therefore use a **neutral scheduling fallback** when CPU utilization is unavailable rather than treating the node as completely idle.

---

# 4. Heartbeat and Node Liveness

Phoenix Core tracks node liveness using heartbeat messages.

Recommended initial heartbeat interval:

```text
10 seconds
```

The exact interval is an implementation/configuration detail and may change without changing the protocol.

If Phoenix Core receives no heartbeat for:

```text
20 seconds
```

the node is marked:

```text
DEGRADED
```

A subsequent valid heartbeat recovers the node to:

```text
ONLINE
```

Conceptually:

```text
ONLINE
   │
   │ heartbeat timeout
   ▼
DEGRADED
   │
   │ valid heartbeat
   ▼
ONLINE
```

This state transition is owned by Phoenix Core.

A temporary heartbeat failure must not automatically create a new node identity.

---

# 5. HEARTBEAT Response

Successful heartbeat:

```http
200 OK
```

```json
{
  "status": "ok"
}
```

If Phoenix Core does not recognize the supplied node identity:

```http
404 Not Found
```

the node agent should perform `REGISTER` again.

The expected recovery sequence is:

```text
HEARTBEAT
    │
    └── 404
         │
         ▼
      REGISTER
         │
         ▼
   receive nodeId
         │
         ▼
      HEARTBEAT
```

The agent should not continuously create new identities when recovering from a `404`.

---

# 6. TASK

**Reserved for a future milestone.**

## Purpose

Deliver a task selected by the Phoenix Scheduler to a node agent.

The initial REGISTER + HEARTBEAT milestone does not require task execution.

The planned task contract is:

```json
{
  "taskId": "task-123",
  "type": "example-task",
  "requirements": {},
  "payload": {}
}
```

### Planned fields

| Field | Type | Description |
|---|---|---|
| `taskId` | string | Unique Phoenix task identity. |
| `type` | string | Task/execution type. |
| `requirements` | object | Resource and capability requirements. |
| `payload` | object | Task-specific execution data. |

The precise task schema will be defined in a future protocol revision.

---

# 7. RESULT

**Reserved for a future milestone.**

## Purpose

Return task execution status and output from the node agent to Phoenix Core.

The planned result contract is:

```json
{
  "taskId": "task-123",
  "status": "completed",
  "output": {},
  "executionTimeMs": 1240
}
```

### Planned fields

| Field | Type | Description |
|---|---|---|
| `taskId` | string | Phoenix task identity. |
| `status` | string | Execution state/result. |
| `output` | object | Task output or result payload. |
| `executionTimeMs` | integer | Measured execution duration in milliseconds. |

The precise result and error semantics will be defined in a future protocol revision.

---

# 8. Capabilities

The `capabilities` field advertises functionality supported by the node agent.

Example:

```json
{
  "capabilities": [
    "shell",
    "python",
    "android"
  ]
}
```

An implementation must only advertise capabilities it can actually execute.

Capability names are protocol-level identifiers and should remain stable across node-agent implementations.

Future revisions may define a formal capability registry.

---

# 9. Resource Reporting

Resource values reported by the node are **telemetry**, not guaranteed reservations.

For example:

```json
{
  "availableRamMb": 3720,
  "cpuUsagePercent": 23.5,
  "activeTasks": 1,
  "queueLength": 0
}
```

Phoenix Scheduler may use these values when selecting work, but should account for measurement uncertainty and platform differences.

Missing or unreliable telemetry must not be silently converted into optimistic values.

In particular:

```text
unknown CPU utilization ≠ 0% CPU utilization
```

---

# 10. Error and Recovery Principles

Node agents should be designed to recover from transient failures.

The basic recovery rules are:

1. **Registration failure**
   - Retry according to the implementation's backoff policy.
   - Do not generate a new `deviceId` merely because registration failed.

2. **Heartbeat failure**
   - Retry according to the implementation's backoff policy.
   - Preserve the existing `deviceId` and `nodeId`.

3. **Heartbeat `404`**
   - Re-register using the existing persistent `deviceId`.
   - Accept the `nodeId` returned by Phoenix Core.
   - Resume heartbeat operation.

4. **Application restart**
   - Reload the persisted `deviceId`.
   - Re-register/reconnect.
   - Do not intentionally create a new physical identity.

5. **Temporary network loss**
   - Preserve identity locally.
   - Resume communication when connectivity returns.

---

# 11. Current Transport

The current transport is:

```text
HTTP/JSON
```

The HTTP API provides the initial implementation because it is simple to debug, test, and integrate across Termux, native Android, and Phoenix Core.

The protocol itself must not depend on HTTP-specific implementation details.

---

# 12. Planned Transport

A future transport is:

```text
MQTT
```

MQTT is intended for asynchronous messaging patterns such as:

```text
heartbeat
task delivery
task result
```

The logical protocol messages should remain equivalent regardless of transport.

For example:

```text
HTTP                         MQTT
────────────────────────────────────────
POST /heartbeat      ↔       heartbeat message
POST /task           ↔       task message
POST /result         ↔       result message
```

The transport may change without changing the underlying node-agent contract.

---

# 13. Implementation Boundary

The protocol separates **what a node is** from **how a node is implemented**.

```text
                    Phoenix Node Agent Protocol
                              │
             ┌────────────────┼────────────────┐
             │                │                │
          Termux         Native Android     Future Node
             │                │                │
             └────────────────┼────────────────┘
                              │
                         Phoenix Core
```

Each implementation is responsible for translating its platform-specific APIs into the common protocol.

For example:

```text
Termux
  └── /proc, shell, Linux utilities
          ↓
      Node Agent
          ↓
   Phoenix Protocol
```

and:

```text
Native Android
  └── Android APIs, Kotlin/Java runtime
          ↓
      Node Agent
          ↓
   Phoenix Protocol
```

Phoenix Core should consume the protocol contract rather than depend on either implementation.

---

# 14. Protocol Version

Current version:

```text
v0.1
```

This version covers:

- Node identity
- Registration
- Reconnection semantics
- Heartbeat
- Basic resource telemetry
- Node liveness
- Capability advertisement
- Transport abstraction
- Reserved TASK/RESULT operations

TASK execution and RESULT delivery are intentionally deferred until REGISTER + HEARTBEAT are proven end-to-end.

Future incompatible changes should increment the protocol major version.

Backward-compatible additions may increment the minor version.

---

# 15. Initial Milestone

The first implementation milestone is intentionally limited to:

```text
1. Generate persistent deviceId
2. REGISTER with Phoenix Core
3. Receive nodeId
4. Persist node identity state
5. Send periodic HEARTBEAT
6. Report available resource telemetry
7. Handle heartbeat failure
8. Handle heartbeat 404 by re-registering
9. Verify ONLINE/DEGRADED state transitions
```

TASK execution is **not part of this milestone**.

The purpose of this boundary is to prove the identity and liveness contract before introducing distributed task execution.

---

## Summary

The Phoenix Node Agent contract establishes a stable boundary between Phoenix Core and physical devices.

The core identity relationship is:

```text
persistent deviceId
        │
        ▼
Phoenix DeviceRegistry
        │
        ▼
assigned nodeId
```

The minimum operational lifecycle is:

```text
START
  │
  ▼
LOAD/CREATE deviceId
  │
  ▼
REGISTER
  │
  ▼
RECEIVE nodeId
  │
  ▼
HEARTBEAT ─────────────┐
  │                    │
  │                    │
  ▼                    │
ONLINE                 │
  │                    │
  │ heartbeat timeout  │
  ▼                    │
DEGRADED               │
  │                    │
  │ heartbeat          │
  └────────────────────┘
```

**Protocol first, implementation second.**

Termux and native Android are implementations of this contract. Phoenix Core remains independent of the underlying device-agent implementation and transport.
