# Push agent protocol (Linux)

Directly addressable agents, typically Linux machines on the LAN, receive
tasks by **push**: Core opens a connection to the agent instead of waiting
to be polled. Registration and heartbeats are identical to the pull agents
described in the [README](../README.md#node-agent-http-protocol).

## Registration

Push agents add `callbackPort`, the port their `/execute` endpoint listens on.
Core combines it with the source address of the registration request.

`POST /register`

```json
{
    "deviceId": "linux-...",
    "displayName": "arch-laptop",
    "hardwareClass": "LINUX",
    "architecture": "x86_64",
    "cpuCores": 8,
    "memoryMb": 15872,
    "adapterType": "linux",
    "capabilities": [],
    "protocolFeatures": ["attempt-id"],
    "callbackPort": 9090
}
```

`callbackPort` must be between 1 and 65535. `protocolFeatures` is optional;
see [attempt identity](#attempt-identity) below.

## Task execution

`POST http://<node-ip>:<callbackPort>/execute` (Core to node)

```json
{ "taskId": "task-001", "type": "Monte-Carlo-Pi", "samples": 2000000 }
```

The node answers **synchronously**, in the same HTTP response, once
execution has finished. There is no separate `/results` call: the response
body is the result.

```json
{ "status": "SUCCESS", "outputData": "Computed Monte-Carlo pi ~= 3.14159", "executionTimeMs": 210 }
```

| Node response                         | Outcome in Core                      |
| ------------------------------------- | ------------------------------------ |
| `200` with `"status": "SUCCESS"`      | `COMPLETED`                          |
| `200` with any other `status`         | `FAILED` / `EXECUTION_ERROR`         |
| `4xx`                                 | `FAILED` / `EXECUTION_ERROR`         |
| `5xx`                                 | `FAILED` / `TRANSPORT_ERROR`         |
| `200` with a body that is not a result | `FAILED` / `TRANSPORT_ERROR`        |

`EXECUTION_ERROR` is final; `TRANSPORT_ERROR` is re-placed on another node.

## Attempt identity

If the agent announced `"attempt-id"`, the request also carries the attempt
identity:

```json
{ "taskId": "task-001", "type": "Monte-Carlo-Pi", "samples": 2000000,
  "attemptId": "3f6c1b7e-9a1d-4c52-8a0e-5d2b7f61c9aa", "attemptNumber": 2 }
```

For push, the HTTP connection itself already ties the result to the attempt,
so the agent does not need to echo `attemptId`; it is provided for the
agent's own logs. Agents that do not announce the feature never receive
these fields.

## Timeouts and failure classification

Push has no Core-side queue, so there is no backlog phase. Writing the
request body is the delivery boundary.

| What happened                                        | Failure reason       | Node degraded? |
| ---------------------------------------------------- | -------------------- | -------------- |
| Connection refused or connect timed out (5 s)        | `NODE_UNREACHABLE`   | Yes            |
| Delivered, no response within `phoenix.taskTimeoutSeconds` | `EXECUTION_TIMEOUT` | Yes      |
| Delivered, connection broke before the response      | `TRANSPORT_ERROR`    | Yes            |
| Node declared `OFFLINE` while Core was waiting       | `NODE_OFFLINE`       | No             |

All four are retryable. On `NODE_OFFLINE` Core closes the connection
immediately rather than waiting for the execution timeout; the outcome on the
node is unknown, and the task is re-placed as a fresh attempt.
