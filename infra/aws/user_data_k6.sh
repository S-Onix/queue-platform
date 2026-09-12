#!/bin/bash
set -eux
apt-get update
apt-get install -y ca-certificates curl
curl -fsSL https://github.com/grafana/k6/releases/download/v0.54.0/k6-v0.54.0-linux-arm64.tar.gz \
  | tar -xz -C /tmp
install -m 0755 /tmp/k6-v0.54.0-linux-arm64/k6 /usr/local/bin/k6
