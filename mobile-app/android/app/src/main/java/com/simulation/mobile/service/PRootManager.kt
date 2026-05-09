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
    }

    private var prootProcess: Process? = null
    private val isRunning = AtomicBoolean(false)

    fun setupRootfs(rootfsDir: File): Boolean {
        return try {
            if (rootfsDir.exists() && File(rootfsDir, "usr").exists()) {
                Log.i(TAG, "Rootfs already extracted at $rootfsDir")
            } else {
                Log.i(TAG, "Setting up rootfs at $rootfsDir")
                rootfsDir.mkdirs()

                val archiveFile = extractRawResource(ROOTFS_ARCHIVE, rootfsDir.parentFile!!)
                    ?: return false

                Log.i(TAG, "Extracting rootfs archive: ${archiveFile.absolutePath}")

                Log.i(TAG, "Archive size: ${archiveFile.length()} bytes")
                val pb = ProcessBuilder(
                    "tar", "xzf", archiveFile.absolutePath,
                    "-C", rootfsDir.absolutePath
                ).redirectErrorStream(true)
                val extractProcess = pb.start()
                val output = extractProcess.inputStream.bufferedReader().readText()
                val exitCode = extractProcess.waitFor()
                Log.i(TAG, "Tar exit code: $exitCode")

                if (exitCode != 0) {
                    Log.e(TAG, "Rootfs extraction failed with code $exitCode")
                    Log.e(TAG, "Tar output: ${output.take(500)}")
                    Log.i(TAG, "Archive file exists: ${archiveFile.exists()}, size: ${archiveFile.length()}")
                    Log.i(TAG, "Rootfs dir exists: ${rootfsDir.exists()}, writable: ${rootfsDir.canWrite()}")
                    return false
                }
            }

            // Fix permissions: Android toybox tar doesn't preserve archive permissions
            // so we fix them explicitly
            fixRootfsPermissions(rootfsDir)

            // Always ensure writable directories exist
            File(rootfsDir, "tmp").apply {
                mkdirs()
                setReadable(true, false)
                setWritable(true, false)
                setExecutable(true, false)
            }
            File(rootfsDir, "data").apply { mkdirs(); setWritable(true, false); setExecutable(true, false) }
            File(rootfsDir, "workspace").apply { mkdirs(); setWritable(true, false); setExecutable(true, false) }
            File(rootfsDir, "opt/gama/logs").apply { mkdirs(); setWritable(true, false) }

            // Write fallback startup script (in case rootfs /startup.sh is missing)
            writeFallbackStartup(rootfsDir)

            Log.i(TAG, "Rootfs setup complete")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Rootfs setup failed", e)
            false
        }
    }

    private fun fixRootfsPermissions(rootfsDir: File) {
        try {
            // Toybox tar on Android doesn't preserve permissions,
            // so everything ends up 0700. Fix with chmod.
            Log.i(TAG, "Fixing rootfs permissions...")
            val runtime = Runtime.getRuntime()

            // Make user rwx, group/other rx (adds world permissions without stripping owner)
            runtime.exec(arrayOf(
                "chmod", "-R", "u+rwX,go+rX",
                rootfsDir.absolutePath
            )).waitFor()

            // /tmp needs world-writable with sticky bit
            runtime.exec(arrayOf(
                "chmod", "1777",
                File(rootfsDir, "tmp").absolutePath
            )).waitFor()

            // /root should stay private
            runtime.exec(arrayOf(
                "chmod", "700",
                File(rootfsDir, "root").absolutePath
            )).waitFor()

            Log.i(TAG, "Rootfs permissions fixed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fix rootfs permissions", e)
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

            // Use guest path - PRoot translates it to the host path
            val guestStartup = "/opt/gama/startup.sh"  // path inside the guest
            // PROOT_TMP_DIR must be a HOST path the app can write to.
            // Must be OUTSIDE the rootfs to avoid double-translation by PRoot.
            // The app's internal cache dir is world-writable only by our app.
            val prootTmpDir = File(context.cacheDir, "proot-tmp").also { it.mkdirs() }.absolutePath
            val env = mapOf(
                "HOME" to "/data",
                "TMPDIR" to "/tmp",
                "PROOT_TMP_DIR" to prootTmpDir,
                "BACKEND_PORT" to port.toString(),
                "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/bin",
                "JAVA_HOME" to "/usr/lib/jvm/java-17-openjdk-arm64",
                "PROOT_NO_SECCOMP" to "1",
                "TERM" to "xterm"
            )

            val cmd = arrayListOf(
                prootBin,
                "-v", "9",
                "-r", rootfsDir.absolutePath,
                "-b", "/dev",
                "-b", "/proc",
                "-b", "/sys",
                "-b", "${workspaceDir.absolutePath}:/workspace",
                "-b", "/sdcard:/sdcard",
                "-w", "/",
                "--kill-on-exit",
                "/usr/bin/bash", guestStartup
            )

            Log.i(TAG, "Starting PRoot: ${cmd.joinToString(" ")}")
            val pb = ProcessBuilder(cmd)
                .directory(rootfsDir)
                .redirectErrorStream(true)

            env.forEach { (k, v) -> pb.environment()[k] = v }

            prootProcess = pb.start()

            // Monitor PRoot output in background
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
                val exitCode = try { prootProcess?.waitFor() } catch (e: Exception) { -1 }
                isRunning.set(false)
                Log.i(TAG, "PRoot process terminated (exit code: $exitCode)")
            }.apply {
                name = "proot-monitor"
                start()
            }

            // Report PID
            try {
                onStarted(try { prootProcess!!.pid() } catch (e: Exception) { -1 })
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

    /**
     * Write a fallback startup script at /opt/gama/startup.sh
     * that first tries to source the rootfs /startup.sh (from build-rootfs),
     * and falls back to a minimal health endpoint.
     */
    private fun writeFallbackStartup(rootfsDir: File) {
        try {
            val optDir = File(rootfsDir, "opt/gama")
            optDir.mkdirs()

            val script = File(optDir, "startup.sh")
            script.writeText(generateStartupScript())
            script.setExecutable(true)

            Log.i(TAG, "Startup script written to ${script.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write startup script", e)
        }
    }

    private fun generateStartupScript(): String = """
        |#!/bin/bash
        |
        |# GAMA Mobile startup - runs inside PRoot Linux container
        |
        |unset LD_PRELOAD
        |export PROOT_NO_SECCOMP=1
        |export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
        |export PATH=${'$'}JAVA_HOME/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
        |export HOME=/data
        |export TMPDIR=/tmp
        |export GAMA_HOME=/opt/gama
        |export BACKEND_PORT=${'$'}{BACKEND_PORT:-8080}
        |export GAMA_WS_PORT=${'$'}{GAMA_WS_PORT:-6868}
        |
        |echo "[startup] GAMA Mobile backend starting"
        |echo "[startup] Java: $(java -version 2>&1 | head -1 2>/dev/null || echo 'not found')"
        |echo "[startup] Backend port: ${'$'}BACKEND_PORT"
        |echo "[startup] GAMA WS port: ${'$'}GAMA_WS_PORT"
        |
        |mkdir -p /tmp /data /workspace /opt/gama/logs 2>/dev/null
        |[ -f /etc/profile.d/gama-env.sh ] && source /etc/profile.d/gama-env.sh
        |
        |# Port check function (uses Python since nc may not be available)
        |check_port() {
        |  python3 -c "import socket; s=socket.socket(); s.settimeout(2); s.connect(('$1', $2)); s.close()" 2>/dev/null
        |}
        |
        |# ─── Run rootfs startup.sh in background (handles GAMA + bridge) ─
        |if [ -f /startup.sh ]; then
        |  echo "[startup] Running rootfs startup.sh in background..."
        |  bash /startup.sh &
        |  STARTUP_PID=${'$'}!
        |  echo "[startup] Rootfs startup PID: ${'$'}STARTUP_PID"
        |fi
        |
        |# ─── Wait for backend on port ${'$'}BACKEND_PORT ────────────────────
        |echo "[startup] Waiting for backend on port ${'$'}BACKEND_PORT..."
        |for i in $(seq 1 120); do
        |  check_port 127.0.0.1 ${'$'}BACKEND_PORT && echo "[startup] Backend ready! (attempt ${'$'}i)" && break
        |  [ ${'$'}i -eq 120 ] && echo "[startup] Backend not ready after 120s"
        |  sleep 1
        |done
        |
        |# ─── Fallback: start minimal health endpoint ───────────────────
        |if ! check_port 127.0.0.1 ${'$'}BACKEND_PORT; then
        |  echo "[startup] Starting fallback health endpoint on port ${'$'}BACKEND_PORT..."
        |  python3 -c "
        |import http.server, json, sys, signal
        |signal.signal(signal.SIGTERM, lambda *a: sys.exit(0))
        |signal.signal(signal.SIGINT, lambda *a: sys.exit(0))
        |class H(http.server.BaseHTTPRequestHandler):
        |  def do_GET(self):
        |    self.send_response(200)
        |    self.send_header('Content-Type', 'application/json')
        |    self.send_header('Access-Control-Allow-Origin', '*')
        |    self.end_headers()
        |    self.wfile.write(json.dumps({'status': 'ok', 'mode': 'fallback'}).encode())
        |  def log_message(self, *a): pass
        |http.server.HTTPServer(('0.0.0.0', ${'$'}BACKEND_PORT), H).serve_forever()
        |" &
        |  echo "[startup] Fallback health PID: ${'$'}!"
        |fi
        |
        |# Keep alive
        |while true; do sleep 10; done
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

    @Suppress("DEPRECATION")
    private fun Process.pid(): Int {
        return try {
            val pidField = javaClass.getDeclaredField("pid")
            pidField.isAccessible = true
            pidField.getInt(this)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot get PID", e)
            -1
        }
    }
}
