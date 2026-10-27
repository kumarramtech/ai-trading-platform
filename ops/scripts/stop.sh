#!/bin/bash

BASE_DIR="/opt/trading-platform"
RUN_DIR="$BASE_DIR/run"

echo "=============================================="
echo "   AI TRADING PLATFORM SHUTDOWN"
echo "=============================================="

stop_service() {
    local service_name="$1"
    local pid_file="$RUN_DIR/$service_name.pid"

    echo
    echo "Stopping: $service_name"

    if [ ! -f "$pid_file" ]; then
        echo "[SKIP] PID file not found"
        return
    fi

    local pid
    pid=$(cat "$pid_file")

    if ! kill -0 "$pid" 2>/dev/null; then
        echo "[SKIP] Process $pid is not running"
        rm -f "$pid_file"
        return
    fi

    echo "[INFO] Sending SIGTERM to PID $pid"
    kill "$pid"

    for i in {1..15}; do
        if ! kill -0 "$pid" 2>/dev/null; then
            echo "[OK] $service_name stopped"
            rm -f "$pid_file"
            return
        fi
        sleep 1
    done

    echo "[WARN] Graceful shutdown timed out"
    echo "[INFO] Sending SIGKILL to PID $pid"
    kill -9 "$pid" 2>/dev/null

    rm -f "$pid_file"
    echo "[OK] $service_name stopped"
}

stop_service "ai-service"
stop_service "notification-service"
stop_service "newsanalysis-service"
stop_service "portfolio-service"
stop_service "balance-margin-service"
stop_service "signal-engine-service"
stop_service "watchlist-service"
stop_service "market-stream-service"
stop_service "stock-service"
stop_service "broker-auth-service"

echo
echo "=============================================="
echo "   VERIFYING JAVA PROCESSES"
echo "=============================================="

if pgrep -af "java -jar /opt/trading-platform/services" >/dev/null; then
    echo "[WARN] Some application processes are still running:"
    pgrep -af "java -jar /opt/trading-platform/services"
else
    echo "[OK] All application services stopped"
fi

echo
echo "Shutdown complete."
echo "=============================================="
