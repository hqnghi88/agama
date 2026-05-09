package com.simulation.api;

import com.simulation.api.model.SimulationStatus;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SimulationApiServer {

    private static final Logger LOG = Logger.getLogger(SimulationApiServer.class.getName());

    private final int port;
    private final String workspace;
    private HttpServer server;
    private final ConcurrentHashMap<String, SimulationStatus> jobs = new ConcurrentHashMap<>();
    private final AtomicInteger jobCounter = new AtomicInteger(0);
    private final AtomicLong startTime = new AtomicLong(0);
    private final ThreadPoolExecutor executor;

    public SimulationApiServer(int port, String workspace) {
        this.port = port;
        this.workspace = workspace;
        this.executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(4);
    }

    public void start() throws IOException {
        startTime.set(System.currentTimeMillis());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(executor);

        SimulationController controller = new SimulationController(jobs, jobCounter, startTime);
        server.createContext("/api/health", controller::handleHealth);
        server.createContext("/api/simulation/start", controller::handleStart);
        server.createContext("/api/simulation/stop", controller::handleStop);
        server.createContext("/api/simulation/status", controller::handleStatus);
        server.createContext("/api/simulation/list", controller::handleList);

        server.start();
        LOG.info("Simulation API server started on 127.0.0.1:" + port);
        LOG.info("Workspace: " + workspace);
        LOG.info("Available endpoints:");
        LOG.info("  GET  /api/health");
        LOG.info("  GET  /api/simulation/status");
        LOG.info("  GET  /api/simulation/list");
        LOG.info("  POST /api/simulation/start");
        LOG.info("  POST /api/simulation/stop");
    }

    public void stop() {
        if (server != null) {
            server.stop(2);
        }
        executor.shutdownNow();
        LOG.info("Simulation API server stopped");
    }

    public boolean isRunning() {
        return server != null;
    }

    public static void main(String[] args) {
        int port = 8080;
        String workspace = "/workspace";

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port":
                    if (i + 1 < args.length) port = Integer.parseInt(args[++i]);
                    break;
                case "--workspace":
                    if (i + 1 < args.length) workspace = args[++i];
                    break;
                case "--log":
                    if (i + 1 < args.length) {
                        System.setProperty("java.util.logging.config.file", args[++i]);
                    }
                    break;
            }
        }

        try {
            SimulationApiServer apiServer = new SimulationApiServer(port, workspace);
            apiServer.start();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                LOG.info("Shutting down...");
                apiServer.stop();
            }));

            LOG.info("Backend ready on port " + port);
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Failed to start server", e);
            System.exit(1);
        }
    }
}
