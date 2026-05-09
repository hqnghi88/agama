#!/bin/bash
set -euo pipefail

# Test the backend server locally (without Android)
# This starts the Java API server and runs health checks

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="${SCRIPT_DIR}/../backend"
TEST_PORT=8080

echo "╔═══════════════════════════════════════════════════════════╗"
echo "║        GAMA Mobile - Backend Test Suite                  ║"
echo "╚═══════════════════════════════════════════════════════════╝"

# ─── 1. Build backend JAR if needed ─────────────────────────────────
BACKEND_JAR="${BACKEND_DIR}/api-server/build/libs/api-server-uber.jar"
if [ ! -f "${BACKEND_JAR}" ]; then
    echo "[test] Building backend JAR..."
    cd "${BACKEND_DIR}" && ./gradlew uberJar 2>&1 | tail -3
    echo "[test] Build complete"
fi

if [ ! -f "${BACKEND_JAR}" ]; then
    echo "[test] ✗ Backend JAR not found at ${BACKEND_JAR}"
    exit 1
fi
echo "[test] Backend JAR: ${BACKEND_JAR} ($(du -h "${BACKEND_JAR}" | cut -f1))"

# ─── 2. Start backend server ────────────────────────────────────────
echo "[test] Starting backend server on port ${TEST_PORT}..."
WORKSPACE_DIR="/tmp/gama-test-$$"
mkdir -p "${WORKSPACE_DIR}"

java -jar "${BACKEND_JAR}" --port=${TEST_PORT} --workspace="${WORKSPACE_DIR}" &
BACKEND_PID=$!
echo "[test] Server PID: ${BACKEND_PID}"

# Cleanup on exit
cleanup() {
    echo ""
    echo "[test] Cleaning up..."
    kill ${BACKEND_PID} 2>/dev/null || true
    rm -rf "${WORKSPACE_DIR}"
    wait ${BACKEND_PID} 2>/dev/null || true
    echo "[test] Done"
}
trap cleanup EXIT INT TERM

# ─── 3. Wait for server to start ────────────────────────────────────
echo "[test] Waiting for server to be ready..."
for i in $(seq 1 15); do
    if curl -s http://127.0.0.1:${TEST_PORT}/api/health > /dev/null 2>&1; then
        echo "[test] Server ready! (attempt ${i})"
        break
    fi
    if [ $i -eq 15 ]; then
        echo "[test] ✗ Server failed to start within 15 seconds"
        exit 1
    fi
    sleep 1
done

echo ""

# ─── 4. Run tests ───────────────────────────────────────────────────
TESTS_PASSED=0
TESTS_FAILED=0

run_test() {
    local name="$1"
    local method="$2"
    local endpoint="$3"
    local body="$4"
    local expected_status="$5"
    
    local tmpfile=$(mktemp)
    if [ -n "$body" ]; then
        curl -s -w "%{http_code}" -o "$tmpfile" -X ${method} \
            -H "Content-Type: application/json" \
            -d "$body" \
            "http://127.0.0.1:${TEST_PORT}${endpoint}" > /tmp/curl_http_code.txt
    else
        curl -s -w "%{http_code}" -o "$tmpfile" -X ${method} \
            "http://127.0.0.1:${TEST_PORT}${endpoint}" > /tmp/curl_http_code.txt
    fi
    
    http_code=$(cat /tmp/curl_http_code.txt)
    response=$(cat "$tmpfile")
    rm -f "$tmpfile" /tmp/curl_http_code.txt
    
    if [ "$http_code" = "$expected_status" ]; then
        echo "  ✓ ${name} (HTTP ${http_code})"
        TESTS_PASSED=$((TESTS_PASSED + 1))
    else
        echo "  ✗ ${name} (expected ${expected_status}, got ${http_code})"
        echo "    Response: ${response}"
        TESTS_FAILED=$((TESTS_FAILED + 1))
    fi
}

echo "--- Running Tests ---"
echo ""

run_test "Health Check"             "GET"  "/api/health"              ""  200
run_test "Status (no jobs)"         "GET"  "/api/simulation/status"   ""  200
run_test "List (empty)"             "GET"  "/api/simulation/list"     ""  200
run_test "Start simulation"         "POST" "/api/simulation/start"    '{"steps":10}'  200
run_test "Status (after start)"     "GET"  "/api/simulation/status"   ""  200
run_test "Stop (no job_id)"         "POST" "/api/simulation/stop"     '{}'  200
run_test "Health (POST also works)"  "POST" "/api/health"              ''  200
run_test "Start with params"        "POST" "/api/simulation/start"    '{"model":"test.gaml","experiment":"default","steps":50}'  200
run_test "Status (with jobs)"       "GET"  "/api/simulation/list"     ""  200
run_test "Job status by ID"         "GET"  "/api/simulation/status?job_id=nonexistent" ""  404

echo ""
echo "--- Results ---"
echo "  Passed: ${TESTS_PASSED}"
echo "  Failed: ${TESTS_FAILED}"
echo ""

if [ ${TESTS_FAILED} -eq 0 ]; then
    echo "╔═══════════════════════════════════════════════════════════╗"
    echo "║           ALL TESTS PASSED                               ║"
    echo "╚═══════════════════════════════════════════════════════════╝"
else
    echo "╔═══════════════════════════════════════════════════════════╗"
    echo "║           ${TESTS_FAILED} TEST(S) FAILED                         ║"
    echo "╚═══════════════════════════════════════════════════════════╝"
    exit 1
fi
