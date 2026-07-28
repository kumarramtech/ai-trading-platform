@echo off
setlocal

echo ==================================================
echo      AI TRADING PLATFORM STARTUP
echo ==================================================

REM --------------------------------------------------
REM Verify JAVA_HOME
REM --------------------------------------------------
if "%JAVA_HOME%"=="" (
    echo ERROR: JAVA_HOME is not set.
    pause
    exit /b 1
)

set JAVA_CMD=%JAVA_HOME%\bin\java.exe

echo Using Java:
"%JAVA_CMD%" -version

echo.
echo Make sure:
echo   1. MySQL is running.
echo   2. Redis container is running.
echo.
pause

echo Starting Broker Auth Service...
start "Broker Auth Service" cmd /k ""%JAVA_CMD%" -jar jars\broker-auth-service.jar"
timeout /t 10

echo Starting Stock Service...
start "Stock Service" cmd /k ""%JAVA_CMD%" -jar jars\stock-service.jar"
timeout /t 10

echo Starting Portfolio Service...
start "Portfolio Service" cmd /k ""%JAVA_CMD%" -jar jars\portfolio-service.jar"
timeout /t 10

echo Starting Notification Service...
start "Notification Service" cmd /k ""%JAVA_CMD%" -jar jars\notification-service.jar"
timeout /t 10

echo Starting AI Service...
start "AI Service" cmd /k ""%JAVA_CMD%" -jar jars\ai-service.jar"
timeout /t 10

echo Starting News Analysis Service...
start "News Analysis Service" cmd /k ""%JAVA_CMD%" -jar jars\newsanalysis-service.jar"
timeout /t 10

echo Starting Signal Engine Service...
start "Signal Engine Service" cmd /k ""%JAVA_CMD%" -jar jars\signal-engine-service.jar"
timeout /t 10

echo Starting Market Stream Service...
start "Market Stream Service" cmd /k ""%JAVA_CMD%" -jar jars\market-stream-service.jar"
timeout /t 15

echo Starting API Gateway...
start "API Gateway" cmd /k ""%JAVA_CMD%" -jar jars\api-gateway.jar"

echo.
echo ==================================================
echo        ALL SERVICES STARTED
echo ==================================================
pause