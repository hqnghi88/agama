package com.simulation.mobile.service

import android.content.Context
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class VncProxyServer(private val context: Context) {

    companion object {
        const val TAG = "VncProxyServer"
        const val WS_PROXY_PORT = 6080
        const val HTTP_PORT = 6090
        const val VNC_HOST = "127.0.0.1"
        const val VNC_PORT = 5901
        private val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    }

    private val running = AtomicBoolean(false)
    private var httpServer: ServerSocket? = null
    private var wsProxyServer: ServerSocket? = null

    val httpPort: Int get() = httpServer?.localPort ?: HTTP_PORT
    val wsProxyPort: Int get() = wsProxyServer?.localPort ?: WS_PROXY_PORT
    val isRunning: Boolean get() = running.get()

    fun start(): Boolean {
        if (running.getAndSet(true)) return true
        return try {
            httpServer = ServerSocket(HTTP_PORT, 10, InetAddress.getByName("0.0.0.0"))
            wsProxyServer = ServerSocket(WS_PROXY_PORT, 10, InetAddress.getByName("0.0.0.0"))
            Log.i(TAG, "HTTP server on 0.0.0.0:${httpServer?.localPort}")
            Log.i(TAG, "WS proxy on 0.0.0.0:${wsProxyServer?.localPort}")
            thread(name = "vnc-http") { acceptHttp() }
            thread(name = "vnc-ws") { acceptWs() }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start servers", e)
            running.set(false)
            false
        }
    }

    fun stop() {
        running.set(false)
        try { httpServer?.close() } catch (_: Exception) {}
        try { wsProxyServer?.close() } catch (_: Exception) {}
        httpServer = null
        wsProxyServer = null
        Log.i(TAG, "Proxy servers stopped")
    }

    private fun acceptHttp() {
        try {
            while (running.get()) {
                val client = httpServer?.accept() ?: break
                thread(name = "http-worker", isDaemon = true) { handleHttp(client) }
            }
        } catch (e: SocketException) {
            if (running.get()) Log.w(TAG, "HTTP server error", e)
        }
    }

    private fun acceptWs() {
        try {
            while (running.get()) {
                val client = wsProxyServer?.accept() ?: break
                thread(name = "ws-worker", isDaemon = true) { handleWebSocket(client) }
            }
        } catch (e: SocketException) {
            if (running.get()) Log.w(TAG, "WS proxy error", e)
        }
    }

    private fun handleHttp(client: Socket) {
        try {
            client.soTimeout = 10000
            val reader = java.io.BufferedReader(java.io.InputStreamReader(client.getInputStream()))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            var path = parts[1]
            val qIdx = path.indexOf('?')
            if (qIdx >= 0) path = path.substring(0, qIdx)

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line!!.isEmpty()) break
            }

            if (method != "GET") {
                respond(client, 405, "Method Not Allowed", "text/plain", "Method not allowed")
                return
            }

            serveFile(client, path)
        } catch (e: Exception) {
            Log.w(TAG, "HTTP handler error", e)
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun serveFile(client: Socket, path: String) {
        val assetPath = "novnc${if (path == "/") "/vnc_lite.html" else path}"
        try {
            context.assets.open(assetPath).use { stream ->
                val bytes = stream.readBytes()
                val ext = assetPath.substringAfterLast('.', "")
                val mime = when (ext) {
                    "html" -> "text/html; charset=utf-8"
                    "js" -> "application/javascript; charset=utf-8"
                    "css" -> "text/css; charset=utf-8"
                    "png" -> "image/png"
                    "svg" -> "image/svg+xml"
                    "json" -> "application/json"
                    "woff2" -> "font/woff2"
                    "ico" -> "image/x-icon"
                    else -> "application/octet-stream"
                }
                respond(client, 200, "OK", mime, bytes)
            }
        } catch (e: FileNotFoundException) {
            respond(client, 404, "Not Found", "text/plain", "Not found: $path")
        }
    }

    private fun respond(client: Socket, status: Int, message: String, contentType: String, body: String) {
        respond(client, status, message, contentType, body.toByteArray(Charsets.UTF_8))
    }

    private fun respond(client: Socket, status: Int, message: String, contentType: String, body: ByteArray) {
        val out = client.getOutputStream()
        val header = "HTTP/1.1 $status $message\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        if (body.isNotEmpty()) out.write(body)
        out.flush()
    }

    private fun handleWebSocket(client: Socket) {
        try {
            client.soTimeout = 30000
            val input = client.getInputStream()
            val output = client.getOutputStream()

            val header = readHttpHeader(input)
            val headerStr = String(header, Charsets.UTF_8)

            val keyLine = headerStr.lineSequence()
                .firstOrNull { it.trimStart().startsWith("Sec-WebSocket-Key", ignoreCase = true) }
                ?: run { Log.w(TAG, "Missing WebSocket key"); return }
            val key = keyLine.substringAfter(':').trim()

            val accept = computeAccept(key)
            val response = "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $accept\r\n" +
                "\r\n"
            output.write(response.toByteArray(Charsets.UTF_8))
            output.flush()

            val vnc = Socket(VNC_HOST, VNC_PORT)
            vnc.soTimeout = 30000
            val vncIn = vnc.getInputStream()
            val vncOut = vnc.getOutputStream()

            val relayActive = AtomicBoolean(true)

            thread(name = "ws2vnc", isDaemon = true) {
                try {
                    while (relayActive.get()) {
                        val frame = readFrame(input) ?: break
                        when (frame.opcode) {
                            8 -> { relayActive.set(false); break }
                            9 -> sendFrame(output, 0x0A, frame.payload)
                            1, 2 -> {
                                unmask(frame)
                                vncOut.write(frame.payload)
                                vncOut.flush()
                            }
                        }
                    }
                } catch (_: Exception) { /* connection closed */ }
                relayActive.set(false)
                try { vnc.close() } catch (_: Exception) {}
            }

            thread(name = "vnc2ws", isDaemon = true) {
                try {
                    val buf = ByteArray(65536)
                    while (relayActive.get()) {
                        val n = vncIn.read(buf)
                        if (n < 0) break
                        sendFrame(output, 0x02, buf.copyOf(n))
                    }
                } catch (_: Exception) { /* connection closed */ }
                relayActive.set(false)
                try { client.close() } catch (_: Exception) {}
            }

        } catch (e: Exception) {
            Log.w(TAG, "WS proxy error", e)
        }
    }

    private fun readHttpHeader(input: InputStream): ByteArray {
        val buf = ByteArrayOutputStream()
        var pp = -1
        var p = -1
        while (true) {
            val b = input.read()
            if (b < 0) break
            buf.write(b)
            if (pp == '\r'.code && p == '\n'.code && b == '\r'.code) {
                val next = input.read()
                if (next == '\n'.code) {
                    buf.write(next)
                    break
                }
            }
            pp = p
            p = b
        }
        return buf.toByteArray()
    }

    private data class WsFrame(val opcode: Int, val payload: ByteArray, val mask: ByteArray?)

    private fun readFrame(input: InputStream): WsFrame? {
        val b0 = input.read()
        if (b0 < 0) return null
        val opcode = b0 and 0x0F

        val b1 = input.read()
        if (b1 < 0) return null
        val masked = (b1 and 0x80) != 0
        var len = (b1 and 0x7F).toLong()

        if (len == 126L) {
            val hi = input.read()
            val lo = input.read()
            if (hi < 0 || lo < 0) return null
            len = ((hi shl 8) or lo).toLong()
        } else if (len == 127L) {
            len = 0L
            repeat(8) { len = (len shl 8) or input.read().toLong() }
        }

        val mask = if (masked) byteArrayOf(
            input.read().toByte(), input.read().toByte(),
            input.read().toByte(), input.read().toByte()
        ) else null

        val payload = if (len > 0) {
            val data = ByteArray(len.toInt())
            var total = 0
            while (total < len) {
                val r = input.read(data, total, data.size - total)
                if (r < 0) return null
                total += r
            }
            data
        } else ByteArray(0)

        return WsFrame(opcode, payload, mask)
    }

    private fun unmask(frame: WsFrame) {
        val mask = frame.mask ?: return
        for (i in frame.payload.indices) {
            frame.payload[i] = (frame.payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        }
    }

    private fun sendFrame(output: OutputStream, opcode: Int, payload: ByteArray) {
        output.write(0x80 or opcode)
        val len = payload.size
        when {
            len < 126 -> output.write(len)
            len < 65536 -> {
                output.write(126)
                output.write((len shr 8) and 0xFF)
                output.write(len and 0xFF)
            }
            else -> {
                output.write(127)
                var l = len.toLong()
                for (i in 7 downTo 0) {
                    output.write((l shr (i * 8)).toInt() and 0xFF)
                }
            }
        }
        if (len > 0) output.write(payload)
        output.flush()
    }

    private fun computeAccept(key: String): String {
        val sha1 = MessageDigest.getInstance("SHA-1")
        sha1.update(key.toByteArray(Charsets.UTF_8))
        sha1.update(WS_GUID.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(sha1.digest())
    }
}
