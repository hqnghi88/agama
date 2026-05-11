package com.simulation.mobile.vnc

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class VncRfbClient(
    private val host: String = "127.0.0.1",
    private val port: Int = 5901
) {
    companion object {
        const val TAG = "VncRfbClient"
        private const val RETRY_DELAY_MS = 3000L
        private const val MAX_RETRIES = 120
        private const val FRAME_INTERVAL_MS = 50L
    }

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null

    var fbWidth: Int = 0
        private set
    var fbHeight: Int = 0
        private set

    private val running = AtomicBoolean(false)
    private var bitmap: Bitmap? = null
    private var onFrame: ((Bitmap) -> Unit)? = null
    private var onStateChange: ((VncState) -> Unit)? = null
    private val writeLock = Any()
    private val writeThread = HandlerThread("vnc-write").apply { start() }
    private val writeHandler = Handler(writeThread.looper)

    enum class VncState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    fun start(
        onFrame: (Bitmap) -> Unit,
        onStateChange: (VncState) -> Unit
    ) {
        this.onFrame = onFrame
        this.onStateChange = onStateChange
        thread(name = "vnc-client") { run() }
    }

    fun stop() {
        Log.i(TAG, "Stopping VNC client")
        running.set(false)
        disconnect()
        writeThread.quitSafely()
    }

    private fun run() {
        running.set(true)
        var retries = 0

        while (running.get() && retries < MAX_RETRIES) {
            Log.i(TAG, "Connection attempt ${retries + 1}/$MAX_RETRIES")
            onStateChange?.invoke(VncState.CONNECTING)
            try {
                socket = Socket()
                socket?.connect(InetSocketAddress(host, port), 5000)
                socket?.soTimeout = 0
                input = DataInputStream(socket!!.getInputStream())
                output = DataOutputStream(socket!!.getOutputStream())
                Log.i(TAG, "TCP connected to $host:$port")

                if (negotiate()) {
                    Log.i(TAG, "RFB negotiation complete, entering update loop")
                    onStateChange?.invoke(VncState.CONNECTED)
                    updateLoop()
                    return
                } else {
                    Log.w(TAG, "RFB negotiation failed, will retry")
                }
            } catch (e: SocketTimeoutException) {
                Log.w(TAG, "Connection attempt $retries timed out")
            } catch (e: Exception) {
                Log.w(TAG, "Connection attempt ${retries + 1} failed: ${e.message}")
            }

            disconnect()
            retries++
            if (retries < MAX_RETRIES && running.get()) {
                try { Thread.sleep(RETRY_DELAY_MS) } catch (_: InterruptedException) { break }
            }
        }

        val finalState = if (!running.get()) VncState.DISCONNECTED else VncState.ERROR
        Log.i(TAG, "VNC client finished with state $finalState")
        onStateChange?.invoke(finalState)
    }

    private fun negotiate(): Boolean {
        return try {
            val verBytes = readExactly(12) ?: return negotiateFail("no server version")
            val verStr = String(verBytes)
            Log.i(TAG, "Server version: ${verStr.trim()}")

            writeExactly(verBytes)
            Log.i(TAG, "Sent version: ${verStr.trim()}")

            if (verStr.startsWith("RFB 003.003")) {
                val authScheme = readU32()
                if (authScheme == null || authScheme == 0L) {
                    return negotiateFail("RFB 3.3 auth failed: scheme=0")
                }
                Log.i(TAG, "RFB 3.3 auth scheme: $authScheme")
            } else {
                val count = readU8() ?: return negotiateFail("no security count")
                Log.i(TAG, "Security types count: $count")
                val types = readExactly(count) ?: return negotiateFail("no security types")
                Log.i(TAG, "Security types: ${types.joinToString()}")

                if (!types.contains(1) && !types.contains(2)) {
                    return negotiateFail("no supported auth type in [${types.joinToString()}]")
                }
                val selected = if (types.contains(1)) 1 else 2
                writeU8(selected)
                Log.i(TAG, "Selected security type: $selected")

                val result = readU32() ?: return negotiateFail("no security result")
                if (result != 0L) {
                    return negotiateFail("security result=$result")
                }
                Log.i(TAG, "Security result: OK")
            }

            writeU8(1)
            Log.i(TAG, "Sent ClientInit (shared=1)")

            fbWidth = readU16() ?: return negotiateFail("no fb width")
            fbHeight = readU16() ?: return negotiateFail("no fb height")
            Log.i(TAG, "Framebuffer: ${fbWidth}x${fbHeight}")

            readExactly(16) ?: return negotiateFail("no pixel format")

            val nameLen = readU32() ?: return negotiateFail("no name length")
            val name = if (nameLen > 0) {
                String(readExactly(nameLen.toInt()) ?: ByteArray(0))
            } else ""
            Log.i(TAG, "Desktop name: $name")

            bitmap = Bitmap.createBitmap(fbWidth, fbHeight, Bitmap.Config.ARGB_8888)
            sendSetPixelFormat()
            sendSetEncodings()
            sendFbUpdateRequest(true)

            Log.i(TAG, "Negotiation complete, bitmap created: ${fbWidth}x${fbHeight}")
            true
        } catch (e: Exception) {
            negotiateFail("exception: ${e.message}")
            false
        }
    }

    private fun negotiateFail(reason: String): Boolean {
        Log.w(TAG, "Negotiation failed: $reason")
        return false
    }

    private fun sendSetPixelFormat() {
        synchronized(writeLock) {
            output?.writeByte(0)
            output?.writeByte(0)
            output?.writeByte(0)
            output?.writeByte(0)
            output?.writeByte(32)
            output?.writeByte(24)
            output?.writeByte(0)
            output?.writeByte(1)
            output?.writeShort(255)
            output?.writeShort(255)
            output?.writeShort(255)
            output?.writeByte(16)
            output?.writeByte(8)
            output?.writeByte(0)
            output?.writeByte(0)
            output?.writeByte(0)
            output?.writeByte(0)
            output?.flush()
        }
    }

    private fun sendFbUpdateRequest(incremental: Boolean) {
        synchronized(writeLock) {
            output?.writeByte(3)
            output?.writeByte(if (incremental) 1 else 0)
            output?.writeShort(0)
            output?.writeShort(0)
            output?.writeShort(fbWidth)
            output?.writeShort(fbHeight)
            output?.flush()
        }
    }

    private fun sendSetEncodings() {
        synchronized(writeLock) {
            output?.writeByte(2)
            output?.writeByte(0)
            output?.writeShort(2)
            output?.writeInt(0)
            output?.writeInt(-223)
            output?.flush()
        }
    }

    private fun updateLoop() {
        try {
            while (running.get()) {
                val msgType = readU8() ?: break
                if (msgType != 0) continue

                readU8()
                val numRects = readU16() ?: break

                for (i in 0 until numRects) {
                    val rx = readU16() ?: break
                    val ry = readU16() ?: break
                    val rw = readU16() ?: break
                    val rh = readU16() ?: break
                    val encoding = readS32() ?: break

                    if (encoding == 0) {
                        readRawRect(rx, ry, rw, rh)
                    } else if (encoding == -223) {
                        fbWidth = rw
                        fbHeight = rh
                        bitmap = Bitmap.createBitmap(fbWidth, fbHeight, Bitmap.Config.ARGB_8888)
                    } else {
                        skipRect(rw, rh)
                    }
                }

                bitmap?.let { onFrame?.invoke(it) }
                sendFbUpdateRequest(true)
                Thread.sleep(FRAME_INTERVAL_MS)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Update loop error", e)
        }
        Log.i(TAG, "Update loop ended")
        onStateChange?.invoke(VncState.DISCONNECTED)
    }

    private fun readRawRect(x: Int, y: Int, w: Int, h: Int) {
        val pixels = IntArray(w * h)
        val rowBytes = w * 4
        val buf = ByteArray(rowBytes * h)
        try { readExactly(buf) } catch (_: Exception) { return }

        var src = 0
        for (iy in 0 until h) {
            for (ix in 0 until w) {
                val b = buf[src++].toInt() and 0xFF
                val g = buf[src++].toInt() and 0xFF
                val r = buf[src++].toInt() and 0xFF
                src++
                pixels[iy * w + ix] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        bitmap?.setPixels(pixels, 0, w,
            x.coerceAtLeast(0), y.coerceAtLeast(0),
            w.coerceAtMost(fbWidth - x.coerceAtLeast(0)),
            h.coerceAtMost(fbHeight - y.coerceAtLeast(0)))
    }

    private fun skipRect(w: Int, h: Int) {
        val total = w.toLong() * h * 4
        var remaining = total
        val tmp = ByteArray(8192)
        while (remaining > 0) {
            val n = input?.read(tmp, 0, tmp.size.coerceAtMost(remaining.toInt())) ?: break
            if (n < 0) break
            remaining -= n
        }
    }

    private fun readExactly(size: Int): ByteArray? {
        val buf = ByteArray(size)
        return try { input?.readFully(buf); buf } catch (_: Exception) { null }
    }

    private fun readExactly(buf: ByteArray): Boolean {
        return try { input?.readFully(buf); true } catch (_: Exception) { false }
    }

    private fun readU8(): Int? {
        return try { input?.readUnsignedByte() } catch (_: Exception) { null }
    }

    private fun readU16(): Int? {
        return try { input?.readUnsignedShort() } catch (_: Exception) { null }
    }

    private fun readU32(): Long? {
        return try { input?.readInt()?.toLong()?.and(0xFFFFFFFFL) } catch (_: Exception) { null }
    }

    private fun readS32(): Int? {
        return try { input?.readInt() } catch (_: Exception) { null }
    }

    private fun writeU8(value: Int) {
        try { output?.writeByte(value); output?.flush() } catch (_: Exception) {}
    }

    private fun writeExactly(data: ByteArray) {
        try { output?.write(data); output?.flush() } catch (_: Exception) {}
    }

    fun sendPointerEvent(x: Int, y: Int, buttonMask: Int) {
        val out = output
        if (out == null) { Log.w(TAG, "sendPointerEvent: output is null"); return }
        if (socket?.isConnected != true) { Log.w(TAG, "sendPointerEvent: socket not connected"); return }
        val fx = x.coerceIn(0, fbWidth - 1)
        val fy = y.coerceIn(0, fbHeight - 1)
        writeHandler.post {
            try {
                synchronized(writeLock) {
                    out.writeByte(5)
                    out.writeByte(buttonMask)
                    out.writeShort(fx)
                    out.writeShort(fy)
                    out.flush()
                }
                Log.d(TAG, "PointerEvent: btn=$buttonMask ($fx,$fy)")
            } catch (e: Exception) {
                Log.w(TAG, "sendPointerEvent failed", e)
            }
        }
    }

    fun sendKeyEvent(keysym: Int, downFlag: Boolean) {
        val out = output
        if (out == null) { Log.w(TAG, "sendKeyEvent: output is null"); return }
        if (socket?.isConnected != true) { Log.w(TAG, "sendKeyEvent: socket not connected"); return }
        writeHandler.post {
            try {
                synchronized(writeLock) {
                    out.writeByte(4)
                    out.writeByte(if (downFlag) 1 else 0)
                    out.writeByte(0)
                    out.writeByte(0)
                    out.writeInt(keysym)
                    out.flush()
                }
                val dir = if (downFlag) "down" else "up"
                Log.d(TAG, "KeyEvent: keysym=0x${keysym.toString(16)} $dir")
            } catch (e: Exception) {
                Log.w(TAG, "sendKeyEvent failed", e)
            }
        }
    }

    private fun disconnect() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        input = null
        output = null
    }
}
