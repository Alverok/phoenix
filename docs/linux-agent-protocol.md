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
