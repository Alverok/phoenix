## Transport: PUSH (Linux, and future MQTT)

Push-capable agents (directly addressable, can accept inbound connections)
register with an extra field:

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
    "callbackPort": 9090
}
```

`callbackPort` tells Core where to push tasks directly — no TASK POLL, no
TaskQueueService involvement for this node.

`POST http://<node-ip>:<callbackPort>/execute` (Core -> node)

```json
{ "id": "task-001", "type": "Monte-Carlo-Pi", "samples": 2000000 }
```

Node responds **synchronously**, in the same HTTP response, once execution
finishes:

```json
{ "status": "SUCCESS", "outputData": "Computed Monte-Carlo pi ~= 3.14159", "executionTimeMs": 210 }
```

No separate `/results` call — the response body _is_ the result. This is
deliberately different from Android's pull+queue+correlate model; it's
simpler because a directly-addressable node doesn't need it.

### Timeout classification for push nodes (Issue #28)

Push has no Core-side queue, so there is no backlog phase. Core still reports
the same lifecycle and failure reasons as every other transport:

- the TCP connection to the node cannot be established, or the connect times
  out -> `NODE_UNREACHABLE` (the task never reached the node)
- the connection succeeded but the node does not answer within the execute
  window -> `EXECUTION_TIMEOUT` (the task reached the node)

Both remain retryable and both degrade the node, as before.
