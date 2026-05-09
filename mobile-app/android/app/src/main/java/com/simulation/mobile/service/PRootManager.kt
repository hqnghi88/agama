package com.simulation.mobile.service

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

class PRootManager(private val context: Context) {

    companion object {
        const val TAG = "PRootManager"
        const val ROOTFS_ARCHIVE = "rootfs.tar.gz"
        const val PROOT_BINARY = "libproot.so"
        const val STARTUP_SCRIPT = "startup.sh"
    }

    private var prootProcess: Process? = null
    private val isRunning = AtomicBoolean(false)

    fun setupRootfs(rootfsDir: File): Boolean {
        return try {
            if (rootfsDir.exists() && File(rootfsDir, "usr").exists()) {
                Log.i(TAG, "Rootfs already extracted at $rootfsDir")
                return true
            }

            Log.i(TAG, "Setting up rootfs at $rootfsDir")
            rootfsDir.mkdirs()

            val archiveFile = extractRawResource(ROOTFS_ARCHIVE, rootfsDir.parentFile!!)
                ?: return false

            Log.i(TAG, "Extracting rootfs archive: ${archiveFile.absolutePath}")
            val runtime = Runtime.getRuntime()

            // Extract rootfs
            val extractProcess = runtime.exec(arrayOf(
                "tar", "xzf", archiveFile.absolutePath,
                "-C", rootfsDir.absolutePath,
                "--no-same-owner",
                "--no-same-permissions"
            ))
            val exitCode = extractProcess.waitFor()

            if (exitCode != 0) {
                Log.e(TAG, "Rootfs extraction failed with code $exitCode")
                return false
            }

            // Set up PRoot binary
            setupPRootBinary(rootfsDir)

            // Create writable directories
            File(rootfsDir, "tmp").apply { mkdirs(); setWritable(true, false) }
            File(rootfsDir, "data").apply { mkdirs(); setWritable(true, false) }
            File(rootfsDir, "workspace").apply { mkdirs(); setWritable(true, false) }

            // Copy startup scripts
            copyStartupScript(rootfsDir)

            Log.i(TAG, "Rootfs setup complete")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Rootfs setup failed", e)
            false
        }
    }

    fun startPRoot(rootfsDir: File, workspaceDir: File, port: Int, onStarted: (pid: Int) -> Unit) {
        if (isRunning.getAndSet(true)) {
            Log.d(TAG, "PRoot already running")
            return
        }

        try {
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val prootBin = File(nativeLibDir, PROOT_BINARY).absolutePath
            if (!File(prootBin).exists()) {
                Log.e(TAG, "PRoot binary not found at $prootBin")
                isRunning.set(false)
                return
            }

            val startupScript = File(rootfsDir, "opt/gama/startup.sh").absolutePath
            val env = mapOf(
                "HOME" to "/data",
                "TMPDIR" to "/tmp",
                "BACKEND_PORT" to port.toString(),
                "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "JAVA_HOME" to "/usr/lib/jvm/java-17-openjdk-arm64",
                "TERM" to "xterm"
            )

            val cmd = arrayListOf(
                prootBin,
                "-r", rootfsDir.absolutePath,
                "-b", "/dev",
                "-b", "/proc",
                "-b", "/sys",
                "-b", "${workspaceDir.absolutePath}:/workspace",
                "-b", "/sdcard:/sdcard",
                "-w", "/",
                "--kill-on-exit",
                "/bin/bash", startupScript
            )

            Log.i(TAG, "Starting PRoot: ${cmd.joinToString(" ")}")
            val pb = ProcessBuilder(cmd)
                .directory(rootfsDir)
                .redirectErrorStream(true)

            env.forEach { (k, v) -> pb.environment()[k] = v }

            prootProcess = pb.start()

            // Monitor output in background
            Thread {
                try {
                    val reader = BufferedReader(InputStreamReader(prootProcess!!.inputStream))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        Log.d(TAG, "[proot] $line")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "PRoot output stream ended", e)
                }
                isRunning.set(false)
                Log.i(TAG, "PRoot process terminated")
            }.apply {
                name = "proot-monitor"
                start()
            }

            // Get PID
            try {
                val pidFile = File("/proc/${prootProcess!!.pid()}/status")
                if (pidFile.exists()) {
                    onStarted(prootProcess!!.pid())
                }
            } catch (e: Exception) {
                onStarted(-1)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start PRoot", e)
            isRunning.set(false)
        }
    }

    fun stopPRoot() {
        Log.i(TAG, "Stopping PRoot")
        isRunning.set(false)
        prootProcess?.let {
            it.destroyForcibly()
            it.waitFor()
        }
        prootProcess = null
    }

    fun isAlive(): Boolean {
        return prootProcess?.isAlive == true
    }

    // Private helpers

    private fun setupPRootBinary(rootfsDir: File) {
        try {
            val prootFile = File(rootfsDir, "usr/bin/proot")
            if (prootFile.exists()) return

            // PRoot is bundled as libproot.so in jniLibs/arm64-v8a/.
            // Android extracts it to nativeLibraryDir (apk_data_file context)
            // where SELinux allows execute_no_trans. Use it directly.
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val prootBin = File(nativeLibDir, PROOT_BINARY)

            if (!prootBin.exists()) {
                Log.e(TAG, "PRoot binary not found at ${prootBin.absolutePath}")
                return
            }

            Log.i(TAG, "PRoot binary at ${prootBin.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup PRoot binary", e)
        }
    }

    private fun copyStartupScript(rootfsDir: File) {
        try {
            val optDir = File(rootfsDir, "opt/gama")
            optDir.mkdirs()

            // Write startup script directly
            val script = File(optDir, "startup.sh")
            script.writeText(createStartupScript())
            script.setExecutable(true)

            val javaEnv = File(rootfsDir, "etc/profile.d/gama-env.sh")
            javaEnv.writeText(createJavaEnvScript())
            javaEnv.setExecutable(true)

            Log.i(TAG, "Startup script written to ${script.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy startup script", e)
        }
    }

    private fun createStartupScript(): String = """
        |#!/bin/bash
        |set -euo pipefail
        |
        |# GAMA Mobile startup - runs inside PRoot Linux container
        |
        |export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
        |export PATH=${'$'}JAVA_HOME/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
        |export HOME=/data
        |export TMPDIR=/tmp
        |export GAMA_HOME=/opt/gama
        |export BACKEND_PORT=${'$'}{BACKEND_PORT:-8080}
        |export GAMA_WS_PORT=${'$'}{GAMA_WS_PORT:-6868}
        |
        |echo "[startup] GAMA Mobile backend starting"
        |echo "[startup] Java: ${'$'}(java -version 2>&1 | head -1)"
        |echo "[startup] Backend port: ${'$'}BACKEND_PORT"
        |echo "[startup] GAMA WS port: ${'$'}GAMA_WS_PORT"
        |
        |mkdir -p /tmp /data /workspace /opt/gama/logs
        |
        |# Source environment
        |if [ -f /etc/profile.d/gama-env.sh ]; then
        |  source /etc/profile.d/gama-env.sh
        |fi
        |
        |# ─── Start GAMA Headless (if available) ───────────────────────
        |GAMA_AVAILABLE=false
        |if [ -f "${'$'}GAMA_HOME/gama-launcher.sh" ] && \
        |   ls "${'$'}GAMA_HOME/headless/plugins/org.eclipse.equinox.launcher_"*.jar &>/dev/null 2>&1; then
        |  GAMA_AVAILABLE=true
        |  echo "[startup] GAMA headless product detected, starting on port ${'$'}GAMA_WS_PORT..."
        |  ${'$'}GAMA_HOME/gama-launcher.sh \
        |    > /opt/gama/logs/gama-stdout.log 2> /opt/gama/logs/gama-stderr.log &
        |  GAMA_PID=${'$'}!
        |  echo "[startup] GAMA PID: ${'$'}GAMA_PID"
        |
        |  for i in $(seq 1 60); do
        |    if nc -z 127.0.0.1 ${'$'}GAMA_WS_PORT 2>/dev/null; then
        |      echo "[startup] GAMA WebSocket ready on port ${'$'}GAMA_WS_PORT (attempt ${'$'}i)"
        |      break
        |    fi
        |    if [ ${'$'}i -eq 60 ]; then
        |      echo "[startup] WARNING: GAMA WebSocket may not have started"
        |    fi
        |    sleep 1
        |  done
        |else
        |  echo "[startup] GAMA headless not found"
        |fi
        |
        |# ─── Start Bridge Server ──────────────────────────────────────
        |if [ -f "${'$'}GAMA_HOME/bridge-server.py" ]; then
        |  echo "[startup] Starting Python bridge server on port ${'$'}BACKEND_PORT..."
        |  python3 "${'$'}GAMA_HOME/bridge-server.py" \
        |    > /opt/gama/logs/bridge.log 2>&1 &
        |  BRIDGE_PID=${'$'}!
        |  echo "[startup] Bridge PID: ${'$'}BRIDGE_PID"
        |elif [ -f "${'$'}GAMA_HOME/gama-backend.jar" ]; then
        |  echo "[startup] Starting Java backend on port ${'$'}BACKEND_PORT..."
        |  java -jar "${'$'}GAMA_HOME/gama-backend.jar" --port=${'$'}BACKEND_PORT --workspace=/workspace &
        |  BRIDGE_PID=${'$'}!
        |  echo "[startup] Backend PID: ${'$'}BRIDGE_PID"
        |else
        |  echo "[startup] WARNING: No bridge server found"
        |  echo "[startup] Starting minimal HTTP health endpoint..."
        |  python3 -c "
        |import http.server, json
        |class H(http.server.BaseHTTPRequestHandler):
        |  def do_GET(self):
        |    self.send_response(200)
        |    self.send_header('Content-Type', 'application/json')
        |    self.end_headers()
        |    self.wfile.write(json.dumps({'status': 'ok', 'mode': 'minimal'}).encode())
        |  def log_message(self, *a): pass
        |http.server.HTTPServer(('127.0.0.1', ${'$'}BACKEND_PORT), H).serve_forever()
        |" &
        |  BRIDGE_PID=${'$'}!
        |  echo "[startup] Minimal health PID: ${'$'}BRIDGE_PID"
        |fi
        |
        |# Wait for bridge
        |for i in $(seq 1 10); do
        |  if nc -z 127.0.0.1 ${'$'}BACKEND_PORT 2>/dev/null; then
        |    echo "[startup] Bridge server ready!"
        |    break
        |  fi
        |  sleep 1
        |done
        |
        |echo "[startup] Backend initialization complete"
        |echo "[startup] REST API: http://127.0.0.1:${'$'}BACKEND_PORT"
        |echo "[startup] Logs: /opt/gama/logs/"
        |
        |# Monitor processes
        |while true; do
        |  sleep 5
        |  if [ -n "${'$'}{BRIDGE_PID:-}" ] && ! kill -0 ${'$'}BRIDGE_PID 2>/dev/null; then
        |    echo "[startup] Bridge process died"
        |  fi
        |  if [ "${'$'}GAMA_AVAILABLE" = true ] && [ -n "${'$'}{GAMA_PID:-}" ] && ! kill -0 ${'$'}GAMA_PID 2>/dev/null; then
        |    echo "[startup] GAMA process died"
        |  fi
        |done
        |""".trimMargin()

    private fun createJavaEnvScript(): String = """
        |export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
        |export PATH=${'$'}JAVA_HOME/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
        |export _JAVA_OPTIONS="-Xms64m -Xmx512m -XX:+UseG1GC -Djava.awt.headless=true"
        |""".trimMargin()

    private fun extractRawResource(name: String, destDir: File): File? {
        return try {
            val resId = context.resources.getIdentifier(
                name.replace(".", "_"),
                "raw",
                context.packageName
            )
            if (resId == 0) {
                Log.w(TAG, "Resource $name not found in raw/")
                return null
            }
            val destFile = File(destDir, name)
            context.resources.openRawResource(resId).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            destFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract resource $name", e)
            null
        }
    }

    private fun filesDir(): File = context.filesDir

    private fun Process.pid(): Int {
        return try {
            javaClass.name.let { cls ->
                when {
                    cls == "java.lang.ProcessImpl" || cls.startsWith("android.os") -> {
                        val pidField = javaClass.getDeclaredField("pid")
                        pidField.isAccessible = true
                        pidField.getInt(this)
                    }
                    else -> {
                        val pidField = javaClass.getDeclaredField("pid")
                        pidField.isAccessible = true
                        pidField.getInt(this)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot get PID", e)
            -1
        }
    }
}
