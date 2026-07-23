#!/bin/bash
# GAMA Web Launcher
# Starts the GAMA headless server and opens the browser

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PORT=6868

# Find Java
if command -v java &>/dev/null; then
    JAVA=java
elif [ -n "$JAVA_HOME" ]; then
    JAVA="$JAVA_HOME/bin/java"
else
    echo "ERROR: Java not found. Please install Java 25."
    exit 1
fi

echo "Using Java: $($JAVA -version 2>&1 | head -1)"

# Build classpath from all JARs
CP=""
for jar in "$SCRIPT_DIR/gama-jars/"*.jar; do
    if [ -z "$CP" ]; then
        CP="$jar"
    else
        CP="$CP:$jar"
    fi
done

echo "Starting GAMA server on port $PORT..."
echo "Press Ctrl+C to stop."

# Start server in background and open browser
$JAVA -cp "$CP" \
    -Xms2G -Xmx8G \
    --add-exports=java.base/java.lang=ALL-UNNAMED \
    --add-opens=java.base/java.lang=ALL-UNNAMED \
    --add-opens=java.base/java.util=ALL-UNNAMED \
    --add-opens=java.base/java.io=ALL-UNNAMED \
    --add-opens=java.base/java.net=ALL-UNNAMED \
    --add-opens=java.base/java.nio=ALL-UNNAMED \
    --add-opens=java.desktop/java.awt=ALL-UNNAMED \
    gama.browser.GamaServerLauncher "$PORT" &
SERVER_PID=$!

# Wait for server to start
echo "Waiting for server..."
for i in $(seq 1 30); do
    if curl -s -o /dev/null -w "%{http_code}" "http://localhost:$PORT" 2>/dev/null | grep -q "^[12]"; then
        break
    fi
    # Also check WebSocket port
    if nc -z localhost "$PORT" 2>/dev/null; then
        break
    fi
    sleep 1
done

# Open browser
echo "Opening browser..."
if command -v open &>/dev/null; then
    open "$SCRIPT_DIR/web/index.html"
elif command -v xdg-open &>/dev/null; then
    xdg-open "$SCRIPT_DIR/web/index.html"
elif command -v start &>/dev/null; then
    start "$SCRIPT_DIR/web/index.html"
fi

echo ""
echo "GAMA Web is running!"
echo "  Server: ws://localhost:$PORT"
echo "  UI:     $SCRIPT_DIR/web/index.html"
echo ""
echo "Press Ctrl+C to stop the server."

# Wait for server process
wait $SERVER_PID
