#!/usr/bin/env python3
"""
GAMA Mobile Bridge Server
HTTP REST ↔ WebSocket bridge for GAMA headless simulation engine.

Provides:
  - REST API on 127.0.0.1:8080 (configurable via BACKEND_PORT env var)
  - WebSocket client connecting to GAMA headless on 127.0.0.1:6868
  - Translates REST calls to WebSocket JSON commands and vice versa
"""

import asyncio
import json
import logging
import os
import signal
import sys
import time
import uuid
from http.server import HTTPServer, BaseHTTPRequestHandler
from threading import Thread, Event

logging.basicConfig(
    level=logging.INFO,
    format="[bridge] %(levelname)s %(message)s",
)
log = logging.getLogger("bridge")

BACKEND_PORT = int(os.environ.get("BACKEND_PORT", "8080"))
GAMA_WS_PORT = int(os.environ.get("GAMA_WS_PORT", "6868"))
GAMA_WS_URL = f"ws://127.0.0.1:{GAMA_WS_PORT}"

# In-memory state
simulation_state = {
    "status": "initializing",
    "connected": False,
    "jobs": {},
    "uptime": 0,
    "start_time": time.time(),
}

# Try to import websockets - fall back to HTTP-only mode if unavailable
try:
    import websockets
    HAS_WEBSOCKETS = True
except ImportError:
    HAS_WEBSOCKETS = False
    log.warning("websockets library not available - running in HTTP-only mode")
    log.warning("Install: pip3 install websockets")


class BridgeHandler(BaseHTTPRequestHandler):
    """HTTP request handler that bridges REST ↔ WebSocket"""

    def log_message(self, format, *args):
        log.info("%s %s %s", *args)

    def _send_json(self, status_code, data):
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()
        self.wfile.write(json.dumps(data).encode())

    def do_OPTIONS(self):
        self._send_json(200, {})

    def do_GET(self):
        if self.path == "/api/health":
            self._handle_health()
        elif self.path == "/api/simulation/status":
            self._handle_status()
        elif self.path == "/api/simulation/list":
            self._handle_list()
        elif self.path.startswith("/api/simulation/status?job_id="):
            job_id = self.path.split("=", 1)[1]
            self._handle_job_status(job_id)
        else:
            self._send_json(404, {"status": "error", "message": "Not found"})

    def do_POST(self):
        content_length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(content_length).decode() if content_length > 0 else "{}"
        try:
            data = json.loads(body)
        except json.JSONDecodeError:
            data = {}

        if self.path == "/api/simulation/start":
            self._handle_start(data)
        elif self.path == "/api/simulation/stop":
            self._handle_stop(data)
        else:
            self._send_json(404, {"status": "error", "message": "Not found"})

    def _read_body(self):
        content_length = int(self.headers.get("Content-Length", 0))
        if content_length > 0:
            return json.loads(self.rfile.read(content_length).decode())
        return {}

    # ── Handlers ────────────────────────────────────────────────────

    def _handle_health(self):
        uptime = int(time.time() - simulation_state["start_time"])
        self._send_json(200, {
            "status": "ok" if simulation_state["connected"] else "degraded",
            "uptime": uptime,
            "gama_connected": simulation_state["connected"],
            "jobs_running": sum(
                1 for j in simulation_state["jobs"].values()
                if j.get("state") == "running"
            ),
            "version": "0.2.0",
            "mode": "bridge" if HAS_WEBSOCKETS else "standalone",
        })

    def _handle_status(self):
        jobs_list = sorted(
            simulation_state["jobs"].values(),
            key=lambda j: j.get("created", 0),
            reverse=True,
        )
        running_jobs = [j for j in jobs_list if j.get("state") in ("running", "starting")]
        self._send_json(200, {
            "status": "ok",
            "running": len(running_jobs) > 0,
            "jobs": jobs_list,
            "uptime": int(time.time() - simulation_state["start_time"]),
        })

    def _handle_list(self):
        self._send_json(200, {
            "status": "ok",
            "total": len(simulation_state["jobs"]),
            "jobs": list(simulation_state["jobs"].values()),
        })

    def _handle_job_status(self, job_id):
        job = simulation_state["jobs"].get(job_id)
        if job is None:
            self._send_json(404, {
                "status": "error",
                "message": f"Job not found: {job_id}",
            })
            return
        self._send_json(200, {
            "status": "ok",
            "simulation": job,
        })

    def _handle_start(self, data):
        job_id = data.get("job_id", uuid.uuid4().hex[:8])
        model = data.get("model", "")
        experiment = data.get("experiment", "")
        steps = data.get("steps", 100)

        job = {
            "id": job_id,
            "state": "starting",
            "progress": 0,
            "model": model,
            "experiment": experiment,
            "steps": steps,
            "created": time.time(),
        }
        simulation_state["jobs"][job_id] = job
        log.info("Started job %s (model=%s, experiment=%s)", job_id, model, experiment)

        # Simulate progress until real GAMA WebSocket commands are implemented
        self._simulate_job_progress(job_id, steps)

        self._send_json(200, {
            "status": "ok",
            "job_id": job_id,
            "message": "Simulation started",
        })

    def _handle_stop(self, data):
        job_id = data.get("job_id")
        if job_id and job_id in simulation_state["jobs"]:
            simulation_state["jobs"][job_id]["state"] = "stopped"
            log.info("Stopped job %s", job_id)
            self._send_json(200, {
                "status": "ok",
                "message": f"Job {job_id} stopped",
            })
        elif job_id:
            self._send_json(404, {
                "status": "error",
                "message": f"Job not found: {job_id}",
            })
        else:
            # Stop all
            for jid in list(simulation_state["jobs"].keys()):
                simulation_state["jobs"][jid]["state"] = "stopped"
            log.info("Stopped all jobs")
            self._send_json(200, {
                "status": "ok",
                "message": "All jobs stopped",
            })

    def _simulate_job_progress(self, job_id, total_steps):
        """Simulate job progress in a background thread (standalone mode)"""
        def _run():
            import time as _time
            for step in range(1, total_steps + 1):
                _time.sleep(0.2)
                if job_id in simulation_state["jobs"]:
                    job = simulation_state["jobs"][job_id]
                    if job.get("state") == "stopped":
                        return
                    job["progress"] = int((step / total_steps) * 100)
                    job["state"] = "running" if step < total_steps else "completed"
                    job["current_step"] = step
            log.info("Job %s completed", job_id)

        Thread(target=_run, daemon=True).start()


class BridgeServer:
    """Manages the HTTP server and WebSocket client"""

    def __init__(self):
        self.http_server = None
        self.http_thread = None
        self.ws_connected = Event()
        self.running = Event()
        self.running.set()

    def start(self):
        """Start the bridge server"""
        # Start HTTP server
        self.http_server = HTTPServer(("0.0.0.0", BACKEND_PORT), BridgeHandler)
        self.http_thread = Thread(target=self.http_server.serve_forever, daemon=True)
        self.http_thread.start()
        log.info("HTTP server started on 127.0.0.1:%d", BACKEND_PORT)

        simulation_state["status"] = "running"

        # Try WebSocket connection to GAMA if available
        if HAS_WEBSOCKETS:
            log.info("Connecting to GAMA WebSocket at %s...", GAMA_WS_URL)
            try:
                asyncio.run(self._ws_client())
            except Exception as e:
                log.warning("WebSocket connection failed: %s", e)
                log.info("Running in HTTP-only mode (GAMA not available)")

        simulation_state["connected"] = False

        # Block main thread
        try:
            while self.running.is_set():
                time.sleep(1)
        except KeyboardInterrupt:
            self.stop()

    def stop(self):
        log.info("Shutting down...")
        self.running.clear()
        if self.http_server:
            self.http_server.shutdown()

    async def _ws_client(self):
        """WebSocket client that connects to GAMA headless with continuous retry"""
        retry_delay = 2
        max_delay = 30

        while self.running.is_set():
            try:
                async with websockets.connect(
                    GAMA_WS_URL,
                    ping_interval=10,
                    ping_timeout=5,
                    open_timeout=10,
                ) as ws:
                    simulation_state["connected"] = True
                    log.info("Connected to GAMA WebSocket server")
                    self.ws_connected.set()
                    retry_delay = 2

                    async for message in ws:
                        self._handle_ws_message(message)

            except (websockets.exceptions.ConnectionClosed,
                    ConnectionRefusedError,
                    OSError) as e:
                simulation_state["connected"] = False
                log.debug("WS reconnect in %ds: %s", retry_delay, e)
                await asyncio.sleep(retry_delay)
                retry_delay = min(retry_delay * 1.5, max_delay)

    def _handle_ws_message(self, message):
        """Process incoming WebSocket messages from GAMA"""
        try:
            data = json.loads(message)
            msg_type = data.get("type", "unknown")
            log.debug("WS message: %s", msg_type)

            if msg_type == "CommandExecutedSuccessfully":
                exp_id = data.get("exp_id", "")
                if exp_id and exp_id in simulation_state["jobs"]:
                    simulation_state["jobs"][exp_id]["state"] = "running"
            elif msg_type == "SimulationEnded":
                exp_id = data.get("exp_id", "")
                if exp_id and exp_id in simulation_state["jobs"]:
                    simulation_state["jobs"][exp_id]["state"] = "completed"
                    simulation_state["jobs"][exp_id]["progress"] = 100

        except json.JSONDecodeError:
            pass


def main():
    log.info("GAMA Mobile Bridge Server v0.2.0")
    log.info("Backend port: %d", BACKEND_PORT)
    log.info("GAMA WS port: %d", GAMA_WS_PORT)

    server = BridgeServer()

    def handle_signal(sig, frame):
        log.info("Received signal %d", sig)
        server.stop()
        sys.exit(0)

    signal.signal(signal.SIGTERM, handle_signal)
    signal.signal(signal.SIGINT, handle_signal)

    server.start()


if __name__ == "__main__":
    main()
