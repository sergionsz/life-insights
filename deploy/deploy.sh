#!/usr/bin/env bash
#
# Build the server on this machine and install it on the VM.
#
#   deploy/deploy.sh ubuntu@recuer.de
#
# The distribution is plain JVM bytecode, so it does not matter that this laptop is arm64 and the
# server might not be. Nothing is compiled on the VM, which is the point: no Gradle, no JDK build
# toolchain and no memory pressure on a small instance.

set -euo pipefail

HOST="${1:-}"
if [ -z "$HOST" ]; then
    echo "usage: deploy/deploy.sh user@host" >&2
    exit 64
fi

cd "$(dirname "$0")/.."

echo "==> Building the distribution"
./gradlew :server:distTar --quiet

TARBALL=server/build/distributions/server.tar
[ -f "$TARBALL" ] || { echo "no tarball at $TARBALL" >&2; exit 1; }
echo "    $(du -h "$TARBALL" | cut -f1)"

echo "==> Copying to $HOST"
scp -q "$TARBALL" "$HOST:/tmp/life-insights.tar"

echo "==> Installing"
ssh "$HOST" 'set -eu
    sudo systemctl stop life-insights || true
    sudo rm -rf /opt/life-insights
    sudo mkdir -p /opt/life-insights
    sudo tar -C /opt/life-insights --strip-components=1 -xf /tmp/life-insights.tar
    # Root-owned and world-readable: the service user runs the code but cannot rewrite it.
    sudo chown -R root:root /opt/life-insights
    sudo chmod -R a+rX /opt/life-insights
    rm -f /tmp/life-insights.tar
    sudo systemctl start life-insights'

echo "==> Waiting for it to answer"
for attempt in $(seq 1 20); do
    if ssh "$HOST" 'curl -fsS --max-time 2 localhost:8080/health' >/dev/null 2>&1; then
        echo "    healthy"
        exit 0
    fi
    sleep 1
done

echo "    did not become healthy; last log lines:" >&2
ssh "$HOST" 'sudo journalctl -u life-insights -n 30 --no-pager' >&2
exit 1
