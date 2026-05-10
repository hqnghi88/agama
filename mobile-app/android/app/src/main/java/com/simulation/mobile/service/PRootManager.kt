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

    fun startPRoot(rootfsDir: File, workspaceDir: File, port: Int, onStarted: (pid: Int) -> Unit) {
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
                // PROOT_NO_SECCOMP disabled: seccomp intercepts only syscalls needing path translation.
                // getcwd passes through seccomp correctly (ptrace mode breaks getcwd for Java).
                // mkdir failures are handled in gama-launcher.sh with || true.
                // "PROOT_NO_SECCOMP" to "1",
                "LD_LIBRARY_PATH" to libHelperDir.absolutePath,
                "BACKEND_PORT" to port.toString(),
                "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/bin",
                "JAVA_HOME" to "/usr/lib/jvm/java-25",
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

    private fun generateStartupScript(): String = """
        |#!/bin/bash
        |
        |# GAMA Mobile startup - runs inside PRoot Linux container
        |
        |unset LD_PRELOAD
        |# PROOT_NO_SECCOMP is intentionally unset here (set by PRootManager env, not inside guest).
        |export JAVA_HOME=/usr/lib/jvm/java-25
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
        |# Port check function (uses explicit /usr/bin/python3, not PATH lookup)
        |PYTHON=/usr/bin/python3
        |check_port() {
        |  ${'$'}{PYTHON} -c "import socket; s=socket.socket(); s.settimeout(2); s.connect(('$1', $2)); s.close()" 2>/dev/null
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
        |# ─── Fallback: warn but don't start anything on the port ──────
        |# The Kotlin FallbackHealthServer (outside PRoot) already returns
        |# {"status":"ok","mode":"fallback"} for health checks when the
        |# bridge isn't accessible. Starting another server on the same
        |# port would conflict with the real bridge when it eventually starts.
        |if ! check_port 127.0.0.1 ${'$'}BACKEND_PORT; then
        |  echo "[startup] WARNING: Bridge not ready on port ${'$'}BACKEND_PORT after 120s"
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
