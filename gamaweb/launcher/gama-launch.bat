@echo off
setlocal enabledelayedexpansion
:: GAMA Launcher for Windows
:: Automatically downloads and starts GAMA server

set GAMA_HOME=%USERPROFILE%\.gama
set GAMA_JARS=%GAMA_HOME%\jars
set GAMA_WEB=%GAMA_HOME%\web
set GAMA_JRE=%GAMA_HOME%\jre
set SERVER_PORT=6868
set WEB_PORT=8000
set GAMA_VERSION=1.0.0

echo.
echo   ╔══════════════════════════════════════╗
echo   ║     GAMA - Simulation Platform       ║
echo   ╚══════════════════════════════════════╝
echo.

:: Check for Java
where java >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    echo [GAMA] Found system Java
    set JAVA_CMD=java
    goto :check_gama
)

:: Check for bundled JRE
if exist "%GAMA_JRE%\bin\java.exe" (
    echo [GAMA] Using bundled JRE
    set JAVA_CMD=%GAMA_JRE%\bin\java.exe
    goto :check_gama
)

:: Download JRE
echo [GAMA] Downloading JRE...
mkdir "%GAMA_JRE%" 2>nul
set JRE_URL=https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.4%%2B7/OpenJDK21U-jre_x64_windows_hotspot_21.0.4_7.zip
curl -L -o "%TEMP%\gama-jre.zip" "%JRE_URL%"
tar -xf "%TEMP%\gama-jre.zip" -C "%GAMA_JRE%" --strip-components=1
del "%TEMP%\gama-jre.zip"
set JAVA_CMD=%GAMA_JRE%\bin\java.exe

:check_gama
:: Check if GAMA JARs exist
if exist "%GAMA_JARS%" (
    dir /b "%GAMA_JARS%\*.jar" >nul 2>nul
    if !ERRORLEVEL! EQU 0 (
        echo [GAMA] GAMA JARs already downloaded
        goto :setup_web
    )
)

:: Download GAMA
echo [GAMA] Downloading GAMA engine...
mkdir "%GAMA_JARS%" 2>nul
set ARCHIVE_URL=https://github.com/gama-platform/gama/releases/download/%GAMA_VERSION%/gama-headless-windows-x86_64.zip
curl -L -o "%TEMP%\gama-headless.zip" "%ARCHIVE_URL%"
tar -xf "%TEMP%\gama-headless.zip" -C "%GAMA_JARS%"
del "%TEMP%\gama-headless.zip"

:setup_web
if exist "%GAMA_WEB%\index.html" goto :start_server
echo [GAMA] Setting up web interface...
mkdir "%GAMA_WEB%" 2>nul
:: Copy from local source if available
set SCRIPT_DIR=%~dp0
if exist "%SCRIPT_DIR%..\web\index.html" (
    xcopy /E /I /Y "%SCRIPT_DIR%..\web\*" "%GAMA_WEB%"
) else (
    curl -L -o "%TEMP%\gama-web.zip" "https://github.com/gama-platform/gama/releases/download/%GAMA_VERSION%/gama-web-%GAMA_VERSION%.zip"
    tar -xf "%TEMP%\gama-web.zip" -C "%GAMA_WEB%"
    del "%TEMP%\gama-web.zip"
)

:start_server
:: Check if server is already running
curl -s "http://localhost:%SERVER_PORT%" >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    echo [GAMA] Server already running
    goto :start_web
)

echo [GAMA] Starting GAMA server...
mkdir "%GAMA_HOME%\logs" 2>nul

:: Build classpath
set CLASSPATH=
for %%j in ("%GAMA_JARS%\*.jar") do (
    if "!CLASSPATH!"=="" (
        set CLASSPATH=%%j
    ) else (
        set CLASSPATH=!CLASSPATH!;%%j
    )
)

:: Start server
start /B "" "%JAVA_CMD%" -cp "%CLASSPATH%" -Xmx4g -Dgama.server.port=%SERVER_PORT% gama.browser.GamaServerLauncher %SERVER_PORT% > "%GAMA_HOME%\logs\server.log" 2>&1

:: Wait for server
echo [GAMA] Waiting for server...
timeout /t 5 /nobreak >nul

:start_web
:: Start web server
echo [GAMA] Starting web server...
start /B "" python -m http.server %WEB_PORT% --directory "%GAMA_WEB%" > "%GAMA_HOME%\logs\web.log" 2>&1
timeout /t 2 /nobreak >nul

:: Open browser
echo [GAMA] Opening browser...
start http://localhost:%WEB_PORT%

echo.
echo [GAMA] GAMA is running!
echo   Server:  http://localhost:%SERVER_PORT%
echo   Web UI:  http://localhost:%WEB_PORT%
echo.
echo Press any key to stop...
pause >nul
