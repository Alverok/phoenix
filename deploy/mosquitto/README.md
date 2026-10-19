# Mosquitto broker for Phoenix

Everything needed to run the MQTT broker for the MQTT/TLS node-agent
transport ([protocol](../../docs/mqtt-agent-protocol.md)): a Mosquitto 2.x
configuration, the access-control list, and two scripts. The integration
tests (`MqttMosquittoIntegrationTest`) start a broker from these same files.

| File             | Purpose                                                         |
| ---------------- | --------------------------------------------------------------- |
| `mosquitto.conf` | Loopback listener for Core, TLS listener for nodes, optional TLS-PSK listener for ESP32 |
| `acl`            | Core may use all Phoenix topics; each node only its own         |
| `gen-certs.sh`   | Private CA, broker certificate, Core client certificate         |
| `add-device.sh`  | One broker account per node agent                               |

## Deployment

The design deploys Core and the broker on the same control node. Core then
talks to the broker over loopback and only node traffic needs TLS:

```text
 Phoenix Core ──tcp://127.0.0.1:1883──> Mosquitto <──ssl://broker:8883── node agents
```

### 1. Install

```bash
sudo apt install mosquitto mosquitto-clients
sudo mkdir -p /etc/mosquitto/phoenix
```

### 2. Certificates

Name every host name and address nodes will use to reach the broker:

```bash
sudo ./gen-certs.sh /etc/mosquitto/phoenix/certs broker.lan 192.168.1.20
```

Move `certs/ca.key` somewhere offline once you have issued everything you
need. Nodes only need `ca.pem`.

### 3. Accounts

```bash
# Core
sudo ./add-device.sh /etc/mosquitto/phoenix/passwd phoenix-core
# Each node agent: the user name MUST be the agent's deviceId
sudo ./add-device.sh /etc/mosquitto/phoenix/passwd android-7fbacc0c-bb51-4230-8790-79be08e3a18b
```

Each call prints `<user> <password>` once. Give Core its password through a
file (`phoenix.mqtt.passwordFile`) or `PHOENIX_MQTT_PASSWORD`, and each agent
its own.

### 4. Configuration and permissions

```bash
sudo cp mosquitto.conf /etc/mosquitto/conf.d/phoenix.conf
sudo cp acl /etc/mosquitto/phoenix/acl
sudo chown -R mosquitto:mosquitto /etc/mosquitto/phoenix
sudo chmod 0700 /etc/mosquitto/phoenix/certs
sudo chmod 0600 /etc/mosquitto/phoenix/passwd /etc/mosquitto/phoenix/acl \
                /etc/mosquitto/phoenix/certs/*.key
sudo systemctl restart mosquitto
```

Mosquitto 2 warns about, and future versions refuse, world-readable password
and ACL files.

### 5. Start Core against it

```bash
openssl rand -hex 24 > ~/.phoenix/enrollment-token   # share with your agents

./gradlew --console=plain run \
  -Dphoenix.advertiseAddress=192.168.1.20 \
  -Dphoenix.mqtt.url=tcp://127.0.0.1:1883 \
  -Dphoenix.mqtt.username=phoenix-core \
  -Dphoenix.mqtt.passwordFile=$HOME/.phoenix/core-broker-password \
  -Dphoenix.mqtt.enrollmentTokenFile=$HOME/.phoenix/enrollment-token
```

The console shows `MQTT ●` (green) once Core is connected.

If the broker runs on another machine, use TLS from Core as well:

```bash
  -Dphoenix.mqtt.url=ssl://broker.lan:8883 \
  -Dphoenix.mqtt.tls.caFile=/path/to/ca.pem
```

and, for brokers that require client certificates,
`-Dphoenix.mqtt.tls.keyStore=.../core.p12` with
`-Dphoenix.mqtt.tls.keyStorePasswordFile=.../core.p12.password`.

## Checking the setup

Watch all Phoenix traffic (with Core's credentials):

```bash
mosquitto_sub -h 127.0.0.1 -u phoenix-core -P "$(cat ~/.phoenix/core-broker-password)" \
  -i phoenix-wiretap -t 'phoenix/#' -v
```

Confirm a node cannot publish as another node (nothing should arrive):

```bash
mosquitto_pub -h broker.lan -p 8883 --cafile ca.pem -u node-a -P '…' \
  -t phoenix/v1/nodes/node-b/up/heartbeat -m '{}' -q 1
```

## ESP32 with TLS-PSK

Uncomment the `8884` listener in `mosquitto.conf` and create
`/etc/mosquitto/phoenix/psk` (mode 0600) with one line per device:

```text
esp32-01:3f1e9a6c0b5d4e7f8a9b0c1d2e3f4a5b
```

The PSK identity is used as the user name, so the same ACL confines the
device to its own topics. This combination (own topic delivered, foreign
topic dropped, wrong key refused) was checked against Mosquitto 2.0.18.
