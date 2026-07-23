package gama.browser;

import java.net.InetSocketAddress;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.Executors;
import java.net.ServerSocket;
import java.net.Socket;
import java.io.*;

import gama.api.GAMA;
import gama.api.ui.NullGuiHandler;
import gama.core.CoreActivator;
import gama.workspace.WorkspaceActivator;
import gaml.compiler.GamlStandaloneSetup;
import gama.api.utils.prefs.GamaPreferences;
import gama.headless.runtime.GamaHeadlessWebSocketServer;

public class GamaServerLauncher {

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 6868;
        
        System.out.println("Initializing GAMA headless...");
        System.setProperty("java.awt.headless", "true");
        GAMA.setHeadLessMode(true);
        GAMA.setHeadlessGui(new NullGuiHandler());
        WorkspaceActivator.load();
        GamlStandaloneSetup.doSetup();
        CoreActivator.load();
        GamlStandaloneSetup.initializeAfterPlatformReady(null);
        GamaPreferences.External.CORE_SEED_DEFINED.set(true);
        GamaPreferences.External.CORE_SEED.set(1.0);
        
        System.out.println("Starting WebSocket server on port " + port + "...");
        ThreadPoolExecutor runner = (ThreadPoolExecutor) Executors.newFixedThreadPool(8);
        GamaHeadlessWebSocketServer.startForHeadless(port, runner, 5000, true);

        // Start HTTP ping server on port+1 for health checks
        startPingServer(port + 1);
    }

    private static void startPingServer(int port) {
        new Thread(() -> {
            try (ServerSocket server = new ServerSocket(port)) {
                System.out.println("Ping server on port " + port);
                while (true) {
                    Socket client = server.accept();
                    new Thread(() -> {
                        try {
                            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
                            String line = in.readLine();
                            if (line != null && line.startsWith("GET /ping")) {
                                String response = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nAccess-Control-Allow-Origin: *\r\n\r\npong";
                                client.getOutputStream().write(response.getBytes());
                            }
                            client.close();
                        } catch (Exception e) {}
                    }).start();
                }
            } catch (Exception e) {
                System.err.println("Ping server failed: " + e.getMessage());
            }
        }).start();
    }
}
