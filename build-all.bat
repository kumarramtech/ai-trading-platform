@echo off
setlocal EnableDelayedExpansion

echo ==========================================================
echo        AI TRADING PLATFORM - BUILD SCRIPT
echo ==========================================================

echo.
echo [1/4] Cleaning and Building Project...
call mvn clean package -DskipTests

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo BUILD FAILED
    pause
    exit /b 1
)

echo.
echo [2/4] Preparing runtime folder...

if not exist jars mkdir jars

del /F /Q jars\*.jar >nul 2>&1

echo.
echo [3/4] Copying executable JARs...

call :copyJar api-gateway api-gateway.jar
call :copyJar stock-service stock-service.jar
call :copyJar signal-engine-service signal-engine-service.jar
call :copyJar portfolio-service portfolio-service.jar
call :copyJar backtesting-service backtesting-service.jar
call :copyJar ai-service ai-service.jar
call :copyJar notification-service notification-service.jar
call :copyJar newsanalysis-service newsanalysis-service.jar
call :copyJar market-stream-service market-stream-service.jar
call :copyJar broker-auth-service broker-auth-service.jar

echo.
echo ==========================================================
echo Build Completed Successfully
echo ==========================================================
echo.

dir jars

pause
exit /b

:copyJar

set MODULE=%1
set DEST=%2

for %%F in ("%MODULE%\target\*.jar") do (

    echo %%~nxF | findstr /I "original sources javadoc" >nul

    if errorlevel 1 (
        echo Copying %%~nxF
        copy /Y "%%F" "jars\%DEST%" >nul
        goto :eof
    )

)

echo WARNING: No executable jar found for %MODULE%

goto :eof