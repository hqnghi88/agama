package com.simulation.mobile.service

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

class FallbackHealthServer(private val listenPort: Int, private val backendPort: Int) {

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
            serverSocket = ServerSocket(listenPort, 10, java.net.InetAddress.getByName("127.0.0.1"))
            Log.i(TAG, "Proxy server listening on 127.0.0.1:$listenPort, backend 127.0.0.1:$backendPort")
            serverThread = Thread {
                try {
                    while (running.get()) {
                        val client = serverSocket?.accept() ?: break
                        Thread { handleClient(client) }.apply {
                            isDaemon = true
                            name = "proxy-worker"
                            start()
                        }
                    }
                } catch (e: java.net.SocketException) {
                    if (running.get()) Log.e(TAG, "Server socket error", e)
                } catch (e: Exception) {
                    Log.e(TAG, "Server error", e)
                }
                Log.i(TAG, "Proxy server stopped")
            }.apply {
                isDaemon = true
                name = "proxy-server"
                start()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start proxy server on port $listenPort", e)
            running.set(false)
            false
        }
    }

    fun stop() {
        Log.i(TAG, "Stopping proxy server")
        running.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        serverThread = null
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 10000
            val clientIn = client.getInputStream()
            val clientOut = client.getOutputStream()

            // Parse request from client
            val reader = BufferedReader(InputStreamReader(clientIn))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1]

            val headers = mutableMapOf<String, String>()
            var contentLength = 0
            var headerLine: String?
            while (reader.readLine().also { headerLine = it } != null) {
                val h = headerLine ?: break
                if (h.isEmpty()) break
                val colon = h.indexOf(":")
                if (colon > 0) {
                    val key = h.substring(0, colon).trim()
                    val value = h.substring(colon + 1).trim()
                    headers[key] = value
                    if (key.equals("Content-Length", ignoreCase = true)) {
                        contentLength = value.toIntOrNull() ?: 0
                    }
                }
            }

            val body = if (contentLength > 0) {
                val buf = ByteArray(contentLength)
                var total = 0
                while (total < contentLength) {
                    val read = clientIn.read(buf, total, contentLength - total)
                    if (read < 0) throw java.io.EOFException("Unexpected EOF")
                    total += read
                }
                buf
            } else null

            // Try proxying to backend
            val (statusCode, responseHeaders, responseBody) = tryProxy(method, path, headers, body)

            // Build and send response
            val statusText = when (statusCode) {
                200 -> "OK"
                201 -> "Created"
                204 -> "No Content"
                400 -> "Bad Request"
                404 -> "Not Found"
                500 -> "Internal Server Error"
                502 -> "Bad Gateway"
                else -> "Unknown"
            }

            val responseHeaderBuilder = StringBuilder()
            responseHeaderBuilder.append("HTTP/1.1 $statusCode $statusText\r\n")
            for ((key, value) in responseHeaders) {
                responseHeaderBuilder.append("$key: $value\r\n")
            }
            responseHeaderBuilder.append("Connection: close\r\n")
            responseHeaderBuilder.append("\r\n")

            clientOut.write(responseHeaderBuilder.toString().toByteArray(Charsets.UTF_8))
            if (responseBody != null) {
                clientOut.write(responseBody)
            }
            clientOut.flush()

        } catch (e: Exception) {
            Log.w(TAG, "Error handling request", e)
            try {
                val errBody = """{"status":"error","message":"${e.message?.replace("\"", "'") ?: "Unknown"}"}"""
                val response = buildHttpResponse(502, "Bad Gateway", "application/json", errBody)
                client.getOutputStream().write(response.toByteArray(Charsets.UTF_8))
                client.getOutputStream().flush()
            } catch (_: Exception) {}
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun tryProxy(
        method: String,
        path: String,
        headers: Map<String, String>,
        body: ByteArray?
    ): Triple<Int, Map<String, String>, ByteArray?> {
        try {
            val url = URL("http://127.0.0.1:$backendPort$path")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 10000
            conn.instanceFollowRedirects = false
            conn.requestMethod = method

            for ((key, value) in headers) {
                if (key.equals("Host", ignoreCase = true)) continue
                if (key.equals("Connection", ignoreCase = true)) continue
                if (key.equals("Content-Length", ignoreCase = true)) continue
                conn.setRequestProperty(key, value)
            }

            if (body != null && body.isNotEmpty()) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.write(body)
            }

            val responseCode = conn.responseCode
            val respHeaders = mutableMapOf<String, String>()
            var headerIdx = 0
            while (true) {
                val key = conn.getHeaderFieldKey(headerIdx) ?: break
                val value = conn.getHeaderField(headerIdx) ?: ""
                if (key.isNotEmpty()) {
                    respHeaders[key] = value
                }
                headerIdx++
            }

            val responseBody = try {
                conn.inputStream?.readBytes()
            } catch (e: Exception) {
                try { conn.errorStream?.readBytes() } catch (_: Exception) { null }
            }

            // Override Content-Length if body was read
            if (responseBody != null) {
                respHeaders["Content-Length"] = responseBody.size.toString()
            }
            // Remove transfer-encoding chunked since we dechunk
            respHeaders.remove("Transfer-Encoding")

            return Triple(responseCode, respHeaders, responseBody)

        } catch (e: Exception) {
            Log.w(TAG, "Backend proxy failed for $method $path: ${e.message}")

            // Fallback: serve health check locally
            if (path == "/api/health") {
                val fbBody = """{"status":"ok","mode":"fallback"}"""
                val fbHeaders = mapOf(
                    "Content-Type" to "application/json",
                    "Access-Control-Allow-Origin" to "*",
                    "Content-Length" to fbBody.toByteArray(Charsets.UTF_8).size.toString()
                )
                return Triple(200, fbHeaders, fbBody.toByteArray(Charsets.UTF_8))
            }

            // For other paths, return unavailable
            val errBody = """{"status":"error","message":"Backend unavailable"}"""
            val errHeaders = mapOf(
                "Content-Type" to "application/json",
                "Access-Control-Allow-Origin" to "*",
                "Content-Length" to errBody.toByteArray(Charsets.UTF_8).size.toString()
            )
            return Triple(502, errHeaders, errBody.toByteArray(Charsets.UTF_8))
        }
    }

    private fun buildHttpResponse(statusCode: Int, statusText: String, contentType: String, body: String): String {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        return (
            "HTTP/1.1 $statusCode $statusText\r\n" +
            "Content-Type: $contentType\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS\r\n" +
            "Access-Control-Allow-Headers: Content-Type, Authorization\r\n" +
            "Content-Length: ${bodyBytes.size}\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
            body
        )
    }
}
