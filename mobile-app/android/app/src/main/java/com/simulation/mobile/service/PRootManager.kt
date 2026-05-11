package com.simulation.mobile.service

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PRootManager(private val context: Context) {

    companion object {
        const val TAG = "PRootManager"
        const val ROOTFS_ARCHIVE = "rootfs.tar.gz"
        const val PROOT_BINARY = "libproot.so"
    }

    private var prootProcess: Process? = null
    private val isRunning = AtomicBoolean(false)

    fun setupRootfs(rootfsDir: File, onProgress: ((String) -> Unit)? = null): Boolean {
        return try {
            if (rootfsDir.exists() && File(rootfsDir, "usr").exists()) {
                Log.i(TAG, "Rootfs already extracted at $rootfsDir")
            } else {
                Log.i(TAG, "Setting up rootfs at $rootfsDir")
                rootfsDir.mkdirs()
                onProgress?.invoke("copying rootfs archive from APK")

                val archiveFile = extractRawResource(ROOTFS_ARCHIVE, rootfsDir.parentFile!!)
                    ?: return false

                Log.i(TAG, "Extracting rootfs archive: ${archiveFile.absolutePath}")
                onProgress?.invoke("extracting rootfs (this may take several minutes)")

                Log.i(TAG, "Archive size: ${archiveFile.length()} bytes")
                val pb = ProcessBuilder(
                    "tar", "xzf", archiveFile.absolutePath,
                    "-C", rootfsDir.absolutePath
                ).redirectErrorStream(true)
                val extractProcess = pb.start()
                // Read output in background thread to prevent pipe buffer deadlock
                val outputHolder = java.util.concurrent.atomic.AtomicReference("")
                val readerThread = Thread {
                    try {
                        outputHolder.set(extractProcess.inputStream.bufferedReader().readText())
                    } catch (_: Exception) {}
                }.apply { name = "tar-reader"; start() }
                val finished = extractProcess.waitFor(5, TimeUnit.MINUTES)

                if (!finished) {
                    Log.e(TAG, "Rootfs extraction timed out after 5 min — destroying")
                    extractProcess.destroyForcibly()
                    rootfsDir.deleteRecursively()
                    readerThread.join(1000)
                    return false
                }

                val exitCode = extractProcess.exitValue()
                val output = outputHolder.get()
                Log.i(TAG, "Tar exit code: $exitCode")

                if (exitCode != 0) {
                    Log.e(TAG, "Rootfs extraction failed with code $exitCode")
                    Log.e(TAG, "Tar output: ${output.take(500)}")
                    Log.i(TAG, "Archive file exists: ${archiveFile.exists()}, size: ${archiveFile.length()}")
                    Log.i(TAG, "Rootfs dir exists: ${rootfsDir.exists()}, writable: ${rootfsDir.canWrite()}")
                    // Clean up so next launch retries from scratch
                    rootfsDir.deleteRecursively()
                    return false
                }
            }

            onProgress?.invoke("fixing filesystem permissions")
            fixRootfsPermissions(rootfsDir, onProgress)

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

            onProgress?.invoke("writing startup script")
            writeFallbackStartup(rootfsDir)

            onProgress?.invoke("updating bridge server")
            overwriteBridgeServer(rootfsDir)

            Log.i(TAG, "Rootfs setup complete")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Rootfs setup failed", e)
            false
        }
    }

    private fun fixRootfsPermissions(rootfsDir: File, onProgress: ((String) -> Unit)? = null) {
        try {
            Log.i(TAG, "Fixing rootfs permissions...")
            val runtime = Runtime.getRuntime()

            onProgress?.invoke("fixing permissions (chmod -R)")
            execWithTimeout(runtime, arrayOf(
                "chmod", "-R", "u+rwX,go+rX",
                rootfsDir.absolutePath
            ), "chmod -R", 60)

            onProgress?.invoke("fixing permissions (shell scripts)")
            makeExecutableBySuffix(rootfsDir, ".sh")

            onProgress?.invoke("fixing permissions (python scripts)")
            makeExecutableBySuffix(rootfsDir, ".py")

            onProgress?.invoke("fixing permissions (removing AppleDouble files)")
            removeAppleDoubleFiles(rootfsDir)

            onProgress?.invoke("fixing permissions (/tmp sticky bit)")
            execWithTimeout(runtime, arrayOf(
                "chmod", "1777",
                File(rootfsDir, "tmp").absolutePath
            ), "chmod 1777 /tmp", 10)

            onProgress?.invoke("fixing permissions (/root private)")
            execWithTimeout(runtime, arrayOf(
                "chmod", "700",
                File(rootfsDir, "root").absolutePath
            ), "chmod 700 /root", 10)

            Log.i(TAG, "Rootfs permissions fixed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fix rootfs permissions", e)
        }
    }

    private fun execWithTimeout(runtime: Runtime, cmd: Array<String>, label: String, timeoutSec: Int) {
        val proc = runtime.exec(cmd)
        val finished = proc.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
        if (!finished) {
            Log.w(TAG, "$label timed out after ${timeoutSec}s — destroying")
            proc.destroyForcibly()
        } else {
            val code = proc.exitValue()
            if (code != 0) {
                Log.w(TAG, "$label exited with code $code")
            }
        }
    }

    private fun makeExecutableBySuffix(rootfsDir: File, suffix: String) {
        try {
            var count = 0
            rootfsDir.walkTopDown().forEach { file ->
                if (file.isFile && file.name.endsWith(suffix)) {
                    file.setExecutable(true, false)
                    count++
                }
            }
            Log.i(TAG, "Made $count $suffix files executable")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to make $suffix files executable", e)
        }
    }

    private fun removeAppleDoubleFiles(rootfsDir: File) {
        try {
            var count = 0
            rootfsDir.walkTopDown().forEach { file ->
                if (file.isFile && file.name.startsWith("._")) {
                    file.delete()
                    count++
                }
            }
            Log.i(TAG, "Removed $count AppleDouble files")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to remove AppleDouble files", e)
        }
    }

    fun startPRoot(rootfsDir: File, workspaceDir: File, onStarted: (pid: Int) -> Unit) {
        if (isRunning.getAndSet(true)) {
            Log.d(TAG, "PRoot already running")
            return
        }

        try {
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val prootBin = File(nativeLibDir, "libproot.so").absolutePath
            val loaderBin = File(nativeLibDir, "libproot_loader.so").absolutePath
            val loader32Bin = File(nativeLibDir, "libproot_loader32.so").absolutePath
            if (!File(prootBin).exists()) {
                Log.e(TAG, "PRoot binary not found at $prootBin")
                isRunning.set(false)
                return
            }

            // The Termux proot binary needs libtalloc.so.2 at runtime.
            // We bundle it as libtalloc.so in nativeLibDir (Android only extracts *.so).
            // Create a symlink libtalloc.so.2 -> libtalloc.so in our cache dir
            // and add that dir to LD_LIBRARY_PATH so the linker finds it.
            val libHelperDir = File(context.cacheDir, "libhelper").also { it.mkdirs() }
            val tallocSrc = File(nativeLibDir, "libtalloc.so")
            val tallocLink = File(libHelperDir, "libtalloc.so.2")
            if (tallocSrc.exists() && !tallocLink.exists()) {
                try {
                    Runtime.getRuntime().exec(arrayOf("ln", "-sf", tallocSrc.absolutePath, tallocLink.absolutePath)).waitFor()
                    Log.i(TAG, "Created libtalloc symlink at ${tallocLink.absolutePath}")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to create symlink, trying copy", e)
                    tallocSrc.copyTo(tallocLink, overwrite = true)
                }
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
                "PROOT_LOADER" to loaderBin,
                "PROOT_LOADER_32" to loader32Bin,
                "LD_LIBRARY_PATH" to libHelperDir.absolutePath,
                "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/bin",
                "JAVA_HOME" to "/usr/lib/jvm/java-25",
                "TERM" to "xterm"
            )

            val cmd = arrayListOf(
                prootBin,
                "-v", "0",
                "-r", rootfsDir.absolutePath,
                "-b", "/dev",
                "-b", "/proc",
                "-b", "/sys",
                "-b", "${workspaceDir.absolutePath}:/workspace",
                "-b", "/sdcard:/sdcard",
                "-b", "/system:/system",
                "-b", "/apex:/apex",
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

    private fun overwriteBridgeServer(rootfsDir: File) {
        try {
            val bridgeFile = File(rootfsDir, "opt/gama/bridge-server.py")
            val resId = context.resources.getIdentifier("bridge_server_py", "raw", context.packageName)
            if (resId == 0) {
                Log.w(TAG, "bridge_server_py resource not found, keeping existing bridge-server.py")
                return
            }
            context.resources.openRawResource(resId).use { input ->
                bridgeFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            bridgeFile.setExecutable(true)
            Log.i(TAG, "Bridge server updated from bundled resource")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to overwrite bridge-server.py", e)
        }
    }

    private fun generateStartupScript(): String = """
        |#!/bin/bash
        |
        |# GAMA Mobile VNC startup - runs inside PRoot Linux container
        |# Tries Xvfb + x11vnc first, falls back to Xtightvnc (combined X+VNC)
        |
        |unset LD_PRELOAD
        |export JAVA_HOME=/usr/lib/jvm/java-25
        |export PATH=${'$'}JAVA_HOME/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
        |export HOME=/data
        |export USER=shell
        |export TMPDIR=/tmp
        |export VNC_PORT=5901
        |export DISPLAY=:1
        |
        |echo "[startup] GAMA Mobile VNC starting"
        |echo "[startup] Java: $(java -version 2>&1 | head -1 2>/dev/null || echo 'not found')"
        |
        |mkdir -p /tmp /data /workspace /opt/gama/logs /tmp/.X11-unix /dev/shm 2>/dev/null
        |chmod 1777 /tmp /tmp/.X11-unix /dev/shm 2>/dev/null || true
        |chmod 777 /workspace /data /opt/gama/logs 2>/dev/null || true
        |
        |# ─── Start D-Bus (required by X11 and GTK) ─
        |mkdir -p /run/dbus /var/run/dbus 2>/dev/null
        |dbus-daemon --system 2>/dev/null || dbus-daemon --system --fork 2>/dev/null || true
        |echo "[startup] D-Bus status: $(pgrep dbus-daemon >/dev/null 2>&1 && echo 'OK' || echo 'FAILED')"
        |
        |# ─── GTK will use X11 backend (not Wayland) ─
        |export GDK_BACKEND=x11
        |
        |# ─── LD_PRELOAD shim to make link() work (Android kernel blocks hard links) ─
        |export LD_PRELOAD=/opt/gama/override_link.so
        |
        |# ─── Remove stale X lock files ─
        |rm -f /tmp/.X1-lock /tmp/.X11-unix/X1 2>/dev/null || true
        |chmod 1777 /tmp/.X11-unix 2>/dev/null || true
        |
        |# ─── Determine which X+VNC server to use ─
        |USE_XVNC=false
        |
        |# ─── Try Xvfb (virtual framebuffer X server) ─
        |echo "[startup] Starting Xvfb on display ${'$'}DISPLAY..."
        |Xvfb ${'$'}DISPLAY -screen 0 1280x720x16 -pixdepths 8 16 24 32 -noreset +extension GLX +extension RENDER &>/opt/gama/logs/xvfb.log 2>&1 &
        |XVFB_PID=${'$'}!
        |sleep 3
        |
        |X_SERVER_RUNNING=false
        |if kill -0 ${'$'}XVFB_PID 2>/dev/null; then
        |  echo "[startup] Xvfb running (PID ${'$'}XVFB_PID)"
        |  X_SERVER_RUNNING=true
        |  # ─── Start x11vnc (VNC server attached to Xvfb) ─
        |  echo "[startup] Starting x11vnc on port ${'$'}VNC_PORT..."
        |  x11vnc -display ${'$'}DISPLAY -forever -nopw -quiet -rfbport ${'$'}VNC_PORT &>/opt/gama/logs/x11vnc.log 2>&1 &
        |fi
        |
        |if [ "${'$'}X_SERVER_RUNNING" = false ]; then
        |  echo "[startup] Xvfb failed, trying Xtightvnc..."
        |  Xtightvnc ${'$'}DISPLAY -geometry 1280x720 -depth 16 -rfbport ${'$'}VNC_PORT \
        |    -desktop GAMA -localhost &>/opt/gama/logs/xvnc.log 2>&1 &
        |  XVNC_PID=${'$'}!
        |  sleep 3
        |  if kill -0 ${'$'}XVNC_PID 2>/dev/null; then
        |    echo "[startup] Xtightvnc running (PID ${'$'}XVNC_PID)"
        |    X_SERVER_RUNNING=true
        |  else
        |    echo "[startup] Xtightvnc also failed (check /opt/gama/logs/xvnc.log)"
        |  fi
        |fi
        |
        |# ─── Wait for VNC port (up to 30s) ─
        |echo "[startup] Waiting for VNC on port ${'$'}VNC_PORT..."
        |for i in $(seq 1 30); do
        |  python3 -c "import socket; s=socket.socket(); s.settimeout(2); s.connect(('127.0.0.1', ${'$'}VNC_PORT)); s.close()" 2>/dev/null \
        |    && echo "[startup] VNC ready! (attempt ${'$'}i)" && break
        |  [ ${'$'}i -eq 30 ] && echo "[startup] VNC not ready after 30s"
        |  sleep 1
        |done
        |
        |# ─── Start fluxbox (window manager) ─
        |echo "[startup] Starting fluxbox..."
        |fluxbox &>/opt/gama/logs/fluxbox.log 2>&1 &
        |
        |# ─── Start GAMA GUI ─
        |GAMA_HOME=/opt/gama
        |if [ -f "${'$'}GAMA_HOME/Gama" ]; then
        |  echo "[startup] Starting GAMA GUI..."
        |  cd "${'$'}GAMA_HOME"
        |  DISPLAY=${'$'}DISPLAY ./Gama -data /workspace &>/opt/gama/logs/gama.log 2>&1 &
        |  GAMA_PID=${'$'}!
        |  echo "[startup] GAMA PID: ${'$'}GAMA_PID"
        |elif [ -f "${'$'}GAMA_HOME/headless/Gama" ]; then
        |  echo "[startup] Starting GAMA headless..."
        |  cd "${'$'}GAMA_HOME/headless"
        |  DISPLAY=${'$'}DISPLAY ./Gama -data /workspace &>/opt/gama/logs/gama.log 2>&1 &
        |  GAMA_PID=${'$'}!
        |  echo "[startup] GAMA PID: ${'$'}GAMA_PID"
        |else
        |  echo "[startup] GAMA binary not found"
        |fi
        |
        |# ─── Monitor and keep alive ─
        |while true; do
        |  sleep 5
        |  if [ -n "${'$'}GAMA_PID" ] && ! kill -0 ${'$'}GAMA_PID 2>/dev/null; then
        |    echo "[startup] GAMA died, restarting..."
        |    cd "${'$'}GAMA_HOME"
        |    DISPLAY=${'$'}DISPLAY ./Gama -data /workspace &>/opt/gama/logs/gama.log 2>&1 &
        |    GAMA_PID=${'$'}!
        |    echo "[startup] GAMA restarted with PID ${'$'}GAMA_PID"
        |  fi
        |done
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
