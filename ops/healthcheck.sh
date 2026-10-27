#!/usr/bin/env bash
#
# Checks every trading service and posts to Slack when one is down or comes
# back. Talks to the Slack webhook directly, so it still alerts when
# notification-service itself is the one that is down.
#
# Install on EC2 (once), running every 2 minutes on weekdays 08:00-16:00 IST:
#   crontab -e
#   */2 2-10 * * 1-5 /opt/trading-platform/ops/healthcheck.sh >> /opt/trading-platform/logs/healthcheck.log 2>&1
# (the host clock is UTC: 02:30-10:30 UTC = 08:00-16:00 IST)
#
# Alerts only on a change of state (down -> up, up -> down), not every run.

set -u

BASE_DIR="/opt/trading-platform"
STATE_FILE="$BASE_DIR/run/healthcheck.state"
ENV_FILE="$BASE_DIR/config/environment"

# name:port:mode  (mode actuator = /actuator/health must say UP; tcp = port must accept)
SERVICES=(
  "broker-auth-service:9090:actuator"
  "stock-service:8081:actuator"
  "market-stream-service:8089:actuator"
  "watchlist-service:9092:tcp"
  "signal-engine-service:8082:actuator"
  "balance-margin-service:9091:tcp"
  "portfolio-service:8083:actuator"
  "newsanalysis-service:8088:actuator"
  "notification-service:8087:actuator"
  "ai-service:8086:actuator"
)

if [ -f "$ENV_FILE" ]; then
  # shellcheck disable=SC1090
  set -a; . "$ENV_FILE"; set +a
fi

mkdir -p "$(dirname "$STATE_FILE")"
touch "$STATE_FILE"

notify() {
  local text="$1"
  echo "$(date '+%F %T') $text"
  if [ -n "${SLACK_WEBHOOK_URL:-}" ]; then
    curl -s -m 10 -X POST -H 'Content-Type: application/json' \
      --data "{\"text\": \"$text\"}" "$SLACK_WEBHOOK_URL" > /dev/null || true
  fi
}

is_up() {
  local port="$1" mode="$2"
  if [ "$mode" = "actuator" ]; then
    curl -s -m 5 "http://localhost:$port/actuator/health" | grep -q '"status":"UP"'
  else
    timeout 5 bash -c "</dev/tcp/localhost/$port" 2>/dev/null
  fi
}

down=()
for entry in "${SERVICES[@]}"; do
  IFS=: read -r name port mode <<< "$entry"

  if is_up "$port" "$mode"; then
    now="UP"
  else
    now="DOWN"
    down+=("$name")
  fi

  before=$(grep "^$name=" "$STATE_FILE" | cut -d= -f2)
  if [ "$before" != "$now" ]; then
    sed -i "/^$name=/d" "$STATE_FILE"
    echo "$name=$now" >> "$STATE_FILE"
    # No alert for the very first observation of a service that is UP.
    if [ "$now" = "DOWN" ]; then
      notify ":red_circle: TRADING PLATFORM: $name is DOWN (port $port)"
    elif [ -n "$before" ]; then
      notify ":large_green_circle: TRADING PLATFORM: $name is back UP"
    fi
  fi
done

[ ${#down[@]} -eq 0 ] && exit 0 || exit 1
