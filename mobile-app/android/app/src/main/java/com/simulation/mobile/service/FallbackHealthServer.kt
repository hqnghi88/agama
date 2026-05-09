package com.simulation.mobile.service

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean

class FallbackHealthServer(private val port: Int) {

    companion object {
        const val TAG = "FallbackHealthServer"
    }

    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    private val running = AtomicBoolean(false)

    val isRunning: Boolean get() = running.get()

    fun start(): Boolean {
        if (running.getAndSet(true)) return true
        return try {
            serverSocket = ServerSocket(port, 10, java.net.InetAddress.getByName("127.0.0.1"))
            Log.i(TAG, "Fallback health server listening on 127.0.0.1:$port")
            serverThread = Thread {
                try {
                    while (running.get()) {
                        val client = serverSocket?.accept() ?: break
                        Thread worker@ {
                            try {
                                client.soTimeout = 5000
                                val reader = BufferedReader(InputStreamReader(client.inputStream))
                                val requestLine = reader.readLine()
                                if (requestLine == null) {
                                    client.close()
                                    return@worker
                                }
                                Log.d(TAG, "Request: $requestLine")

                                var contentLength = 0
                                var headerLine: String?
                                while (reader.readLine().also { headerLine = it } != null) {
                                    val h = headerLine ?: break
                                    if (h.isEmpty()) break
                                    if (h.startsWith("Content-Length:", ignoreCase = true)) {
                                        contentLength = h.substringAfter(":").trim().toIntOrNull() ?: 0
                                    }
                                }

                                if (contentLength > 0) {
                                    reader.read(CharArray(contentLength))
                                }

                                val responseBody = """{"status":"ok","mode":"fallback"}"""
                                val response = (
                                    "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: application/json\r\n" +
                                    "Access-Control-Allow-Origin: *\r\n" +
                                    "Access-Control-Allow-Methods: GET, OPTIONS\r\n" +
                                    "Access-Control-Allow-Headers: Content-Type\r\n" +
                                    "Content-Length: ${responseBody.toByteArray().size}\r\n" +
                                    "Connection: close\r\n" +
                                    "\r\n" +
                                    responseBody
                                )
                                client.getOutputStream().write(response.toByteArray(Charsets.UTF_8))
                                client.getOutputStream().flush()
                            } catch (e: Exception) {
                                Log.w(TAG, "Error handling request", e)
                            } finally {
                                try { client.close() } catch (_: Exception) {}
                            }
                        }.apply { isDaemon = true; name = "fallback-http-worker"; start() }
                    }
                } catch (e: java.net.SocketException) {
                    if (running.get()) {
                        Log.e(TAG, "Server socket error", e)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Server error", e)
                }
                Log.i(TAG, "Fallback health server stopped")
            }.apply {
                isDaemon = true
                name = "fallback-health-server"
                start()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start fallback health server on port $port", e)
            running.set(false)
            false
        }
    }

    fun stop() {
        Log.i(TAG, "Stopping fallback health server")
        running.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        serverThread = null
    }
}
