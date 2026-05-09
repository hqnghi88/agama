package com.simulation.api;

import com.simulation.api.model.SimulationResponse;
import com.simulation.api.model.SimulationStatus;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class SimulationController {

    private static final Logger LOG = Logger.getLogger(SimulationController.class.getName());

    private final ConcurrentHashMap<String, SimulationStatus> jobs;
    private final AtomicInteger jobCounter;
    private final AtomicLong startTime;
    private final List<String> recentLogs;

    public SimulationController(ConcurrentHashMap<String, SimulationStatus> jobs,
                                 AtomicInteger jobCounter,
                                 AtomicLong startTime) {
        this.jobs = jobs;
        this.jobCounter = jobCounter;
        this.startTime = startTime;
        this.recentLogs = new ArrayList<>();
        addLog("Simulation backend initialized");
    }

    public void handleHealth(HttpExchange exchange) throws IOException {
        addLog("Health check");
        String response = new SimulationResponse("ok")
                .withField("uptime", System.currentTimeMillis() - startTime.get())
                .withField("jobs_running", countRunning())
                .withField("version", "0.1.0")
                .toJson();
        sendJson(exchange, 200, response);
    }

    public void handleStatus(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, new SimulationResponse("error")
                    .withField("message", "Method not allowed").toJson());
            return;
        }

        String query = exchange.getRequestURI().getQuery();
        String jobId = null;
        if (query != null && query.startsWith("job_id=")) {
            jobId = query.substring(7);
        }

        if (jobId != null) {
            SimulationStatus status = jobs.get(jobId);
            if (status == null) {
                sendJson(exchange, 404, new SimulationResponse("error")
                        .withField("message", "Job not found: " + jobId).toJson());
                return;
            }
            sendJson(exchange, 200, status.toJson());
        } else {
            List<SimulationStatus> activeJobs = jobs.values().stream()
                    .filter(s -> !"completed".equals(s.getState()))
                    .collect(Collectors.toList());
            String response = new SimulationResponse("ok")
                    .withField("running", !activeJobs.isEmpty())
                    .withField("jobs", activeJobs.stream().map(SimulationStatus::toMap).collect(Collectors.toList()))
                    .withField("uptime", System.currentTimeMillis() - startTime.get())
                    .toJson();
            sendJson(exchange, 200, response);
        }
    }

    public void handleList(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, new SimulationResponse("error")
                    .withField("message", "Method not allowed").toJson());
            return;
        }

        List<SimulationStatus> allJobs = new ArrayList<>(jobs.values());
        String response = new SimulationResponse("ok")
                .withField("total", allJobs.size())
                .withField("jobs", allJobs.stream().map(SimulationStatus::toMap).collect(Collectors.toList()))
                .toJson();
        sendJson(exchange, 200, response);
    }

    public void handleStart(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, new SimulationResponse("error")
                    .withField("message", "Method not allowed").toJson());
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        LOG.info("Start request: " + body);

        String jobId = UUID.randomUUID().toString().substring(0, 8);
        int jobNum = jobCounter.incrementAndGet();

        SimulationStatus status = new SimulationStatus(jobId, "running")
                .withField("name", "simulation-" + jobNum)
                .withField("progress", 0)
                .withField("steps", 100);

        jobs.put(jobId, status);
        addLog("Started job " + jobId);

        startSimulationThread(jobId, status, body);

        String response = new SimulationResponse("ok")
                .withField("job_id", jobId)
                .withField("status", "started")
                .toJson();
        sendJson(exchange, 200, response);
    }

    public void handleStop(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, new SimulationResponse("error")
                    .withField("message", "Method not allowed").toJson());
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        LOG.info("Stop request: " + body);

        String jobId = extractJobId(body);
        if (jobId != null && jobs.containsKey(jobId)) {
            SimulationStatus status = jobs.get(jobId);
            status.withField("state", "stopped");
            jobs.put(jobId, status);
            addLog("Stopped job " + jobId);
        }

        String response = new SimulationResponse("ok")
                .withField("message", jobId != null ? "Job " + jobId + " stopped" : "All jobs stop requested")
                .toJson();
        sendJson(exchange, 200, response);
    }

    private void startSimulationThread(String jobId, SimulationStatus status, String config) {
        Thread worker = new Thread(() -> {
            try {
                int totalSteps = 100;
                for (int step = 1; step <= totalSteps; step++) {
                    Thread.sleep(200);
                    status.withField("progress", (step * 100) / totalSteps);
                    status.withField("current_step", step);
                    status.withField("state", "running");
                    addLog("Job " + jobId + " step " + step + "/" + totalSteps);
                }
                status.withField("state", "completed");
                status.withField("progress", 100);
                addLog("Job " + jobId + " completed");
            } catch (InterruptedException e) {
                status.withField("state", "stopped");
                addLog("Job " + jobId + " interrupted");
                Thread.currentThread().interrupt();
            }
        }, "sim-" + jobId);
        worker.setDaemon(true);
        worker.start();
    }

    private String extractJobId(String body) {
        if (body == null || body.isEmpty()) return null;
        String key = "\"job_id\":";
        int idx = body.indexOf(key);
        if (idx < 0) return null;
        int start = idx + key.length();
        while (start < body.length() && Character.isWhitespace(body.charAt(start))) start++;
        if (start >= body.length()) return null;
        if (body.charAt(start) == '"') {
            int end = body.indexOf('"', start + 1);
            return end > start ? body.substring(start + 1, end) : null;
        }
        int end = start;
        while (end < body.length() && body.charAt(end) != ',' && body.charAt(end) != '}' && !Character.isWhitespace(body.charAt(end))) end++;
        return body.substring(start, end);
    }

    private int countRunning() {
        return (int) jobs.values().stream().filter(s -> "running".equals(s.getState())).count();
    }

    private void addLog(String message) {
        recentLogs.add(0, java.time.Instant.now().toString() + " - " + message);
        if (recentLogs.size() > 100) recentLogs.remove(recentLogs.size() - 1);
    }

    private void sendJson(HttpExchange exchange, int statusCode, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
