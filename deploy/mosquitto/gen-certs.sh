#!/usr/bin/env bash
#
# Creates a private CA and the certificates for a Phoenix MQTT broker.
#
#   ./gen-certs.sh OUT_DIR BROKER_HOST [BROKER_HOST ...]
#
# BROKER_HOST is every name or IP address nodes use to reach the broker
# (for example: broker.lan 192.168.1.20). The broker certificate is valid
# for exactly those, so nodes can verify the host name.
#
# Writes to OUT_DIR:
#   ca.pem / ca.key        the CA (keep ca.key offline; nodes need only ca.pem)
#   server.pem / .key      the broker's certificate and key (for mosquitto.conf)
#   core.pem / core.key    a client certificate for Phoenix Core (optional mTLS)
#   core.p12               the same as PKCS#12 (phoenix.mqtt.tls.keyStore)
#   core.p12.password      its password (phoenix.mqtt.tls.keyStorePasswordFile)
#
# Keys are EC P-256. Existing files are never overwritten.
set -euo pipefail

if [ "$#" -lt 2 ]; then
    sed -n '3,20p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
fi

out=$1
shift
days=${PHOENIX_CERT_DAYS:-825}

mkdir -p "$out"
umask 077
cd "$out"

if [ -e ca.pem ] || [ -e server.pem ]; then
    echo "gen-certs: $out already contains certificates; refusing to overwrite" >&2
    exit 1
fi

san=""
for host in "$@"; do
    if [[ "$host" =~ ^[0-9.]+$ || "$host" == *:* ]]; then
        san="${san:+$san,}IP:$host"
    else
        san="${san:+$san,}DNS:$host"
    fi
done

newkey() {
    openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$1" 2>/dev/null
}

# Certificate authority.
newkey ca.key
openssl req -x509 -new -key ca.key -sha256 -days "$days" -out ca.pem \
    -subj "/CN=Phoenix MQTT CA" \
    -addext "basicConstraints=critical,CA:TRUE" \
    -addext "keyUsage=critical,keyCertSign,cRLSign"

sign() { # name subject extensions
    newkey "$1.key"
    openssl req -new -key "$1.key" -out "$1.csr" -subj "$2"
    printf '%s\n' "$3" > "$1.ext"
    openssl x509 -req -in "$1.csr" -CA ca.pem -CAkey ca.key -CAcreateserial \
        -sha256 -days "$days" -out "$1.pem" -extfile "$1.ext" 2>/dev/null
    rm -f "$1.csr" "$1.ext"
}

sign server "/CN=$1" "subjectAltName=$san
extendedKeyUsage=serverAuth
keyUsage=critical,digitalSignature"

sign core "/CN=phoenix-core" "extendedKeyUsage=clientAuth
keyUsage=critical,digitalSignature"

openssl rand -base64 24 | tr -d '\n' > core.p12.password
openssl pkcs12 -export -inkey core.key -in core.pem -certfile ca.pem \
    -name phoenix-core -out core.p12 -passout file:core.p12.password

rm -f ca.srl
chmod 644 ca.pem server.pem core.pem

echo "gen-certs: wrote CA, broker certificate for [$san] and Core client certificate to $out"
