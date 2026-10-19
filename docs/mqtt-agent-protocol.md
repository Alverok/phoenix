# MQTT/TLS node-agent protocol (version 1)

Node agents that cannot be reached directly (Android behind NAT, ESP32) or
that should not have to be (Linux) talk to Phoenix Core through an MQTT
broker. Every connection is outbound from the device to the broker, and Core
still *pushes* work: the broker delivers it on the node's own topic.

```text
              Phoenix Core ── (loopback or TLS) ──┐
                                                  v
                                            MQTT broker (Mosquitto)
                                            TLS :8883, ACL per device
                                  ┌───────────────┼───────────────┐
                                  v               v               v
                             Android agent   Linux agent     ESP32 agent
                                                            (TLS or TLS-PSK)
```

The protocol carries the same information as the HTTP protocol
([README](../README.md#node-agent-http-protocol)): registration, heartbeats,
tasks, start acknowledgements and results. Field names are the same, so an
agent that speaks both builds the same JSON for both. What MQTT adds is
explicit delivery acknowledgement, a session token and cancellation.

The scheduler, dispatcher and placement code do not know this transport
exists: a node that registered over MQTT is reached over MQTT, whatever its
hardware class.

## Contents

- [Connecting to the broker](#connecting-to-the-broker)
- [Topics](#topics)
- [Registration](#registration)
- [Heartbeats](#heartbeats)
- [Tasks](#tasks)
- [Timeouts and failure classification](#timeouts-and-failure-classification)
- [Idempotence: rules for agents](#idempotence-rules-for-agents)
- [Core restarts and lost sessions](#core-restarts-and-lost-sessions)
- [Security model](#security-model)
- [Agent checklist](#agent-checklist)

## Connecting to the broker

| Setting         | Value                                                                   |
| --------------- | ----------------------------------------------------------------------- |
| MQTT version    | 3.1.1                                                                   |
| URL             | `ssl://<broker>:8883` (TLS 1.2+); verify the broker against the CA, **including its host name** |
| Client id       | the agent's `deviceId`                                                  |
| User name       | the agent's `deviceId` (the broker ACL depends on this)                 |
| Password        | the device's broker password (`deploy/mosquitto/add-device.sh`)         |
| Clean session   | `false`, so tasks published while the device is briefly offline are queued for it |
| Keep-alive      | 30 s (any value the broker accepts)                                     |
| Last will       | topic `…/up/status`, payload `{"state":"offline"}`, QoS 1, **retained** |
| Subscriptions   | `{prefix}/v1/nodes/<deviceId>/down/#` and `{prefix}/v1/core/status`, QoS 1 |

After connecting, publish `{"state":"online"}` retained on `…/up/status`.

ESP32 agents for which certificate TLS is too heavy may use the broker's
TLS-PSK listener instead (see [`deploy/mosquitto`](../deploy/mosquitto/README.md)).
The PSK identity is then the user name; nothing else changes.

A `deviceId` is 1 to 128 characters of `[A-Za-z0-9._-]` and starts with a
letter or digit. Generate it once and keep it, like the Android agent does.

## Topics

`{prefix}` is `phoenix` unless Core sets `phoenix.mqtt.topicPrefix`.

| Topic                                     | Direction   | QoS | Retained | Body                         |
| ----------------------------------------- | ----------- | --- | -------- | ---------------------------- |
| `{prefix}/v1/core/status`                 | Core → all  | 1   | yes      | Core presence                |
| `{prefix}/v1/nodes/{deviceId}/up/register`  | node → Core | 1 | no | registration request         |
| `{prefix}/v1/nodes/{deviceId}/up/heartbeat` | node → Core | 0 | no | telemetry                    |
| `{prefix}/v1/nodes/{deviceId}/up/ack`       | node → Core | 1 | no | "I received this attempt"    |
| `{prefix}/v1/nodes/{deviceId}/up/started`   | node → Core | 1 | no | "I started this attempt" (optional) |
| `{prefix}/v1/nodes/{deviceId}/up/result`    | node → Core | 1 | no | the attempt's result         |
| `{prefix}/v1/nodes/{deviceId}/up/status`    | node → Core | 1 | yes | presence / last will        |
| `{prefix}/v1/nodes/{deviceId}/down/registered` | Core → node | 1 | no | registration answer       |
| `{prefix}/v1/nodes/{deviceId}/down/task`       | Core → node | 1 | no | one attempt of one task   |
| `{prefix}/v1/nodes/{deviceId}/down/cancel`     | Core → node | 1 | no | drop this attempt         |
| `{prefix}/v1/nodes/{deviceId}/down/control`    | Core → node | 1 | no | e.g. register again       |

**Core takes the device id from the topic, never from a body.** Every
message body is limited to 64 KiB.

## Registration

`up/register`

```json
{
  "requestId": "0f6d5f4e-1c1e-4f3e-9a35-7c1f3c2b9d10",
  "deviceId": "android-7fbacc0c-bb51-4230-8790-79be08e3a18b",
  "displayName": "samsung-SM-S916B",
  "hardwareClass": "ANDROID",
  "architecture": "arm64-v8a",
  "cpuCores": 8,
  "memoryMb": 7072,
  "adapterType": "android",
  "capabilities": [],
  "protocolFeatures": ["attempt-id"],
  "protocolVersion": 1,
  "enrollmentToken": "…"
}
```

| Field             | Rule                                                                       |
| ----------------- | -------------------------------------------------------------------------- |
| `requestId`       | New random id per registration attempt. Repeating a request with the same id returns the same answer, so retrying after a lost answer is safe. |
| `deviceId`        | Optional; if present it must equal the topic's device id                   |
| `displayName`, `hardwareClass`, `adapterType`, `cpuCores` > 0, `memoryMb` > 0 | required, exactly as for HTTP |
| `enrollmentToken` | Required when Core is configured with one                                  |
| `protocolVersion` | Optional; must be `1` if present                                           |

`down/registered`, accepted:

```json
{
  "requestId": "0f6d5f4e-1c1e-4f3e-9a35-7c1f3c2b9d10",
  "status": "ok",
  "nodeId": "b984a32e-83f4-4566-bb77-d97a68926bb2",
  "sessionToken": "QtCT_0RBVgyulLs…",
  "heartbeatIntervalSeconds": 5,
  "coreInstanceId": "932f3f75-a3eb-42f6-b4d5-e104cbc381ad",
  "protocolVersion": 1
}
```

Keep `sessionToken` in memory and put it in **every** later message
(heartbeat, ack, started, result). Registering again issues a new token and
invalidates the old one. Ignore answers whose `requestId` is not the one you
sent last.

`down/registered`, refused:

```json
{ "requestId": "…", "status": "error", "message": "enrollment_rejected" }
```

| `message`                       | Meaning                                         |
| ------------------------------- | ----------------------------------------------- |
| `invalid_registration`          | the body is not JSON                            |
| `device_id_mismatch`            | body `deviceId` differs from the topic          |
| `unsupported_protocol_version`  | `protocolVersion` is not 1                      |
| `enrollment_rejected`           | missing or wrong enrollment token               |
| e.g. `adapterType is required`  | a field failed validation                       |

A device keeps a single node identity across transports: if it registered
over HTTP before, registering over MQTT returns the same `nodeId`, and from
then on Core reaches it over MQTT.

## Heartbeats

`up/heartbeat`, QoS 0, every `heartbeatIntervalSeconds`:

```json
{
  "sessionToken": "…",
  "cpuUsagePercent": 12.5,
  "availableRamMb": 1878,
  "activeTasks": 0,
  "queueLength": 0,
  "networkLatencyMs": 40.0,
  "energyCostEstimate": 3.0
}
```

The same fields as the HTTP heartbeat. Omit `cpuUsagePercent` (or send `-1`)
if the platform cannot measure it; it is then recorded as unknown, never as
0 %. Node health (`ONLINE` → `DEGRADED` after 20 s without a heartbeat →
`OFFLINE` after 60 s) is decided from these heartbeats exactly as for HTTP
nodes.

A heartbeat Core cannot accept (unknown device, stale token) is answered on
`down/control` with `{"type":"reregister","reason":"…"}`, at most once every
5 s.

## Tasks

`down/task`:

```json
{
  "taskId": "task-001",
  "type": "Monte-Carlo-Pi",
  "samples": 2000000,
  "attemptId": "7b1adffa-a4c8-4621-90e3-293d26b907b3",
  "attemptNumber": 1
}
```

The same body as the HTTP transports. **Over MQTT the attempt id is always
present and must be echoed.** `attemptId` is the identity of this attempt:
opaque, globally unique, echoed verbatim, never interpreted.
`attemptNumber` (1, 2, 3 …) is informational.

The node answers, each time with `sessionToken`, `taskId` and `attemptId`:

1. `up/ack` **immediately on receipt**, before any work. This is how Core
   knows the task was delivered; a QoS 1 acknowledgement only comes from the
   broker.
   ```json
   { "sessionToken": "…", "taskId": "task-001", "attemptId": "7b1adffa-…" }
   ```
2. `up/started` when execution begins (optional, recommended).
3. `up/result`:
   ```json
   {
     "sessionToken": "…",
     "taskId": "task-001",
     "attemptId": "7b1adffa-…",
     "status": "SUCCESS",
     "outputData": "Computed Monte-Carlo pi ~= 3.1417",
     "executionTimeMs": 230
   }
   ```
   `status` `SUCCESS` completes the task; any other status is a final
   execution error (the task is not retried).

A report counts only if it names an attempt Core is waiting for, comes from
the device that attempt was sent to, names the same task and carries the
current session token. Anything else is dropped. There is no unfenced legacy
mode over MQTT.

`down/cancel` tells the node Core stopped waiting for an attempt:

```json
{ "taskId": "task-001", "attemptId": "7b1adffa-…", "reason": "execution_timeout" }
```

`reason` is `delivery_timeout`, `execution_timeout`, `node_offline`,
`transport_error` or `interrupted`. The node should not start the attempt, or
should stop it, and need not report it. A result sent anyway is ignored.

## Timeouts and failure classification

Core uses the same timeout policy as the HTTP transports
(`phoenix.deliveryTimeoutSeconds`, `phoenix.taskTimeoutSeconds`,
`phoenix.contactLivenessSeconds`):

| What happened                                    | Failure reason          | Re-placed? | Node `DEGRADED`? |
| ------------------------------------------------ | ----------------------- | ---------- | ---------------- |
| No ack in the delivery window, node heard from recently | `DELIVERY_TIMEOUT` | yes | no |
| No ack in the delivery window, node silent       | `NODE_UNREACHABLE`      | yes        | yes              |
| Ack, but no result in the execution window       | `EXECUTION_TIMEOUT`     | yes        | yes              |
| Core itself not connected to the broker          | `TRANSPORT_UNAVAILABLE` | yes        | no               |
| Node declared `OFFLINE` while Core waited        | `NODE_OFFLINE`          | yes        | no (already OFFLINE) |
| Node reported a non-`SUCCESS` result             | `EXECUTION_ERROR`       | no         | no               |

"Heard from recently" means any authenticated message (or an `online`
status) within `phoenix.contactLivenessSeconds`. The execution window starts
at the ack.

If Core briefly loses the broker, a task waits for the link to come back
within the delivery window instead of failing at once.

## Idempotence: rules for agents

MQTT QoS 1 is at-least-once: any message may arrive twice. Agents must:

1. **Acknowledge every `down/task` delivery**, including duplicates.
2. **Never run the same `attemptId` twice.** Remember handled attempt ids
   (a bounded set is enough); for a duplicate, re-send the known result if
   there is one, otherwise do nothing more.
3. **Drop cancelled attempts.** Remember cancelled attempt ids; a cancel may
   overtake its task.
4. Treat duplicate `down/registered` answers by `requestId`.

Core is idempotent on its side: duplicate acks, starts and results change
nothing, and only the first result of an attempt counts. A retry of a task is
a *new* attempt with a new `attemptId`, which the agent runs (at-least-once
execution across attempts, as with HTTP).

## Core restarts and lost sessions

Core keeps its view of the nodes in memory. To let agents notice that Core
forgot them:

- Core publishes `{"state":"online","coreInstanceId":"…"}` retained on
  `core/status` whenever it connects, and `{"state":"offline",…}` when it
  stops. If Core dies, the broker publishes the same `offline` message as
  Core's last will.
- The `coreInstanceId` is new for every Core process. **When an agent sees
  `online` with an instance id different from the one in its registration
  answer, it registers again.**
- When the broker kept the agent's session across a reconnect, the agent can
  keep its token; if Core does not recognise it, Core sends
  `down/control` `{"type":"reregister"}`.

## Security model

| Layer                       | Protects against                                    | Configured in |
| --------------------------- | --------------------------------------------------- | ------------- |
| TLS with host name verification | eavesdropping, a fake broker                    | broker listener; agent CA file |
| Broker password per device  | unknown clients                                     | `passwd` (`add-device.sh`) |
| Broker ACL (`%u` patterns)  | a device publishing or reading as another device    | `deploy/mosquitto/acl` |
| Device id from the topic    | a device claiming another identity in a body        | Core |
| Enrollment token            | an admitted client registering without authorisation | `phoenix.mqtt.enrollmentTokenFile` |
| Session token               | stale agent copies; reports from an old registration | Core |
| Attempt fencing             | late or misdirected results completing a retry      | Core |

The ACL is the boundary that makes the device id trustworthy. Run the broker
with `allow_anonymous false` and the shipped ACL; without them, any client
could publish on any device's topics.

## Agent checklist

- [ ] Stable `deviceId`; broker user name = `deviceId`; client id = `deviceId`
- [ ] TLS with the CA file and host name verification; `cleanSession=false`
- [ ] Last will `{"state":"offline"}` retained on `up/status`; publish `online` after connecting
- [ ] Subscribe to `down/#` and `core/status`
- [ ] Register with a fresh `requestId` (and the enrollment token); keep `sessionToken`, `nodeId`, `coreInstanceId`
- [ ] Heartbeat every `heartbeatIntervalSeconds` with the session token
- [ ] On `down/task`: ack at once; skip handled or cancelled attempts; run; `started`; `result`, all echoing `attemptId`
- [ ] On `down/cancel`: remember the attempt id; stop or skip it
- [ ] Register again on `down/control` `reregister`, or on `core/status` `online` with a new `coreInstanceId`

[`agents/python/phoenix_mqtt_agent.py`](../agents/python/phoenix_mqtt_agent.py)
implements every item and is a working reference for other agents.
