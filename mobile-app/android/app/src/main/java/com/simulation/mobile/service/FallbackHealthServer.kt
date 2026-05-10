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
            client.soTimeout = 15000
            val clientIn = client.getInputStream()
            val clientOut = client.getOutputStream()

            // Read entire raw request: read until we have headers + body
            val rawBuf = ByteArray(8192)
            var totalRead = 0
            var headerEnd = -1
            while (true) {
                if (totalRead >= rawBuf.size) {
                    // Buffer full, grow it
                    break
                }
                val read = clientIn.read(rawBuf, totalRead, rawBuf.size - totalRead)
                if (read < 0) break
                totalRead += read
                val rawStr = String(rawBuf, 0, totalRead, Charsets.UTF_8)
                val idx = rawStr.indexOf("\r\n\r\n")
                if (idx >= 0) {
                    headerEnd = idx + 4
                    // Check if we have all body bytes
                    val contentLen = parseContentLength(rawStr.substring(0, idx))
                    if (contentLen <= 0 || totalRead >= headerEnd + contentLen) {
                        break  // Have all data
                    }
                }
            }

            val rawStr = String(rawBuf, 0, totalRead, Charsets.UTF_8)
            if (headerEnd < 0) {
                throw java.io.EOFException("Incomplete HTTP request")
            }

            val headerBlock = rawStr.substring(0, headerEnd - 4)
            val headerLines = headerBlock.split("\r\n")
            val requestLine = headerLines[0]
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1]

            val headers = mutableMapOf<String, String>()
            var contentLength = 0
            for (i in 1 until headerLines.size) {
                val line = headerLines[i]
                val colon = line.indexOf(":")
                if (colon > 0) {
                    val key = line.substring(0, colon).trim()
                    val value = line.substring(colon + 1).trim()
                    headers[key] = value
                    if (key.equals("Content-Length", ignoreCase = true)) {
                        contentLength = value.toIntOrNull() ?: 0
                    }
                }
            }

            val body = if (contentLength > 0 && totalRead >= headerEnd + contentLength) {
                rawBuf.copyOfRange(headerEnd, headerEnd + contentLength)
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
                if (key.equals("Connection", ignoreCase = true)) continue
                if (key.equals("Transfer-Encoding", ignoreCase = true)) continue
                responseHeaderBuilder.append("$key: $value\r\n")
            }
            val bodyLen = (responseBody?.size ?: 0).toString()
            responseHeaderBuilder.append("Content-Length: $bodyLen\r\n")
            responseHeaderBuilder.append("Connection: close\r\n")
            responseHeaderBuilder.append("Access-Control-Allow-Origin: *\r\n")
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
        var backendSocket: Socket? = null
        try {
            backendSocket = Socket("127.0.0.1", backendPort)
            backendSocket.soTimeout = 10000
            val backendOut = backendSocket.getOutputStream()
            val backendIn = backendSocket.getInputStream()

            val requestBuilder = StringBuilder()
            requestBuilder.append("$method $path HTTP/1.1\r\n")
            requestBuilder.append("Host: 127.0.0.1:$backendPort\r\n")
            for ((key, value) in headers) {
                if (key.equals("Host", ignoreCase = true)) continue
                if (key.equals("Connection", ignoreCase = true)) continue
                if (key.equals("Content-Length", ignoreCase = true)) continue
                if (key.equals("Transfer-Encoding", ignoreCase = true)) continue
                requestBuilder.append("$key: $value\r\n")
            }
            if (body != null && body.isNotEmpty()) {
                requestBuilder.append("Content-Length: ${body.size}\r\n")
            }
            requestBuilder.append("Connection: close\r\n")
            requestBuilder.append("\r\n")

            backendOut.write(requestBuilder.toString().toByteArray(Charsets.UTF_8))
            if (body != null && body.isNotEmpty()) {
                backendOut.write(body)
            }
            backendOut.flush()

            val responseBytes = backendIn.readBytes()
            val responseStr = String(responseBytes, Charsets.UTF_8)

            val headerEnd = responseStr.indexOf("\r\n\r\n")
            val headerBlock = if (headerEnd >= 0) responseStr.substring(0, headerEnd) else responseStr
            val respBodyStart = if (headerEnd >= 0) headerEnd + 4 else responseStr.length

            val lines = headerBlock.split("\r\n")
            val statusParts = lines[0].split(" ", limit = 3)
            val statusCode = statusParts.getOrNull(1)?.toIntOrNull() ?: 502

            val respHeaders = mutableMapOf<String, String>()
            for (i in 1 until lines.size) {
                val colon = lines[i].indexOf(":")
                if (colon > 0) {
                    val key = lines[i].substring(0, colon).trim()
                    val value = lines[i].substring(colon + 1).trim()
                    respHeaders[key] = value
                }
            }

            val respBody = responseBytes.copyOfRange(
                respBodyStart.coerceAtMost(responseBytes.size),
                responseBytes.size
            )

            return Triple(statusCode, respHeaders, respBody)

        } catch (e: Exception) {
            Log.w(TAG, "Backend proxy failed for $method $path: ${e.message}")

            if (path == "/api/health") {
                val fbBody = """{"status":"ok","mode":"fallback"}"""
                val fbHeaders = mapOf(
                    "Content-Type" to "application/json",
                    "Access-Control-Allow-Origin" to "*",
                    "Content-Length" to fbBody.toByteArray(Charsets.UTF_8).size.toString()
                )
                return Triple(200, fbHeaders, fbBody.toByteArray(Charsets.UTF_8))
            }

            if (path == "/api/models") {
                val fbBody = """{"status":"ok","total":0,"models":[]}"""
                val fbHeaders = mapOf(
                    "Content-Type" to "application/json",
                    "Access-Control-Allow-Origin" to "*",
                    "Content-Length" to fbBody.toByteArray(Charsets.UTF_8).size.toString()
                )
                return Triple(200, fbHeaders, fbBody.toByteArray(Charsets.UTF_8))
            }

            val errBody = """{"status":"error","message":"${e.message?.replace("\"", "'") ?: "Backend unavailable"}"}"""
            val errHeaders = mapOf(
                "Content-Type" to "application/json",
                "Access-Control-Allow-Origin" to "*",
                "Content-Length" to errBody.toByteArray(Charsets.UTF_8).size.toString()
            )
            return Triple(502, errHeaders, errBody.toByteArray(Charsets.UTF_8))
        } finally {
            try { backendSocket?.close() } catch (_: Exception) {}
        }
    }

    private fun parseContentLength(headerBlock: String): Int {
        for (line in headerBlock.split("\r\n")) {
            val colon = line.indexOf(":")
            if (colon > 0 && line.substring(0, colon).trim().equals("Content-Length", ignoreCase = true)) {
                return line.substring(colon + 1).trim().toIntOrNull() ?: 0
            }
        }
        return 0
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
