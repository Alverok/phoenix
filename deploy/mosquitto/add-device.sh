#!/usr/bin/env bash
#
# Gives one node agent its own broker account.
#
#   ./add-device.sh PASSWORD_FILE DEVICE_ID
#
# The broker user name must be the agent's deviceId: the ACL (see ./acl)
# confines every user to phoenix/v1/nodes/<user name>/..., so a device can
# only publish as itself. Prints the generated password once; give it to the
# agent and do not store it anywhere else. Reload the broker afterwards
# (systemctl reload mosquitto, or send it SIGHUP).
set -euo pipefail

if [ "$#" -ne 2 ]; then
    sed -n '3,12p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
fi

file=$1
device=$2

if ! [[ "$device" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ ]]; then
    echo "add-device: '$device' is not a valid Phoenix device id ([A-Za-z0-9._-], max 128)" >&2
    exit 1
fi

password=$(openssl rand -base64 24 | tr -d '\n/+=' | cut -c1-24)

if [ -e "$file" ]; then
    mosquitto_passwd -b "$file" "$device" "$password"
else
    (umask 077 && mosquitto_passwd -c -b "$file" "$device" "$password")
fi

echo "$device $password"
