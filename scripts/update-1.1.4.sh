#!/usr/bin/env bash
set -euo pipefail

if [[ ${EUID:-$(id -u)} -ne 0 ]]; then
  echo "Run this updater as root: sudo ./scripts/update-1.1.4.sh" >&2
  exit 1
fi

SOURCE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_DIR=/opt/crystal-planner

if [[ ! -d "$APP_DIR/src" || ! -f "$APP_DIR/package.json" ]]; then
  echo "Crystal Planner is not installed in $APP_DIR. Use scripts/install-debian.sh from the full archive instead." >&2
  exit 1
fi

if ! command -v node >/dev/null 2>&1 || ! command -v npm >/dev/null 2>&1; then
  echo "Node.js and npm are required." >&2
  exit 1
fi

NODE_MAJOR="$(node -p 'Number(process.versions.node.split(".")[0])')"
if [[ "$NODE_MAJOR" -lt 20 ]]; then
  echo "Node.js 20+ is required; found $(node --version)." >&2
  exit 1
fi

install -d -m 0755 "$APP_DIR/src/discord"
install -m 0644 "$SOURCE_DIR/src/index.js" "$APP_DIR/src/index.js"
install -m 0644 "$SOURCE_DIR/src/discord/presence.js" "$APP_DIR/src/discord/presence.js"
install -m 0644 "$SOURCE_DIR/package.json" "$APP_DIR/package.json"

cd "$APP_DIR"
npm install --omit=dev --no-audit --no-fund

systemctl restart crystal-planner.service

echo "Crystal Planner Server 1.1.4 installed and restarted."
echo "Check status: systemctl status crystal-planner"
echo "Follow logs:  journalctl -u crystal-planner -f"
