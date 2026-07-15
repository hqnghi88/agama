const express = require('express');
const http = require('http');
const { spawn } = require('child_process');
const { WebSocket, WebSocketServer } = require('ws');
const path = require('path');
const cors = require('cors');

const app = express();
app.use(cors());
app.use(express.static(path.join(__dirname, 'public')));
app.use(express.json());

const PORT = process.env.PORT || 3000;
const GAMA_INTERNAL_PORT = 6868;

let gamaProcess = null;
let gamaReady = false;

// ─── Health check for Render ───
app.get('/health', (req, res) => {
    res.json({ status: 'ok', gamaRunning: gamaProcess !== null && !gamaProcess.killed, gamaReady });
});

// ─── Launch GAMA headless as a child process ───
app.post('/launch', async (req, res) => {
    console.log('[Orchestrator] Launch request received...');

    // Kill existing GAMA process if any
    if (gamaProcess && !gamaProcess.killed) {
        console.log('[Orchestrator] Killing existing GAMA process...');
        gamaProcess.kill('SIGTERM');
        gamaProcess = null;
        gamaReady = false;
        // Give it a moment to die
        await new Promise(r => setTimeout(r, 2000));
    }

    try {
        console.log('[Orchestrator] Starting GAMA headless on internal port', GAMA_INTERNAL_PORT);

        gamaProcess = spawn('gama-headless', ['-m', '512m', '-socket', String(GAMA_INTERNAL_PORT)], {
            stdio: ['ignore', 'pipe', 'pipe'],
            env: { ...process.env }
        });

        gamaProcess.stdout.on('data', (data) => {
            const line = data.toString();
            process.stdout.write('[GAMA] ' + line);
            if (line.includes('Server started')) {
                gamaReady = true;
                console.log('[Orchestrator] GAMA server is ready!');
            }
        });

        gamaProcess.stderr.on('data', (data) => {
            process.stderr.write('[GAMA-ERR] ' + data.toString());
        });

        gamaProcess.on('exit', (code) => {
            console.log(`[Orchestrator] GAMA process exited with code ${code}`);
            gamaProcess = null;
            gamaReady = false;
        });

        // Wait for GAMA to be ready (poll for up to 30 seconds)
        const maxWait = 30000;
        const pollInterval = 500;
        let waited = 0;
        while (!gamaReady && waited < maxWait) {
            await new Promise(r => setTimeout(r, pollInterval));
            waited += pollInterval;
        }

        if (gamaReady) {
            res.json({ success: true, message: 'GAMA instance is ready. Connect via WebSocket.' });
        } else {
            res.status(500).json({ success: false, error: 'GAMA did not become ready in time.' });
        }
    } catch (error) {
        console.error('[Orchestrator] Error launching GAMA:', error);
        res.status(500).json({ success: false, error: error.message });
    }
});

// ─── Terminate GAMA ───
app.post('/terminate', (req, res) => {
    if (gamaProcess && !gamaProcess.killed) {
        gamaProcess.kill('SIGTERM');
        gamaProcess = null;
        gamaReady = false;
        console.log('[Orchestrator] GAMA process terminated.');
        res.json({ success: true });
    } else {
        res.json({ success: false, error: 'No GAMA process running.' });
    }
});

// ─── Create HTTP server ───
const server = http.createServer(app);

// ─── WebSocket proxy: browser connects to us, we forward to GAMA ───
const wss = new WebSocketServer({ server, path: '/ws' });

wss.on('connection', (clientSocket) => {
    console.log('[WS-Proxy] Browser client connected.');

    if (!gamaReady) {
        clientSocket.send(JSON.stringify({ type: 'Error', content: 'GAMA is not running. Please launch first.' }));
        clientSocket.close();
        return;
    }

    // Connect to the internal GAMA WebSocket
    const gamaSocket = new WebSocket(`ws://127.0.0.1:${GAMA_INTERNAL_PORT}`);

    gamaSocket.on('open', () => {
        console.log('[WS-Proxy] Connected to internal GAMA server.');
    });

    // Forward messages: GAMA -> Browser
    gamaSocket.on('message', (data) => {
        if (clientSocket.readyState === WebSocket.OPEN) {
            clientSocket.send(data.toString());
        }
    });

    // Forward messages: Browser -> GAMA
    clientSocket.on('message', (data) => {
        if (gamaSocket.readyState === WebSocket.OPEN) {
            gamaSocket.send(data.toString());
        }
    });

    // Handle closures
    clientSocket.on('close', () => {
        console.log('[WS-Proxy] Browser client disconnected.');
        if (gamaSocket.readyState === WebSocket.OPEN) gamaSocket.close();
    });

    gamaSocket.on('close', () => {
        console.log('[WS-Proxy] Internal GAMA connection closed.');
        if (clientSocket.readyState === WebSocket.OPEN) clientSocket.close();
    });

    gamaSocket.on('error', (err) => {
        console.error('[WS-Proxy] GAMA socket error:', err.message);
        if (clientSocket.readyState === WebSocket.OPEN) {
            clientSocket.send(JSON.stringify({ type: 'Error', content: 'Lost connection to GAMA engine.' }));
            clientSocket.close();
        }
    });

    clientSocket.on('error', (err) => {
        console.error('[WS-Proxy] Client socket error:', err.message);
    });
});

server.listen(PORT, () => {
    console.log(`[Orchestrator] GAMA Cloud SaaS running on http://localhost:${PORT}`);
});
