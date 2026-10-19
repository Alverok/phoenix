#!/usr/bin/env python3
"""Phoenix reference node agent for Linux, speaking the MQTT/TLS protocol.

It implements docs/mqtt-agent-protocol.md (protocol version 1):

  * registers (with the enrollment token, if Core requires one) and keeps
    the session token Core returns;
  * sends heartbeats with real telemetry from /proc;
  * acknowledges each task, executes it (Monte-Carlo pi), reports start and
    result, all echoing the attempt id;
  * is idempotent: a redelivered attempt is acknowledged again but never run
    twice, and its result is re-sent if it is already known;
  * drops attempts Core cancelled;
  * registers again when Core restarts or asks it to.

Requires Python 3.9+ and paho-mqtt 2.x (pip install "paho-mqtt>=2,<3").

Example, against a broker set up with deploy/mosquitto:

  python3 phoenix_mqtt_agent.py --broker broker.lan --ca ca.pem \\
      --password-file ~/.phoenix/broker-password \\
      --enrollment-token-file ~/.phoenix/enrollment-token
"""

import argparse
import collections
import json
import os
import platform
import queue
import random
import ssl
import threading
import time
import uuid
from pathlib import Path

import paho.mqtt.client as mqtt

PROTOCOL_VERSION = 1
QOS = 1


# ----------------------------------------------------------------------------
# telemetry
# ----------------------------------------------------------------------------

def available_ram_mb():
    try:
        with open("/proc/meminfo") as meminfo:
            for line in meminfo:
                if line.startswith("MemAvailable:"):
                    return int(line.split()[1]) // 1024
    except OSError:
        pass
    return 0


def total_ram_mb():
    try:
        with open("/proc/meminfo") as meminfo:
            for line in meminfo:
                if line.startswith("MemTotal:"):
                    return int(line.split()[1]) // 1024
    except OSError:
        pass
    return 1


class CpuSampler:
    """CPU usage between two calls, from /proc/stat; -1 if unknown."""

    def __init__(self):
        self._last = self._read()

    @staticmethod
    def _read():
        try:
            with open("/proc/stat") as stat:
                fields = [int(value) for value in stat.readline().split()[1:]]
            idle = fields[3] + (fields[4] if len(fields) > 4 else 0)
            return sum(fields), idle
        except (OSError, ValueError, IndexError):
            return None

    def usage_percent(self):
        now = self._read()
        last, self._last = self._last, now
        if now is None or last is None or now[0] == last[0]:
            return -1.0
        total = now[0] - last[0]
        idle = now[1] - last[1]
        return round(100.0 * (total - idle) / total, 1)


# ----------------------------------------------------------------------------
# work
# ----------------------------------------------------------------------------

def monte_carlo_pi(samples):
    inside = 0
    rnd = random.random
    for _ in range(samples):
        x = rnd()
        y = rnd()
        if x * x + y * y <= 1.0:
            inside += 1
    return 4.0 * inside / samples


def execute(task):
    kind = task.get("type")
    if kind != "Monte-Carlo-Pi":
        return "FAILED", "Unsupported task type: %s" % kind
    samples = int(task.get("samples", 2_000_000))
    if samples <= 0 or samples > 200_000_000:
        return "FAILED", "Invalid sample count: %s" % samples
    return "SUCCESS", "Computed Monte-Carlo pi ~= %.4f" % monte_carlo_pi(samples)


# ----------------------------------------------------------------------------
# agent
# ----------------------------------------------------------------------------

class Agent:

    def __init__(self, args):
        self.args = args
        self.device_id = args.device_id
        self.base = "%s/v%d" % (args.topic_prefix, PROTOCOL_VERSION)
        self.up = "%s/nodes/%s/up/" % (self.base, self.device_id)
        self.down = "%s/nodes/%s/down/" % (self.base, self.device_id)
        self.core_status = self.base + "/core/status"

        self.session_token = None
        self.node_id = None
        self.core_instance = None
        self.request_id = None
        self.request_sent_at = 0.0
        self.latency_ms = 0.0
        self.heartbeat_interval = args.heartbeat_interval
        self.active = 0

        self.lock = threading.Lock()
        self.work = queue.Queue()
        self.cancelled = set()
        # attempt id -> result body (None while running); bounded.
        self.handled = collections.OrderedDict()
        self.cpu = CpuSampler()
        self.stopping = threading.Event()

        self.client = mqtt.Client(
            callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
            client_id=self.device_id,
            clean_session=False,
            protocol=mqtt.MQTTv311,
        )
        self.client.max_inflight_messages_set(100)
        self.client.reconnect_delay_set(min_delay=1, max_delay=30)
        self.client.will_set(self.up + "status", json.dumps({"state": "offline"}), QOS, retain=True)

        if args.username or args.password:
            self.client.username_pw_set(args.username or self.device_id, args.password)

        if not args.insecure:
            context = ssl.create_default_context(cafile=args.ca)
            context.minimum_version = ssl.TLSVersion.TLSv1_2
            self.client.tls_set_context(context)

        self.client.on_connect = self.on_connect
        self.client.on_disconnect = self.on_disconnect
        self.client.on_message = self.on_message

    # -- connection -----------------------------------------------------------

    def run(self):
        threading.Thread(target=self.worker, name="phoenix-worker", daemon=True).start()
        threading.Thread(target=self.heartbeats, name="phoenix-heartbeat", daemon=True).start()
        self.client.connect_async(self.args.broker, self.args.port, keepalive=30)
        self.client.loop_start()
        log("connecting to %s:%d as %s" % (self.args.broker, self.args.port, self.device_id))
        try:
            while not self.stopping.wait(1):
                pass
        except KeyboardInterrupt:
            pass
        finally:
            self.publish("status", {"state": "offline"}, retain=True)
            self.client.disconnect()
            self.client.loop_stop()

    def on_connect(self, client, userdata, flags, reason_code, properties):
        if reason_code.is_failure:
            log("broker refused the connection: %s" % reason_code)
            return
        client.subscribe([(self.down + "#", QOS), (self.core_status, QOS)])
        self.publish("status", {"state": "online"}, retain=True)
        log("connected (session present: %s)" % flags.session_present)
        # A broker that kept our session also kept Core's view of us; Core
        # asks us to register again (control/reregister) if it forgot us.
        if self.session_token is None or not flags.session_present:
            self.register()

    def on_disconnect(self, client, userdata, flags, reason_code, properties):
        log("disconnected: %s; reconnecting" % reason_code)

    def publish(self, kind, body, qos=QOS, retain=False):
        self.client.publish(self.up + kind, json.dumps(body), qos=qos, retain=retain)

    # -- registration ----------------------------------------------------------

    def register(self):
        self.request_id = str(uuid.uuid4())
        self.request_sent_at = time.monotonic()
        body = {
            "requestId": self.request_id,
            "deviceId": self.device_id,
            "displayName": self.args.display_name,
            "hardwareClass": "LINUX",
            "architecture": platform.machine() or "unknown",
            "cpuCores": os.cpu_count() or 1,
            "memoryMb": total_ram_mb(),
            "adapterType": "linux",
            "capabilities": self.args.capability or [],
            "protocolFeatures": ["attempt-id"],
            "protocolVersion": PROTOCOL_VERSION,
        }
        if self.args.enrollment_token:
            body["enrollmentToken"] = self.args.enrollment_token
        self.publish("register", body)

    def on_registered(self, body):
        if body.get("requestId") != self.request_id:
            return  # an answer to an earlier request
        if body.get("status") != "ok":
            log("registration refused: %s" % body.get("message"))
            return
        with self.lock:
            self.session_token = body["sessionToken"]
            self.node_id = body["nodeId"]
            self.core_instance = body.get("coreInstanceId")
        self.heartbeat_interval = min(
            self.args.heartbeat_interval, int(body.get("heartbeatIntervalSeconds", 5)))
        self.latency_ms = round((time.monotonic() - self.request_sent_at) * 1000.0, 1)
        log("registered as node %s (round trip %.0f ms)" % (self.node_id, self.latency_ms))
        self.send_heartbeat()

    # -- inbound ---------------------------------------------------------------

    def on_message(self, client, userdata, message):
        try:
            body = json.loads(message.payload.decode("utf-8"))
        except (UnicodeDecodeError, ValueError):
            return
        topic = message.topic

        if topic == self.core_status:
            # Core restarted (new instance) or came back: register again.
            if body.get("state") == "online" and body.get("coreInstanceId") != self.core_instance:
                if self.core_instance is not None:
                    log("Core restarted; registering again")
                    self.register()
            return

        kind = topic[len(self.down):]
        if kind == "registered":
            self.on_registered(body)
        elif kind == "task":
            self.on_task(body)
        elif kind == "cancel":
            with self.lock:
                self.cancelled.add(body.get("attemptId"))
            log("attempt %s of %s cancelled (%s)"
                % (str(body.get("attemptId"))[:8], body.get("taskId"), body.get("reason")))
        elif kind == "control" and body.get("type") == "reregister":
            log("Core asked to register again (%s)" % body.get("reason"))
            self.register()

    def on_task(self, task):
        attempt_id = task.get("attemptId")
        if not attempt_id or "taskId" not in task:
            return

        self.report("ack", task)

        with self.lock:
            if attempt_id in self.handled:
                done = self.handled[attempt_id]
                if done is not None:
                    self.publish("result", done)  # re-send a known result
                return  # duplicate delivery: never run an attempt twice
            if attempt_id in self.cancelled:
                return
            self.handled[attempt_id] = None
            while len(self.handled) > 1024:
                self.handled.popitem(last=False)

        self.work.put(task)

    def report(self, kind, task, **extra):
        with self.lock:
            token = self.session_token
        body = {"sessionToken": token, "taskId": task["taskId"], "attemptId": task["attemptId"]}
        body.update(extra)
        self.publish(kind, body)
        return body

    # -- execution ---------------------------------------------------------------

    def worker(self):
        while True:
            task = self.work.get()
            attempt_id = task["attemptId"]
            with self.lock:
                if attempt_id in self.cancelled:
                    continue
                self.active += 1
            self.report("started", task)
            started = time.monotonic()
            try:
                status, output = execute(task)
            except Exception as error:  # report, never die
                status, output = "FAILED", "Agent error: %s" % error
            elapsed_ms = int((time.monotonic() - started) * 1000)
            with self.lock:
                self.active -= 1
                cancelled = attempt_id in self.cancelled
            if cancelled:
                log("discarding result of cancelled attempt %s" % attempt_id[:8])
                continue
            body = self.report("result", task, status=status, outputData=output,
                               executionTimeMs=elapsed_ms)
            with self.lock:
                self.handled[attempt_id] = body
            log("%s (attempt %d) -> %s in %d ms"
                % (task["taskId"], task.get("attemptNumber", 0), status, elapsed_ms))

    # -- telemetry ---------------------------------------------------------------

    def heartbeats(self):
        while not self.stopping.wait(self.heartbeat_interval):
            self.send_heartbeat()

    def send_heartbeat(self):
        with self.lock:
            token = self.session_token
            active = self.active
        if token is None or not self.client.is_connected():
            return
        self.publish("heartbeat", {
            "sessionToken": token,
            "deviceId": self.device_id,
            "cpuUsagePercent": self.cpu.usage_percent(),
            "availableRamMb": available_ram_mb(),
            "activeTasks": active,
            "queueLength": self.work.qsize(),
            "networkLatencyMs": self.latency_ms,
            "energyCostEstimate": self.args.energy_cost,
        }, qos=0)


# ----------------------------------------------------------------------------
# configuration
# ----------------------------------------------------------------------------

def stable_device_id(state_dir):
    """A device id that survives restarts, like the Android agent's."""
    path = Path(state_dir).expanduser() / "device-id"
    if path.exists():
        return path.read_text().strip()
    path.parent.mkdir(parents=True, exist_ok=True)
    device_id = "linux-" + str(uuid.uuid4())
    path.write_text(device_id + "\n")
    return device_id


def read_secret(path, variable):
    if path:
        return Path(path).expanduser().read_text().strip()
    return os.environ.get(variable)


def log(message):
    print("[phoenix-agent] %s %s" % (time.strftime("%H:%M:%S"), message), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--broker", required=True, help="broker host name (must match its certificate)")
    parser.add_argument("--port", type=int, default=None, help="default 8883, or 1883 with --insecure")
    parser.add_argument("--ca", help="CA certificate (PEM) that signed the broker certificate")
    parser.add_argument("--insecure", action="store_true", help="plain TCP, for a broker on this machine only")
    parser.add_argument("--device-id", help="default: generated once and kept in --state-dir")
    parser.add_argument("--state-dir", default="~/.phoenix-agent")
    parser.add_argument("--username", help="broker user name (default: the device id)")
    parser.add_argument("--password-file", help="broker password file (or PHOENIX_MQTT_PASSWORD)")
    parser.add_argument("--enrollment-token-file", help="or PHOENIX_MQTT_ENROLLMENT_TOKEN")
    parser.add_argument("--topic-prefix", default="phoenix")
    parser.add_argument("--display-name", default=platform.node() or "linux-node")
    parser.add_argument("--capability", action="append", help="scheduling tag; repeatable")
    parser.add_argument("--heartbeat-interval", type=int, default=5)
    parser.add_argument("--energy-cost", type=float, default=3.0)
    args = parser.parse_args()

    if args.port is None:
        args.port = 1883 if args.insecure else 8883
    if not args.insecure and not args.ca:
        parser.error("--ca is required for TLS (or use --insecure for a local broker)")
    if args.device_id is None:
        args.device_id = stable_device_id(args.state_dir)
    args.password = read_secret(args.password_file, "PHOENIX_MQTT_PASSWORD")
    args.enrollment_token = read_secret(args.enrollment_token_file, "PHOENIX_MQTT_ENROLLMENT_TOKEN")
    if args.password and not args.username:
        args.username = args.device_id

    Agent(args).run()


if __name__ == "__main__":
    main()
