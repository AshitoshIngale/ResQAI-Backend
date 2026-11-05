@echo off
REM ============================================================
REM  ResQAI Backend - Windows runner (spec PART 20)
REM  Checks Java, required config, then starts Spring Boot.
REM  Credentials come from environment variables or .env - NEVER here.
REM ============================================================
setlocal enabledelayedexpansion

echo === ResQAI Backend Startup Check ===

REM -- 1. Java check --
where java >nul 2>nul
if errorlevel 1 (
    echo [ERROR] Java is not installed or not on PATH.
    echo         Install Java 21 ^(Temurin^) and add it to PATH.
    exit /b 1
)
java -version 2>&1 | findstr /C:"version" | findstr /R /C:"21\." >nul
if errorlevel 1 (
    echo [WARN] Java 21 recommended - continuing with:
    java -version 2>&1 | findstr /C:"version"
)

REM -- 2. Load .env from the project root if present (KEY=VALUE lines) --
if exist "%~dp0.env" (
    echo [INFO] Loading .env
    for /f "usebackq tokens=1,* delims==" %%A in ("%~dp0.env") do (
        if not "%%A"=="" if not "%%A:~0,1%"=="#" set "%%A=%%B"
    )
) else (
    echo [WARN] No .env file found - relying on system environment variables.
)

REM -- 3. Required environment checks (fail loudly, never silently) --
set MISSING=0
if "%SPRING_DATA_MONGODB_URI%"=="" (
    if "%MONGODB_URI_LOCAL%"=="" (
        echo [ERROR] SPRING_DATA_MONGODB_URI is not set.
        set MISSING=1
    )
)
if "%JWT_SECRET%"=="" (
    echo [WARN] JWT_SECRET not set - a development default will be used. Set it for real use.
)
if "%TWILIO_ACCOUNT_SID%"=="" (
    echo [WARN] TWILIO_ACCOUNT_SID not set - Twilio features will report NOT_CONFIGURED.
)
if "%SARVAM_API_KEY%"=="" (
    echo [WARN] SARVAM_API_KEY not set - speech features will report NOT_CONFIGURED.
)
if "%MISSING%"=="1" (
    echo [ERROR] Required configuration missing. Fix the entries above and retry.
    exit /b 1
)

REM -- 4. Build if needed, then start --
if not exist "target\resqai-backend-*.jar" (
    echo [INFO] Building the backend jar ^(first run^)...
    call mvnw.cmd -q -DskipTests package
    if errorlevel 1 (
        echo [ERROR] Maven build failed. See the output above.
        exit /b 1
    )
)

echo === Starting ResQAI backend on port 8080 ===
java -jar target\resqai-backend-*.jar --server.port=8080
set EXITCODE=%ERRORLEVEL%
echo.
echo [ERROR] Backend exited with code %EXITCODE%. Common causes:
echo   - MongoDB unreachable (check SPRING_DATA_MONGODB_URI / local mongod)
echo   - Port 8080 already in use
echo   - Invalid JWT_SECRET length
exit /b %EXITCODE%
