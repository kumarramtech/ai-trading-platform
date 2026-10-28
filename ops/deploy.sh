#!/usr/bin/env bash
#
# Builds the given services, uploads their jars, verifies checksums, backs up
# the running jars, restarts the platform and checks health.
#
#   ops/deploy.sh <ec2-ip> <service> [<service> ...]
#   ops/deploy.sh 13.200.231.191 signal-engine-service watchlist-service
#
# Never during market hours (09:15-15:30 IST, Mon-Fri): the script refuses.
# Key: TRADING_KEY (default "E:/AI Trading Project/trading-platform-key.pem").

set -euo pipefail

if [ $# -lt 2 ]; then
  echo "usage: $0 <ec2-ip> <service> [<service> ...]" >&2
  exit 2
fi

HOST="ubuntu@$1"
shift
SERVICES=("$@")
KEY="${TRADING_KEY:-E:/AI Trading Project/trading-platform-key.pem}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STAMP="$(date +%Y%m%d-%H%M)"
STAGE="/tmp/deploy-$STAMP"
SSH=(ssh -o ConnectTimeout=20 -i "$KEY" "$HOST")

# --- market-hours guard (IST) -------------------------------------------------
# POSIX offset form: Git Bash on Windows has no tz database, so "Asia/Kolkata"
# silently falls back to UTC (2026-10-28: refused a 17:00 IST deploy as 11:29).
ist_day=$(TZ=IST-5:30 date +%u)
ist_hm=$(TZ=IST-5:30 date +%H%M)
if [ "$ist_day" -le 5 ] && [ "$ist_hm" -ge 0915 ] && [ "$ist_hm" -le 1530 ]; then
  echo "Refusing to deploy during market hours (IST $ist_hm). Deploy after 15:30." >&2
  exit 1
fi

# --- build --------------------------------------------------------------------
JARS=()
for s in "${SERVICES[@]}"; do
  echo "== build $s"
  (cd "$ROOT/$s" && mvn -q clean package -DskipTests)
  JARS+=("$ROOT/$s/target/$s-0.0.1-SNAPSHOT.jar")
done

# --- no open paper trades -----------------------------------------------------
open=$("${SSH[@]}" 'set -a; . /opt/trading-platform/config/environment; set +a;
  H=$(echo "$SPRING_DATASOURCE_URL" | sed -E "s#jdbc:mysql://([^:/]+).*#\1#");
  D=$(echo "$SPRING_DATASOURCE_URL" | sed -E "s#jdbc:mysql://[^/]+/([^?]+).*#\1#");
  MYSQL_PWD="$SPRING_DATASOURCE_PASSWORD" mysql -N -h "$H" -u "$SPRING_DATASOURCE_USERNAME" "$D" \
    -e "select count(*) from paper_trade where status=\"OPEN\""')
if [ "$open" != "0" ]; then
  echo "Refusing to deploy: $open open paper trade(s)." >&2
  exit 1
fi

# --- upload and verify --------------------------------------------------------
"${SSH[@]}" "mkdir -p $STAGE"
scp -q -i "$KEY" "${JARS[@]}" "$HOST:$STAGE/"
for jar in "${JARS[@]}"; do
  local_sum=$(sha256sum "$jar" | cut -d' ' -f1)
  remote_sum=$("${SSH[@]}" "sha256sum $STAGE/$(basename "$jar") | cut -d' ' -f1")
  if [ "$local_sum" != "$remote_sum" ]; then
    echo "Checksum mismatch for $(basename "$jar")" >&2
    exit 1
  fi
done
echo "== uploaded and verified ${#JARS[@]} jar(s)"

# --- back up, swap, restart -----------------------------------------------------
"${SSH[@]}" "set -e
  B=/opt/trading-platform/backup/pre-deploy-$STAMP
  mkdir -p \$B
  cp -p /opt/trading-platform/services/*.jar \$B/
  cd /opt/trading-platform
  bash scripts/stop.sh > /dev/null
  cp $STAGE/*.jar services/
  bash scripts/start.sh | grep -E '\[OK\]|FAIL|ERROR' || true
  echo backup: \$B"

# --- health ---------------------------------------------------------------------
if "${SSH[@]}" 'bash /opt/trading-platform/ops/healthcheck.sh > /dev/null 2>&1'; then
  echo "== all services healthy"
else
  echo "== WARNING: health check reports a service down; see logs/healthcheck.log" >&2
  exit 1
fi
