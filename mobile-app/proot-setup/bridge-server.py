#!/usr/bin/env python3
"""
GAMA Mobile Bridge Server
HTTP REST <-> WebSocket bridge for GAMA headless simulation engine.

GAMA runs in -socket mode (WebSocket server on port 6868) and sends
simulation display frames as binary PNG data via WebSocket.
This bridge:
  - Provides REST API on 8080 for the RN mobile app
  - Forwards simulation start/stop commands to GAMA via WebSocket
  - Captures PNG frames from GAMA and serves them via REST
  - Handles SimulationOutput (text), SimulationEnded, etc.
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
from urllib.parse import urlparse

logging.basicConfig(
    level=logging.INFO,
    format="[bridge] %(levelname)s %(message)s",
)
log = logging.getLogger("bridge")

BACKEND_PORT = int(os.environ.get("BACKEND_PORT", "8080"))
GAMA_WS_PORT = int(os.environ.get("GAMA_WS_PORT", "6868"))
GAMA_WS_URL = f"ws://127.0.0.1:{GAMA_WS_PORT}"

# Thread-safe command queue: HTTP handlers enqueue, WS sender dequeues
ws_send_queue = asyncio.Queue()

simulation_state = {
    "status": "initializing",
    "connected": False,
    "jobs": {},
    "frames": {},  # job_id -> bytes (latest PNG frame)
    "console": {},  # job_id -> list of output lines
    "uptime": 0,
    "start_time": time.time(),
}

try:
    import websockets
    HAS_WEBSOCKETS = True
except ImportError:
    HAS_WEBSOCKETS = False
    log.warning("websockets library not available - running in HTTP-only mode")

def extract_png(data):
    """Extract valid PNG from binary message, stripping trailing marker bytes."""
    if data[:4] == b'\x89PNG':
        iend = b'IEND\xae\x42\x60\x82'
        idx = data.find(iend)
        if idx >= 0:
            return data[:idx + len(iend)]
    return data

class BridgeHandler(BaseHTTPRequestHandler):
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

    def _send_png(self, png_bytes):
        self.send_response(200)
        self.send_header("Content-Type", "image/png")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Cache-Control", "no-cache, no-store, must-revalidate")
        self.send_header("Pragma", "no-cache")
        self.send_header("Expires", "0")
        self.end_headers()
        self.wfile.write(png_bytes)

    def do_OPTIONS(self):
        self._send_json(200, {})

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path

        if path == "/api/health":
            self._handle_health()
        elif path == "/api/simulation/status":
            self._handle_status()
        elif path == "/api/simulation/list":
            self._handle_list()
        elif path.startswith("/api/simulation/frame/"):
            job_id = path.split("/")[-1]
            self._handle_frame(job_id)
        elif path.startswith("/api/simulation/console/"):
            job_id = path.split("/")[-1]
            self._handle_console(job_id)
        elif path.startswith("/api/simulation/status"):
            qs = parsed.query
            if qs.startswith("job_id="):
                job_id = qs.split("=", 1)[1]
                self._handle_job_status(job_id)
            else:
                self._send_json(404, {"status": "error", "message": "Not found"})
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

    # ── Handlers ────────────────────────────────────────────────────

    def _handle_health(self):
        uptime = int(time.time() - simulation_state["start_time"])
        running = sum(1 for j in simulation_state["jobs"].values() if j.get("state") == "running")
        self._send_json(200, {
            "status": "ok" if simulation_state["connected"] else "degraded",
            "uptime": uptime,
            "gama_connected": simulation_state["connected"],
            "jobs_running": running,
            "frames_cached": len(simulation_state["frames"]),
            "version": "0.3.0",
            "mode": "bridge" if HAS_WEBSOCKETS else "standalone",
        })

    def _handle_status(self):
        jobs_list = sorted(
            simulation_state["jobs"].values(),
            key=lambda j: j.get("created", 0),
            reverse=True,
        )
        running = any(j.get("state") in ("running", "starting") for j in jobs_list)
        # Enrich jobs with frame availability
        for j in jobs_list:
            jid = j.get("id", "")
            j["has_frame"] = jid in simulation_state["frames"]
            j["console_lines"] = len(simulation_state["console"].get(jid, []))
        self._send_json(200, {
            "status": "ok",
            "running": running,
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
            self._send_json(404, {"status": "error", "message": f"Job not found: {job_id}"})
            return
        job_copy = dict(job)
        job_copy["has_frame"] = job_id in simulation_state["frames"]
        job_copy["console_lines"] = len(simulation_state["console"].get(job_id, []))
        self._send_json(200, {"status": "ok", "simulation": job_copy})

    def _handle_frame(self, job_id):
        png_data = simulation_state["frames"].get(job_id)
        if png_data is None:
            self._send_json(404, {"status": "error", "message": f"No frame for job: {job_id}"})
            return
        self._send_png(png_data)

    def _handle_console(self, job_id):
        lines = simulation_state["console"].get(job_id, [])
        self._send_json(200, {"status": "ok", "lines": lines})

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
        simulation_state["console"][job_id] = []
        log.info("Job %s: queued (model=%s, experiment=%s)", job_id, model, experiment)

        # Queue load command for GAMA
        load_cmd = {
            "type": "load",
            "model": model if model else "",
            "experiment": experiment if experiment else "",
            "exp_id": job_id,
        }
        _queue_ws_command(load_cmd)

        # Queue play command (sent after load completes)
        play_cmd = {
            "type": "play",
            "exp_id": job_id,
            "nb_step": steps,
        }
        _queue_ws_command(play_cmd)

        self._send_json(200, {
            "status": "ok",
            "job_id": job_id,
            "message": "Simulation command queued to GAMA",
        })

    def _handle_stop(self, data):
        job_id = data.get("job_id")
        if job_id:
            cmd = {"type": "stop", "exp_id": job_id}
            _queue_ws_command(cmd)
            if job_id in simulation_state["jobs"]:
                simulation_state["jobs"][job_id]["state"] = "stopped"
            log.info("Stop queued for job %s", job_id)
            self._send_json(200, {"status": "ok", "message": f"Stop queued for {job_id}"})
        else:
            for jid in list(simulation_state["jobs"].keys()):
                cmd = {"type": "stop", "exp_id": jid}
                _queue_ws_command(cmd)
                simulation_state["jobs"][jid]["state"] = "stopped"
            log.info("Stop queued for all jobs")
            self._send_json(200, {"status": "ok", "message": "All jobs stopped"})


def _queue_ws_command(cmd):
    """Thread-safe: enqueue a command dict for the WS sender coroutine."""
    try:
        loop = asyncio.get_running_loop()
        asyncio.run_coroutine_threadsafe(ws_send_queue.put(cmd), loop)
    except RuntimeError:
        log.warning("No running event loop, command not sent: %s", cmd.get("type"))


class BridgeServer:
    def __init__(self):
        self.http_server = None
        self.http_thread = None
        self.ws_connected = Event()
        self.running = Event()
        self.running.set()
        self._ws = None

    def start(self):
        self.http_server = HTTPServer(("0.0.0.0", BACKEND_PORT), BridgeHandler)
        self.http_thread = Thread(target=self.http_server.serve_forever, daemon=True)
        self.http_thread.start()
        log.info("HTTP server on 127.0.0.1:%d", BACKEND_PORT)

        simulation_state["status"] = "running"

        if HAS_WEBSOCKETS:
            log.info("Connecting to GAMA WS at %s...", GAMA_WS_URL)
            try:
                asyncio.run(self._ws_client())
            except Exception as e:
                log.warning("WS connection failed: %s", e)

        simulation_state["connected"] = False

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
                    self._ws = ws
                    simulation_state["connected"] = True
                    log.info("Connected to GAMA WS")
                    self.ws_connected.set()
                    retry_delay = 2

                    # Start sender task for queued commands
                    sender_task = asyncio.create_task(self._ws_sender(ws))

                    # Read messages from GAMA
                    async for message in ws:
                        self._handle_ws_message(message)

                    sender_task.cancel()

            except (websockets.exceptions.ConnectionClosed,
                    ConnectionRefusedError, OSError) as e:
                simulation_state["connected"] = False
                self._ws = None
                log.debug("WS reconnect in %ds: %s", retry_delay, e)
                await asyncio.sleep(retry_delay)
                retry_delay = min(retry_delay * 1.5, max_delay)

    async def _ws_sender(self, ws):
        """Read commands from the queue and send them to GAMA via WebSocket."""
        while self.running.is_set():
            try:
                cmd = await asyncio.wait_for(ws_send_queue.get(), timeout=1.0)
                payload = json.dumps(cmd)
                log.info("WS send: %s (exp_id=%s)", cmd.get("type"), cmd.get("exp_id", ""))
                await ws.send(payload)
            except asyncio.TimeoutError:
                continue
            except Exception as e:
                log.warning("WS send error: %s", e)

    def _handle_ws_message(self, message):
        """Process incoming messages from GAMA (text JSON or binary PNG)."""
        if isinstance(message, bytes):
            self._handle_binary(message)
            return

        try:
            data = json.loads(message)
        except json.JSONDecodeError:
            return

        msg_type = data.get("type", "unknown")
        log.debug("WS msg: %s", msg_type)

        if msg_type == "ConnectionSuccessful":
            log.info("GAMA WS handshake complete: %s", data)

        elif msg_type == "CommandExecutedSuccessfully":
            exp_id = data.get("exp_id", "")
            cmd_type = data.get("command", "")
            content = data.get("content", {})
            log.info("CommandExecuted: %s for exp=%s", cmd_type, exp_id)

            if exp_id and exp_id in simulation_state["jobs"]:
                job = simulation_state["jobs"][exp_id]
                if cmd_type == "load":
                    job["state"] = "loaded"
                    job["exp_id"] = content.get("exp_id", exp_id)
                    job["progress"] = 0
                elif cmd_type == "play":
                    job["state"] = "running"
                elif cmd_type == "stop":
                    job["state"] = "stopped"

        elif msg_type == "SimulationEnded":
            exp_id = data.get("exp_id", "")
            if exp_id and exp_id in simulation_state["jobs"]:
                simulation_state["jobs"][exp_id]["state"] = "completed"
                simulation_state["jobs"][exp_id]["progress"] = 100
                log.info("Simulation %s completed", exp_id)

        elif msg_type == "SimulationOutput":
            exp_id = data.get("exp_id", "")
            text = data.get("content", str(data))
            if exp_id:
                lines = simulation_state["console"].setdefault(exp_id, [])
                lines.append(text)
            else:
                log.info("GAMA output: %s", text)

        elif msg_type == "SimulationStatus":
            exp_id = data.get("exp_id", "")
            status_text = data.get("content", "")
            if exp_id and exp_id in simulation_state["jobs"]:
                simulation_state["jobs"][exp_id]["status_msg"] = status_text
            log.debug("Status[%s]: %s", exp_id, status_text)

        elif msg_type == "SimulationError":
            exp_id = data.get("exp_id", "")
            err = data.get("content", str(data))
            if exp_id and exp_id in simulation_state["jobs"]:
                simulation_state["jobs"][exp_id]["state"] = "error"
                simulation_state["jobs"][exp_id]["error"] = err
                log.error("Simulation error [%s]: %s", exp_id, err)

        elif msg_type == "UnableToExecuteRequest":
            exp_id = data.get("exp_id", "")
            err = data.get("content", str(data))
            log.warning("GAMA unable to execute [%s]: %s", exp_id, err)
            if exp_id and exp_id in simulation_state["jobs"]:
                simulation_state["jobs"][exp_id]["state"] = "error"
                simulation_state["jobs"][exp_id]["error"] = err

        else:
            log.debug("Unhandled WS message type: %s", msg_type)

    def _handle_binary(self, data):
        """Handle binary WebSocket messages (PNG frames from GAMA)."""
        if not data or len(data) < 8:
            return

        # Check for PNG signature
        if data[:4] != b'\x89PNG':
            log.debug("Binary message (non-PNG, %d bytes)", len(data))
            return

        png = extract_png(data)
        if len(png) < 64:
            log.debug("Binary message too small for PNG (%d bytes)", len(png))
            return

        # Extract exp_id from the trailing marker bytes after PNG IEND
        # Format from GamaServerExperimentJob: [PNG][0x00 marker][index byte]
        # After IEND, the remaining bytes are [marker][index][?]
        extra = data[len(png):]
        exp_id = None

        if len(extra) >= 2:
            # Try to find which experiment this belongs to
            # We know the PNG came from some running experiment
            for jid in list(simulation_state["jobs"].keys()):
                job = simulation_state["jobs"][jid]
                if job.get("state") == "running":
                    exp_id = jid
                    break

        if exp_id:
            simulation_state["frames"][exp_id] = png
            step = simulation_state["jobs"][exp_id].get("current_step", 0)
            simulation_state["jobs"][exp_id]["current_step"] = step + 1
            progress = simulation_state["jobs"][exp_id].get("steps", 100)
            if progress > 0:
                pct = min(int((step + 1) / progress * 100), 100)
                simulation_state["jobs"][exp_id]["progress"] = pct
            log.debug("Frame captured for %s (%d bytes, step %d)", exp_id, len(png), step + 1)
        else:
            # Store as unassigned frame if we can't determine the exp
            simulation_state["frames"]["_latest"] = png
            log.debug("Frame captured (unassigned, %d bytes)", len(png))


def main():
    log.info("GAMA Mobile Bridge Server v0.3.0")
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
