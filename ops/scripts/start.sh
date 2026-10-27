#!/bin/bash

set -u

BASE_DIR="/opt/trading-platform"
JAR_DIR="$BASE_DIR/services"
LOG_DIR="$BASE_DIR/logs"
RUN_DIR="$BASE_DIR/run"
ENV_FILE="$BASE_DIR/config/environment"

STARTUP_TIMEOUT=60
HEALTH_INTERVAL=2

echo "=================================================="
echo "       AI TRADING PLATFORM - START"
echo "=================================================="

# --------------------------------------------------
# 1. Load environment
# --------------------------------------------------

if [ ! -f "$ENV_FILE" ]; then
    echo "[ERROR] Environment file not found: $ENV_FILE"
    exit 1
fi

source "$ENV_FILE"

echo "[OK] Environment loaded"

# --------------------------------------------------
# 2. Prepare directories
# --------------------------------------------------

mkdir -p "$LOG_DIR" "$LOG_DIR/console" "$RUN_DIR"

# --------------------------------------------------
# 3. Check / Start MySQL
# --------------------------------------------------

echo
echo "[1/3] Checking MySQL..."

if systemctl is-active --quiet mysql; then
    echo "[OK] MySQL is running"
else
    echo "[WARN] MySQL is not running. Starting MySQL..."

    sudo systemctl start mysql

    if systemctl is-active --quiet mysql; then
        echo "[OK] MySQL started"
    else
        echo "[ERROR] MySQL failed to start"
        exit 1
    fi
fi

# --------------------------------------------------
# 4. Check / Start Redis
# --------------------------------------------------

echo
echo "[2/3] Checking Redis..."

if redis-cli ping 2>/dev/null | grep -q "PONG"; then
    echo "[OK] Redis is running"
else
    echo "[WARN] Redis is not running. Starting Redis..."

    sudo systemctl start redis-server

    sleep 2

    if redis-cli ping 2>/dev/null | grep -q "PONG"; then
        echo "[OK] Redis started"
    else
        echo "[ERROR] Redis failed to start"
        exit 1
    fi
fi

# --------------------------------------------------
# Wait for Actuator health
# --------------------------------------------------

wait_for_health() {

    SERVICE_NAME="$1"
    PORT="$2"

    echo "[WAIT] Waiting for $SERVICE_NAME health on port $PORT..."

    ELAPSED=0

    while [ "$ELAPSED" -lt "$STARTUP_TIMEOUT" ]; do

        RESPONSE=$(curl -s --max-time 2 \
            "http://localhost:$PORT/actuator/health" 2>/dev/null || true)

        if echo "$RESPONSE" | grep -q '"status":"UP"'; then
            echo "[OK] $SERVICE_NAME is HEALTHY"
            return 0
        fi

        sleep "$HEALTH_INTERVAL"
        ELAPSED=$((ELAPSED + HEALTH_INTERVAL))

    done

    echo "[ERROR] $SERVICE_NAME did not become healthy within ${STARTUP_TIMEOUT}s"
    return 1
}

# --------------------------------------------------
# Wait for TCP port
# --------------------------------------------------

wait_for_port() {

    SERVICE_NAME="$1"
    PORT="$2"

    echo "[WAIT] Waiting for $SERVICE_NAME on port $PORT..."

    ELAPSED=0

    while [ "$ELAPSED" -lt "$STARTUP_TIMEOUT" ]; do

        if (echo > "/dev/tcp/127.0.0.1/$PORT") >/dev/null 2>&1; then
            echo "[OK] $SERVICE_NAME is listening on port $PORT"
            return 0
        fi

        sleep "$HEALTH_INTERVAL"
        ELAPSED=$((ELAPSED + HEALTH_INTERVAL))

    done

    echo "[ERROR] $SERVICE_NAME did not open port $PORT within ${STARTUP_TIMEOUT}s"
    return 1
}

# --------------------------------------------------
# Start service
# --------------------------------------------------

start_service() {

    SERVICE_NAME="$1"
    JAR_FILE="$2"
    READINESS_TYPE="$3"
    PORT="$4"

    JAR_PATH="$JAR_DIR/$JAR_FILE"
    PID_FILE="$RUN_DIR/$SERVICE_NAME.pid"
    # stdout/stderr only (JVM crashes, pre-logback output). Logback owns
    # logs/<name>.log; sharing that file with this redirect left two writers on it.
    LOG_FILE="$LOG_DIR/console/$SERVICE_NAME.out"

    echo
    echo "--------------------------------------------------"
    echo "Starting: $SERVICE_NAME"
    echo "--------------------------------------------------"

    # Check JAR
    if [ ! -f "$JAR_PATH" ]; then
        echo "[ERROR] JAR not found:"
        echo "        $JAR_PATH"
        return 1
    fi

    # --------------------------------------------------
    # Check existing PID
    # --------------------------------------------------

    if [ -f "$PID_FILE" ]; then

        PID=$(cat "$PID_FILE")

        if kill -0 "$PID" 2>/dev/null; then

            echo "[INFO] Existing process found"
            echo "       PID: $PID"

            # Verify actual readiness
            if [ "$READINESS_TYPE" = "health" ]; then

                if wait_for_health "$SERVICE_NAME" "$PORT"; then
                    echo "[SKIP] $SERVICE_NAME already running and healthy"
                    return 0
                fi

            elif [ "$READINESS_TYPE" = "port" ]; then

                if wait_for_port "$SERVICE_NAME" "$PORT"; then
                    echo "[SKIP] $SERVICE_NAME already running"
                    return 0
                fi
            fi

            echo "[ERROR] Existing PID found but service is not ready"
            echo "        Check: $LOG_FILE"
            return 1

        else

            echo "[INFO] Removing stale PID file"
            rm -f "$PID_FILE"

        fi
    fi

    # --------------------------------------------------
    # Start Java process
    # --------------------------------------------------

    echo "[START] Launching $SERVICE_NAME..."

    nohup java -jar "$JAR_PATH" \
        > "$LOG_FILE" 2>&1 &

    PID=$!

    echo "$PID" > "$PID_FILE"

    echo "[INFO] PID: $PID"
    echo "[INFO] LOG: $LOG_FILE"

    # --------------------------------------------------
    # Verify process
    # --------------------------------------------------

    sleep 1

    if ! kill -0 "$PID" 2>/dev/null; then

        echo "[ERROR] $SERVICE_NAME process exited immediately"
        echo
        echo "----- LAST 30 LOG LINES -----"
        tail -n 30 "$LOG_FILE"
        echo "-----------------------------"

        rm -f "$PID_FILE"
        return 1
    fi

    # --------------------------------------------------
    # Wait for readiness
    # --------------------------------------------------

    if [ "$READINESS_TYPE" = "health" ]; then

        if wait_for_health "$SERVICE_NAME" "$PORT"; then
            return 0
        fi

    elif [ "$READINESS_TYPE" = "port" ]; then

        if wait_for_port "$SERVICE_NAME" "$PORT"; then
            return 0
        fi

    else

        echo "[ERROR] Unknown readiness type: $READINESS_TYPE"
        return 1

    fi

    # --------------------------------------------------
    # Startup failed
    # --------------------------------------------------

    echo
    echo "[ERROR] $SERVICE_NAME failed readiness check"
    echo
    echo "----- LAST 50 LOG LINES -----"
    tail -n 50 "$LOG_FILE"
    echo "-----------------------------"

    return 1
}

# --------------------------------------------------
# 5. Start Trading Platform Services
# --------------------------------------------------

echo
echo "[3/3] Starting AI Trading Platform services..."

# --------------------------------------------------
# Broker Authentication
# --------------------------------------------------

if ! start_service \
    "broker-auth-service" \
    "broker-auth-service-0.0.1-SNAPSHOT.jar" \
    "health" \
    "9090"; then

    echo "[FATAL] Broker Auth is not healthy."
    echo "[FATAL] Stopping deployment."
    exit 1
fi

# --------------------------------------------------
# Stock Service
# --------------------------------------------------

if ! start_service \
    "stock-service" \
    "stock-service-0.0.1-SNAPSHOT.jar" \
    "health" \
    "8081"; then

    echo "[FATAL] Stock Service failed."
    echo "[FATAL] Stopping deployment."
    exit 1
fi

# --------------------------------------------------
# Market Stream
# --------------------------------------------------

if ! start_service \
    "market-stream-service" \
    "market-stream-service-0.0.1-SNAPSHOT.jar" \
    "health" \
    "8089"; then

    echo "[FATAL] Market Stream Service failed."
    echo "[FATAL] Stopping deployment."
    exit 1
fi

# --------------------------------------------------
# Watchlist
# --------------------------------------------------

if ! start_service \
    "watchlist-service" \
    "watchlist-service-0.0.1-SNAPSHOT.jar" \
    "port" \
    "9092"; then

    echo "[FATAL] Watchlist Service failed."
    echo "[FATAL] Stopping deployment."
    exit 1
fi

# --------------------------------------------------
# Signal Engine
# --------------------------------------------------

if ! start_service \
    "signal-engine-service" \
    "signal-engine-service-0.0.1-SNAPSHOT.jar" \
    "health" \
    "8082"; then

    echo "[FATAL] Signal Engine failed."
    echo "[FATAL] Stopping deployment."
    exit 1
fi

# --------------------------------------------------
# Balance / Margin
# --------------------------------------------------

if ! start_service \
    "balance-margin-service" \
    "balance-margin-service-0.0.1-SNAPSHOT.jar" \
    "port" \
    "9091"; then

    echo "[FATAL] Balance/Margin Service failed."
    echo "[FATAL] Stopping deployment."
    exit 1
fi

# --------------------------------------------------
# Portfolio
# --------------------------------------------------

if ! start_service \
    "portfolio-service" \
    "portfolio-service-0.0.1-SNAPSHOT.jar" \
    "health" \
    "8083"; then

    echo "[FATAL] Portfolio Service failed."
    echo "[FATAL] Stopping deployment."
    exit 1
fi

# --------------------------------------------------
# News Analysis
# --------------------------------------------------

if ! start_service \
    "newsanalysis-service" \
    "newsanalysis-service-0.0.1-SNAPSHOT.jar" \
    "health" \
    "8088"; then

    echo "[FATAL] News Analysis Service failed."
    echo "[FATAL] Stopping deployment."
    exit 1
fi

# --------------------------------------------------
# Notification
# --------------------------------------------------

if ! start_service \
    "notification-service" \
    "notification-service-0.0.1-SNAPSHOT.jar" \
    "health" \
    "8087"; then

    echo "[FATAL] Notification Service failed."
    echo "[FATAL] Stopping deployment."
    exit 1
fi

# --------------------------------------------------
# AI Service
# --------------------------------------------------

if ! start_service \
    "ai-service" \
    "ai-service-0.0.1-SNAPSHOT.jar" \
    "health" \
    "8086"; then

    echo "[FATAL] AI Service failed."
    echo "[FATAL] Stopping deployment."
    exit 1
fi

# --------------------------------------------------
# Final result
# --------------------------------------------------

echo
echo "=================================================="
echo "     AI TRADING PLATFORM STARTUP COMPLETE"
echo "=================================================="
echo
echo "Infrastructure:"
echo "  MySQL : RUNNING"
echo "  Redis : RUNNING"
echo
echo "Services:"
echo "  Broker Auth       : 9090  HEALTHY"
echo "  Stock             : 8081  HEALTHY"
echo "  Signal Engine     : 8082  HEALTHY"
echo "  Portfolio         : 8083  HEALTHY"
echo "  AI                : 8086  HEALTHY"
echo "  Notification      : 8087  HEALTHY"
echo "  News Analysis     : 8088  HEALTHY"
echo "  Market Stream     : 8089  HEALTHY"
echo "  Balance / Margin  : 9091  READY"
echo "  Watchlist         : 9092  READY"
echo
echo "All 10 services are ready."
echo "=================================================="
