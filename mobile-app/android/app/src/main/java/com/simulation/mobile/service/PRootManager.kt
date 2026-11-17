package com.simulation.mobile.service

import android.content.Context
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.io.BufferedReader
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class PRootManager(private val context: Context) {

    companion object {
        const val TAG = "PRootManager"
        const val ROOTFS_ARCHIVE = "rootfs.tar.gz"
        const val PROOT_BINARY = "libproot.so"
        const val ROOTFS_URL = "https://github.com/hqnghi88/agama/releases/download/rootfs/rootfs_tar_gz"

        /**
         * Port for GAMA's built-in GUI WebSocket server (`GamaGuiWebSocketServer`).
         *
         * GAMA defaults to 1000, but Android sets `net.ipv4.ip_unprivileged_port_start = 1024`,
         * so an app UID cannot bind it: the server dies with
         * `java.net.BindException: Permission denied` and external clients (connector.html,
         * another app, a browser) never connect. Keep this above 1024.
         */
        const val GAMA_SERVER_PORT = 10000

        private const val GAMA_GUI_SERVER_JAR_PREFIX = "gama.ui.application_"
        private const val GAMA_GUI_SERVER_CLASS_ENTRY =
            "gama/ui/application/server/GamaGuiWebSocketServer.class"

        /**
         * `createGuiServer()` reads `CORE_SERVER_PORT` and validates it. The bytes below are the
         * compiled sequence `CORE_SERVER_PORT.getValue()` -> `(Integer)` -> `intValue()` ->
         * `istore_0`. Replacing it with `sipush 10000; istore_0` pins the GUI server port.
         */
        private val CREATE_GUI_SERVER_PATTERN = byteArrayOf(
            0xB2.toByte(), 0x00, 0x62,              // getstatic CORE_SERVER_PORT
            0xB6.toByte(), 0x00, 0x4D,              // invokevirtual Pref.getValue
            0xC0.toByte(), 0x00, 0x8A.toByte(),     // checkcast Integer
            0xB6.toByte(), 0x00, 0x8C.toByte(),     // invokevirtual Integer.intValue
            0x3B                                    // istore_0
        )

        /**
         * `startGuiServer()` subscribes to `CORE_SERVER_PORT` changes and recreates the server on
         * the new port. The Preferences view can therefore move it to an ephemeral port. These
         * bytes are the listener registration (`getstatic` + `invokedynamic` + `onChange` + `pop`);
         * replacing them with NOPs removes the subscription.
         */
        private val SERVER_PORT_LISTENER_PATTERN = byteArrayOf(
            0xB2.toByte(), 0x00, 0x62,              // getstatic CORE_SERVER_PORT
            0xBA.toByte(), 0x00, 0x65, 0x00, 0x00,  // invokedynamic afterValueChange
            0xB6.toByte(), 0x00, 0x5E,              // invokevirtual Pref.onChange
            0x57                                    // pop
        )
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

                // Try to get rootfs: download from GitHub or extract from APK
                val archiveFile = downloadOrExtractRootfs(rootfsDir.parentFile!!, onProgress)
                    ?: return false

                onProgress?.invoke("extracting rootfs (this may take several minutes)")
                Log.i(TAG, "Extracting rootfs archive: ${archiveFile.absolutePath}")
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
                val finished = waitForExtraction(extractProcess, rootfsDir, onProgress)

                if (!finished) {
                    Log.e(TAG, "Rootfs extraction timed out after 20 min — destroying")
                    extractProcess.destroyForcibly()
                    rootfsDir.deleteRecursively()
                    readerThread.join(1000)
                    return false
                }

                val exitCode = extractProcess.exitValue()
                val output = outputHolder.get()
                Log.i(TAG, "Tar exit code: $exitCode")

                if (exitCode >= 2) {
                    Log.e(TAG, "Rootfs extraction fatal error (exit code $exitCode)")
                    Log.e(TAG, "Tar output: ${output.take(500)}")
                    Log.i(TAG, "Archive file exists: ${archiveFile.exists()}, size: ${archiveFile.length()}")
                    Log.i(TAG, "Rootfs dir exists: ${rootfsDir.exists()}, writable: ${rootfsDir.canWrite()}")
                    // Clean up so next launch retries from scratch
                    rootfsDir.deleteRecursively()
                    return false
                }
                if (exitCode == 1) {
                    Log.w(TAG, "Tar had warnings (exit code 1, likely hard links on Android)")
                    Log.w(TAG, "Tar output: ${output.take(300)}")
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

            onProgress?.invoke("configuring GAMA server")
            configureGamaPreferences(rootfsDir)

            onProgress?.invoke("writing startup script")
            writeFallbackStartup(rootfsDir)

            onProgress?.invoke("updating bridge server")
            overwriteBridgeServer(rootfsDir)

            onProgress?.invoke("installing JOGL natives")
            installJoglNatives(rootfsDir)

            onProgress?.invoke("pinning GAMA server port")
            patchGamaGuiServerClass(rootfsDir)

            onProgress?.invoke("updating connector defaults")
            patchConnectorDefaults(rootfsDir)

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

    private fun waitForExtraction(
        process: Process,
        rootfsDir: File,
        onProgress: ((String) -> Unit)? = null
    ): Boolean {
        val deadline = System.currentTimeMillis() + 20 * 60 * 1000L
        var lastUpdate = 0L
        while (process.isAlive) {
            if (System.currentTimeMillis() >= deadline) {
                return false
            }
            val now = System.currentTimeMillis()
            if (now - lastUpdate >= 15_000) {
                lastUpdate = now
                val mb = rootfsDir.walkBottomUp()
                    .mapNotNull { it.length().takeIf { l -> l > 0 } }
                    .sum() / (1024 * 1024)
                val msg = "extracting rootfs (this may take several minutes) … $mb MB"
                Log.i(TAG, msg)
                onProgress?.invoke(msg)
            }
            Thread.sleep(5_000)
        }
        return true
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
                onStarted(-1)
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
                "JAVA_HOME" to "/usr/lib/jvm/java-25-openjdk-arm64",
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
                "-b", "/vendor:/vendor",
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
            try { onStarted(-1) } catch (_: Exception) {}
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
     * Patch GAMA's global preference store before launch.
     *
     * The store is `$HOME/.java/.userPrefs/gama/prefs.xml` because the GAMA launcher runs with
     * `-Duse_global_preference_store=true` (`Gama.ini`) and `HOME=/data` inside PRoot.
     *
     * Two problems are fixed here:
     *  - `pref_server_port` defaults to 1000, which Android forbids for unprivileged app UIDs
     *    (BindException: Permission denied). Move it above 1024.
     *  - The workspace sanity dialogs (stale `.lock` / `.snap`, outdated model library) block
     *    `Application.start()` before `GamaGuiWebSocketServer` is created. Since the mobile app
     *    kills GAMA without a graceful shutdown, these dialogs appear on every cold boot and
     *    there is no one to click them unless the VNC view is open. Disable the prompts so the
     *    workspace is repaired automatically.
     */
    private fun configureGamaPreferences(rootfsDir: File) {
        try {
            val prefsFile = File(rootfsDir, "data/.java/.userPrefs/gama/prefs.xml")
            prefsFile.parentFile?.mkdirs()

            val emptyPrefs = buildString {
                appendLine("""<?xml version="1.0" encoding="UTF-8" standalone="no"?>""")
                appendLine("""<!DOCTYPE map SYSTEM "http://java.sun.com/dtd/preferences.dtd">""")
                appendLine("""<map MAP_XML_VERSION="1.0">""")
                appendLine("</map>")
            }
            var xml = if (prefsFile.exists()) prefsFile.readText() else emptyPrefs

            val desired = linkedMapOf(
                "pref_enable_server" to "true",
                "pref_server_port" to GAMA_SERVER_PORT.toString(),
                "pref_ask_rebuild" to "false",
                "pref_ask_outdated" to "false"
            )
            for ((key, value) in desired) {
                val entry = """<entry key="$key" value="$value"/>"""
                val pattern = Regex("""<entry\s+key="${Regex.escape(key)}"\s+value="[^"]*"\s*/>""")
                xml = if (pattern.containsMatchIn(xml)) {
                    pattern.replace(xml, entry)
                } else {
                    xml.replace("</map>", "  $entry\n</map>")
                }
            }

            prefsFile.writeText(xml)
            Log.i(TAG, "GAMA server prefs written: port=$GAMA_SERVER_PORT, enabled=true, async workspace repair")

            clearStaleWorkspaceMarkers(rootfsDir, xml)
        } catch (e: Exception) {
            Log.w(TAG, "Could not configure GAMA preferences", e)
        }
    }

    /**
     * Remove crash markers that make GAMA show a blocking confirmation dialog before the
     * workbench (and therefore the WebSocket server) starts.
     */
    private fun clearStaleWorkspaceMarkers(rootfsDir: File, prefsXml: String) {
        try {
            val workspacePath = Regex("""<entry\s+key="pref_workspace_path"\s+value="([^"]*)"""")
                .find(prefsXml)?.groupValues?.get(1)
                ?.takeIf { it.startsWith("/") }
                ?: "/data/Gama_Workspace"
            val workspace = File(rootfsDir, workspacePath.removePrefix("/"))

            File(workspace, ".metadata/.lock").delete()

            val resources = File(workspace, ".metadata/.plugins/org.eclipse.core.resources")
            resources.listFiles()?.filter { it.name.contains("snap") }?.forEach { it.delete() }

            Log.i(TAG, "Cleared stale workspace markers in ${workspace.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "Could not clear stale workspace markers", e)
        }
    }

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
            // Prefer a NEW-flow rootfs /startup.sh (synced from proot-setup/startup.sh by
            // build-rootfs.sh when the rootfs is rebuilt). The prebuilt archive in git bakes
            // the OLD tightvnc/fluxbox flow, so only trust it if it has the x11vnc -noshm
            // marker; otherwise fall back to the inline generated script below.
            val baked = File(rootfsDir, "startup.sh")
            val vncW = 1280
            val vncH = 800
            if (baked.exists() && baked.readText().contains("x11vnc -noshm")) {
                var bakedText = baked.readText()
                    .replace(Regex("""VNC_WIDTH=\d+"""), "VNC_WIDTH=$vncW")
                    .replace(Regex("""VNC_HEIGHT=\d+"""), "VNC_HEIGHT=$vncH")
                    .replace("960x540", "${vncW}x${vncH}")
                // Older baked rootfs archives shipped a launch line without the Vector API
                // module. GAMA's engine uses jdk.incubator.vector, which exists in the guest's
                // JDK 25 but is NOT in the default module graph — inject it at write time so
                // GAMA doesn't crash with NoClassDefFoundError while loading gama.core.
                if (!bakedText.contains("--add-modules=jdk.incubator.vector")) {
                    bakedText = bakedText.replace(
                        "-Dorg.eclipse.core.resources.disable.workspace.locking=true \\\n",
                        "-Dorg.eclipse.core.resources.disable.workspace.locking=true \\\n" +
                            "      --add-modules=jdk.incubator.vector \\\n"
                    )
                }
                script.writeText(bakedText)
                Log.i(TAG, "Startup script copied from rootfs /startup.sh (${vncW}x${vncH})")
            } else {
                script.writeText(generateStartupScript(vncW, vncH))
            }
            script.setExecutable(true)
            configureQuestDisplayAndGama(rootfsDir, vncW, vncH)

            Log.i(TAG, "Startup script written to ${script.absolutePath}")

            val soResId = context.resources.getIdentifier("override_link_so", "raw", context.packageName)
            if (soResId != 0) {
                val soFile = File(optDir, "override_link.so")
                context.resources.openRawResource(soResId).use { input ->
                    soFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                soFile.setExecutable(true)
                Log.i(TAG, "LD_PRELOAD shim written to ${soFile.absolutePath}")
            } else {
                Log.w(TAG, "override_link_so resource not found — Xvfb may fail")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write startup script components", e)
        }
    }

    private fun configureQuestDisplayAndGama(rootfsDir: File, vncW: Int, vncH: Int) {
        try {
            val obConfigDir = File(rootfsDir, "data/.config/openbox")
            obConfigDir.mkdirs()
            val targetRc = File(obConfigDir, "rc.xml")
            val sourceRc = if (targetRc.exists()) targetRc else File(rootfsDir, "etc/xdg/openbox/rc.xml")
            if (sourceRc.exists()) {
                var rcXml = sourceRc.readText()
                if (!rcXml.contains("""<!-- gama-quest-rule -->""") && rcXml.contains("</applications>")) {
                    val rule = """
  <!-- gama-quest-rule -->
  <application type="normal">
    <decor>no</decor>
    <position force="yes">
      <x>0</x>
      <y>0</y>
    </position>
    <maximized>true</maximized>
  </application>
</applications>"""
                    rcXml = rcXml.replace("</applications>", rule)
                    targetRc.writeText(rcXml)
                }
            }

            val workbenchXmi = File(rootfsDir, "data/Gama_Workspace/.metadata/.plugins/org.eclipse.e4.workbench/workbench.xmi")
            if (workbenchXmi.exists()) {
                val orig = workbenchXmi.readText()
                var fixed = orig.replace(Regex(""" y="-\d+""""), """ y="0"""")
                fixed = fixed.replace(Regex(""" x="-\d+""""), """ x="0"""")
                fixed = fixed.replace(
                    Regex("""(<children[^>]*xsi:type="basic:TrimmedWindow"[^>]*?)\swidth="\d+"\sheight="\d+""""),
                    """$1 width="$vncW" height="$vncH""""
                )
                if (fixed != orig) {
                    workbenchXmi.writeText(fixed)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to configure Quest display: ${e.message}")
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

    /**
     * Install the JOGL 2.6.0 linux-aarch64 native libraries shipped as a raw
     * resource, overwriting whatever the rootfs archive contains.
     *
     * The GAMA product ships gluegen/jogl Java jars 2.6.0 (Aug 2025) but no
     * aarch64 natives; older rootfs archives contain 2021-era natives whose
     * JNI ABI does not match (UnsatisfiedLinkError on dladdr_fname, then a
     * SIGSEGV inside glXChooseFBConfig). Overwriting them on every launch
     * keeps the guest in sync without re-downloading the whole rootfs.
     */
    private fun installJoglNatives(rootfsDir: File) {
        try {
            val dst = File(rootfsDir, "opt/gama/natives/linux-aarch64")
            // Read the JOGL 2.6.0 aarch64 native libraries from assets.
            // Bundling via raw resources (res/raw) can be stripped by the Android
            // build shrinker; assets are always included in the final APK.
            val input = context.assets?.open("jogl_natives_aarch64") ?: return
            dst.mkdirs()
            var count = 0
            // The asset is a zip archive containing the 8 .so files at its root.
            java.util.zip.ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val name = entry.name
                    if (!name.endsWith(".so")) continue
                    val out = File(dst, File(name).name)
                    zip.copyTo(out.outputStream())
                    out.setReadable(true, false)
                    out.setExecutable(true, false)
                    count++
                }
            }
            Log.i(TAG, "Installed $count JOGL native libraries from assets into ${dst.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install JOGL natives from assets", e)
        }
    }

    /**
     * Keep GAMA's own GUI WebSocket server on [GAMA_SERVER_PORT].
     *
     * The shipped GAMA build re-reads `pref_server_port` every time the server is (re)created and
     * also restarts it whenever that preference changes. Opening the Preferences view was observed
     * to move the listener from 10000 to an ephemeral port (and to retry the unprivileged default
     * 1000), which breaks external clients such as `connector.html`.
     *
     * Patching the compiled class fixes this at the source: `createGuiServer()` always uses 10000
     * and the port-change listener is removed, so the server cannot wander off again. The patch is
     * applied to the rootfs copy on every launch and is idempotent.
     */
    private fun patchGamaGuiServerClass(rootfsDir: File) {
        try {
            val plugins = File(rootfsDir, "opt/gama/plugins")
            val jars = plugins.listFiles { f ->
                f.isFile && f.name.startsWith(GAMA_GUI_SERVER_JAR_PREFIX) && f.name.endsWith(".jar")
            } ?: emptyArray()
            if (jars.isEmpty()) {
                Log.w(TAG, "GAMA GUI server jar not found under ${plugins.absolutePath}")
                return
            }
            for (jar in jars) patchGamaGuiServerJar(jar)
        } catch (e: Exception) {
            Log.w(TAG, "Could not patch GAMA GUI server port", e)
        }
    }

    private fun patchGamaGuiServerJar(jar: File) {
        val tmp = File(jar.parentFile, jar.name + ".tmp")
        var changed = false
        try {
            ZipFile(jar).use { zip ->
                BufferedOutputStream(FileOutputStream(tmp)).use { bos ->
                    ZipOutputStream(bos).use { out ->
                        val entries = zip.entries()
                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()
                            var data = zip.getInputStream(entry).use { it.readBytes() }
                            if (entry.name == GAMA_GUI_SERVER_CLASS_ENTRY) {
                                val patched = patchGuiServerBytes(data)
                                if (patched !== data) {
                                    data = patched
                                    changed = true
                                }
                            }
                            out.putNextEntry(ZipEntry(entry.name))
                            out.write(data)
                            out.closeEntry()
                        }
                    }
                }
            }

            if (changed) {
                val backup = File(jar.parentFile, jar.name + ".orig")
                if (!backup.exists()) jar.copyTo(backup, overwrite = false)
                try {
                    Files.move(
                        tmp.toPath(), jar.toPath(), StandardCopyOption.REPLACE_EXISTING
                    )
                } catch (_: Exception) {
                    tmp.copyTo(jar, overwrite = true)
                    tmp.delete()
                }
                Log.i(TAG, "Pinned GAMA GUI server to port $GAMA_SERVER_PORT (${jar.name})")
            } else {
                tmp.delete()
                Log.i(TAG, "GAMA GUI server already pinned or pattern not found (${jar.name})")
            }
        } catch (e: Exception) {
            tmp.delete()
            Log.w(TAG, "Failed to patch ${jar.name}", e)
        }
    }

    private fun patchGuiServerBytes(bytes: ByteArray): ByteArray {
        val createIndex = indexOfBytes(bytes, CREATE_GUI_SERVER_PATTERN)
        val listenerIndex = indexOfBytes(bytes, SERVER_PORT_LISTENER_PATTERN)
        if (createIndex < 0 && listenerIndex < 0) return bytes

        val patched = bytes.copyOf()
        if (createIndex >= 0) {
            // sipush 10000; istore_0; pad with NOPs to keep every Code offset stable.
            patched[createIndex] = 0x11
            patched[createIndex + 1] = 0x27
            patched[createIndex + 2] = 0x10
            patched[createIndex + 3] = 0x3B
            for (i in 4 until CREATE_GUI_SERVER_PATTERN.size) {
                patched[createIndex + i] = 0
            }
        }
        if (listenerIndex >= 0) {
            for (i in SERVER_PORT_LISTENER_PATTERN.indices) {
                patched[listenerIndex + i] = 0
            }
        }
        return patched
    }

    private fun indexOfBytes(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    /**
     * The built-in GAMA connector page defaults to `localhost:1000`, which cannot work on
     * Android (port 1000 is unprivileged) and may resolve to IPv6 `::1`. Point the bundled
     * connector at the pinned GUI server instead so it connects out of the box.
     */
    private fun patchConnectorDefaults(rootfsDir: File) {
        try {
            val osgi = File(rootfsDir, "opt/gama/configuration/org.eclipse.osgi")
            if (!osgi.exists()) return
            var patched = 0
            osgi.walkTopDown()
                .filter { it.isFile && it.name == "connector.js" }
                .forEach { file ->
                    val text = file.readText()
                    val updated = text
                        .replace(
                            "const DEFAULT_GAMA_WS_PORT = \"1000\"",
                            "const DEFAULT_GAMA_WS_PORT = \"$GAMA_SERVER_PORT\""
                        )
                        .replace(
                            "const DEFAULT_GAMA_IP_ADDRESS = \"localhost\"",
                            "const DEFAULT_GAMA_IP_ADDRESS = \"127.0.0.1\""
                        )
                    if (updated != text) {
                        file.writeText(updated)
                        patched++
                    }
                }
            if (patched > 0) {
                Log.i(TAG, "Patched $patched connector.js default(s) to 127.0.0.1:$GAMA_SERVER_PORT")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to patch connector defaults", e)
        }
    }

    private fun generateStartupScript(vncW: Int = 1920, vncH: Int = 1080): String = """
        |#!/bin/bash
        |
        |# GAMA Mobile startup — Ubuntu PRoot
        |# Commands mirror c.sh + u.sh pattern, with VNC fallback
        |
        |unset LD_PRELOAD
        |export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-arm64
        |export PATH=${'$'}JAVA_HOME/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
        |export HOME=/data
        |export USER=shell
        |export TMPDIR=/tmp
        |export VNC_PORT=5901
        |export VNC_WIDTH=${vncW}
        |export VNC_HEIGHT=${vncH}
        |
        |# From u.sh / c.sh
        |export DISPLAY=:0
        |export GDK_BACKEND=x11
        |
        |# Software OpenGL via Mesa llvmpipe (only option inside PRoot on Android)
        |# Zink/Vulkan not viable: Turnip needs MSM DRM, Android HAL needs hwservicemanager
        |# OSMesa is also installed as a JOGL fallback (no X server needed).
        |export GALLIUM_DRIVER=llvmpipe
        |export LIBGL_ALWAYS_SOFTWARE=1
        |export MESA_GL_VERSION_OVERRIDE=3.3
        |export MESA_GLSL_VERSION_OVERRIDE=330
        |
        |# Tell Mesa DRI loader where to find drivers and that /dev/dri may be missing
        |export MESA_LOADER_DRIVER_OVERRIDE=llvmpipe
        |mkdir -p /opt/gama/logs /tmp /data /workspace 2>/dev/null
        |
        |# Bridge server (REST API on 8081) for the RN app; the in-app proxy forwards
        |# 8080 -> 8081. Unset the X-related LD_PRELOAD so Python's loader stays clean.
        |( unset LD_PRELOAD
        |  nohup env BACKEND_PORT=8081 python3 /opt/gama/bridge-server.py \
        |      >/opt/gama/logs/bridge.log 2>&1 &
        |  echo "[startup] Bridge server starting on 8081 (pid $!)"
        |)
        |
        |# LD_PRELOAD shim for X servers and GAMA (hard link fix under PRoot)
        |if [ -f /opt/gama/override_link.so ]; then
        |  export LD_PRELOAD=/opt/gama/override_link.so
        |fi
        |
        |echo "[startup] Ubuntu PRoot starting"
        |java -version 2>&1 | head -1 || echo "[startup] Java not found"
        |
        |# From c.sh: dbus + /dev/shm (with fallbacks for PRoot)
        |service dbus start 2>/dev/null || dbus-daemon --system --fork 2>/dev/null || true
        |mkdir -p /dev/shm 2>/dev/null || true
        |chmod 777 /dev/shm 2>/dev/null || true
        |
        |# Ensure DNS works inside PRoot for apt-get fallback
        |if ! grep -qs nameserver /etc/resolv.conf 2>/dev/null; then
        |  echo "nameserver 8.8.8.8" > /etc/resolv.conf 2>/dev/null || true
        |  echo "nameserver 8.8.4.4" >> /etc/resolv.conf 2>/dev/null || true
        |fi
        |
        |# X server + VNC: Xvfb (patched, preplaced keymap) + x11vnc -noshm.
        |# Single-instance guard: kill any stale Xvfb/x11vnc left by a previous boot
        |# (keep-alive relaunch loops can otherwise pile up servers).
        |pkill -f 'Xvfb :0' 2>/dev/null || true
        |pkill -x x11vnc 2>/dev/null || true
        |rm -f /tmp/.X0-lock /tmp/.X11-unix/X0 2>/dev/null || true
        |mkdir -p /tmp/.X11-unix 2>/dev/null || true
        |
        |# Create /dev/dri for Mesa DRI loader (llvmpipe checks this path)
        |mkdir -p /dev/dri 2>/dev/null || true
        |
        |X_SERVER_RUNNING=false
        |
        |X_SERVER_LOG=/opt/gama/logs/xserver.log
        |
        |# Pre-place compiled keymap so the server's in-process xkbcomp is a no-op
        |# (server loads /var/lib/xkb/server-<display>.xkm and unlinks it, so re-do every boot)
        |mkdir -p /usr/share/X11/xkb/keymap /usr/share/X11/xkb/compiled /var/lib/xkb/compiled /var/lib/xkb /tmp/compiled 2>/dev/null
        |if [ ! -s /opt/gama/default.xkm ]; then
        |  cat > /tmp/kb.xkb <<'END'
        |xkb_keymap {
        |  xkb_keycodes { include "evdev+aliases(qwerty)" };
        |  xkb_types    { include "complete" };
        |  xkb_compat   { include "complete" };
        |  xkb_symbols  { include "pc+us+inet(evdev)" };
        |};
        |END
        |  xkbcomp -w 2 -I/usr/share/X11/xkb -R/usr/share/X11/xkb -xkm -o /usr/share/X11/xkb/compiled/xfree86 /tmp/kb.xkb >>${'$'}X_SERVER_LOG 2>&1
        |  cp /usr/share/X11/xkb/compiled/xfree86 /opt/gama/default.xkm 2>/dev/null
        |fi
        |cp /opt/gama/default.xkm /var/lib/xkb/server-0.xkm 2>/dev/null
        |cp /opt/gama/default.xkm /var/lib/xkb/compiled/server-0.xkm 2>/dev/null
        |cp /opt/gama/default.xkm /tmp/compiled/server-0.xkm 2>/dev/null
        |chmod 666 /var/lib/xkb/server-0.xkm 2>/dev/null
        |echo "[startup] preplaced /var/lib/xkb/server-0.xkm: ${'$'}(ls -la /var/lib/xkb/server-0.xkm 2>&1)" >>${'$'}X_SERVER_LOG
        |
        |# Xvfb is the sole X server: its in-process xkbcomp is bypassed by the
        |# pre-placed /var/lib/xkb/server-0.xkm + NOP'd checks in the binary, and
        |# override_link.so shims the hard-link failure it hits under PRoot.
        |start_xfb() {
        |  echo "[startup] Starting Xvfb on display ${'$'}DISPLAY..."
        |  : > ${'$'}X_SERVER_LOG
        |  LD_PRELOAD=/opt/gama/override_link.so \
        |  XKB_CONFIG_ROOT=/usr/share/X11/xkb \
        |  Xvfb ${'$'}DISPLAY -screen 0 ${'$'}{VNC_WIDTH}x${'$'}{VNC_HEIGHT}x24 -pixdepths 8 16 24 32 \
        |    -noreset +extension GLX +extension RENDER +extension COMPOSITE \
        |    &>>${'$'}X_SERVER_LOG 2>&1 &
        |  XVFB_PID=${'$'}!
        |  for i in ${'$'}(seq 1 30); do
        |    kill -0 ${'$'}XVFB_PID 2>/dev/null || break
        |    sleep 1
        |  done
        |  if kill -0 ${'$'}XVFB_PID 2>/dev/null; then
        |    echo "[startup] Xvfb running (PID ${'$'}XVFB_PID)"
        |    X_SERVER_RUNNING=true
        |  else
        |    echo "[startup] Xvfb failed, tail of log:"
        |    tail -20 ${'$'}X_SERVER_LOG 2>/dev/null || true
        |  fi
        |}
        |
        |start_xfb
        |
        |if [ "${'$'}X_SERVER_RUNNING" = true ]; then
        |  echo "[startup] Starting x11vnc on port ${'$'}VNC_PORT (with relaunch loop)..."
        |  (
        |    while [ -d /tmp/.X11-unix ]; do
        |      if ! pgrep -x x11vnc >/dev/null 2>&1; then
        |        echo "[x11vnc] launching ${'$'}(date +%H:%M:%S)" >>/opt/gama/logs/x11vnc.log
        |        x11vnc -display ${'$'}DISPLAY -forever -shared -nopw -noxdamage \
        |          -noshm -localhost -rfbport ${'$'}VNC_PORT >/opt/gama/logs/x11vnc.err 2>&1
        |        echo "[x11vnc] exited rc=${'$'}? ${'$'}(date +%H:%M:%S)" >>/opt/gama/logs/x11vnc.log
        |      fi
        |      sleep 5
        |    done
        |  ) &
        |fi
        |
        |# Wait for VNC port (up to 60s)
        |echo "[startup] Waiting for VNC on port ${'$'}VNC_PORT..."
        |for i in $(seq 1 60); do
        |  python3 -c "import socket; s=socket.socket(); s.settimeout(2); s.connect(('127.0.0.1', ${'$'}VNC_PORT)); s.close()" 2>/dev/null \
        |    && echo "[startup] VNC ready! (attempt ${'$'}i)" && break
        |  [ ${'$'}i -eq 60 ] && echo "[startup] VNC not ready after 60s"
        |  sleep 1
        |done
        |
        |# From c.sh: openbox
        |echo "[startup] Starting openbox..."
        |pgrep openbox | openbox &
        |
        |# GLX diagnostic: log available OpenGL info before launching GAMA
        |echo "[startup] === OpenGL/GLX diagnostic ==="
        |if command -v glxinfo &>/dev/null; then
        |  LIBGL_DEBUG=verbose glxinfo -B 2>&1 | head -30 || echo "[startup] glxinfo failed"
        |else
        |  echo "[startup] glxinfo not available"
        |fi
        |if command -v eglinfo &>/dev/null; then
        |  eglinfo -B 2>&1 | head -20 || echo "[startup] eglinfo failed"
        |fi
        |echo "[startup] LIBGL_ALWAYS_SOFTWARE=${'$'}LIBGL_ALWAYS_SOFTWARE"
        |echo "[startup] GALLIUM_DRIVER=${'$'}GALLIUM_DRIVER"
        |echo "[startup] === end diagnostic ==="
        |
        |# From c.sh: cd gama && ./Gama
        |GAMA_HOME=/opt/gama
        |# JOGL common args to force software rendering / avoid GLX issues.
        |# NOTE: -Dnativewindow.ws.name=x11 must NOT be set: NEWT then resolves the
        |# x11.DisplayDriver class from the gama.ui.display.opengl bundle loader,
        |# which fails (ClassNotFoundException) and breaks the 3D display.
        |JOGL_ARGS="-Djogamp.gluegen.UseTempJarCache=false \
        |  -Djogamp.opengl.GLContext.nativeGL2=1 \
        |  -Djava.awt.headless=false"
        |
        |# JOGL resolves native libs from GAMA_HOME/natives/<os>-<arch>. The app
        |# installs the matching 2.6.0 aarch64 natives from assets before PRoot
        |# starts; this is only a fallback for archives shipped without them
        |# (reads nested natives jars inside the OpenGL plugin, then flattens).
        |extract_jogl_natives() {
        |  local dst=/opt/gama/natives/linux-aarch64
        |  [ -f "${'$'}dst/libgluegen_rt.so" ] && [ -f "${'$'}dst/libjogl_desktop.so" ] && return 0
        |  mkdir -p "${'$'}dst"
        |  local plugin
        |  plugin=${'$'}(ls /opt/gama/plugins/gama.ui.display.opengl_*.jar 2>/dev/null | head -1)
        |  if [ -n "${'$'}plugin" ]; then
        |    python3 - "${'$'}plugin" "${'$'}dst" <<'PY'
        |import sys, zipfile, os, io
        |plugin, dst = sys.argv[1], sys.argv[2]
        |with zipfile.ZipFile(plugin) as pz:
        |    nested = [n for n in pz.namelist()
        |              if n.endswith('-natives-linux-aarch64.jar')]
        |    for nj in nested:
        |        nz = zipfile.ZipFile(io.BytesIO(pz.read(nj)))
        |        for n in nz.namelist():
        |            if n.endswith('.so') and '/' in n:
        |                out = os.path.join(dst, os.path.basename(n))
        |                with nz.open(n) as src, open(out, 'wb') as f:
        |                    f.write(src.read())
        |                os.chmod(out, 0o755)
        |                sys.stderr.write(f"[natives] {out}\n")
        |PY
        |  fi
        |  chmod 755 "${'$'}dst"/*.so 2>/dev/null
        |  if [ -f "${'$'}dst/libgluegen_rt.so" ] && [ -f "${'$'}dst/libjogl_desktop.so" ]; then
        |    echo "[startup] JOGL natives ready in ${'$'}dst"
        |  else
        |    echo "[startup] WARN: JOGL natives still missing in ${'$'}dst"
        |  fi
        |  return 0
        |}
        |extract_jogl_natives
        |
        |# Helper: launch GAMA with LD_PRELOAD for hard link fix
        |run_gama() {
        |  cd "${'$'}GAMA_HOME"
        |  if [ -f /opt/gama/override_link.so ]; then
        |    LD_PRELOAD=/opt/gama/override_link.so \
        |    DISPLAY=:0 ./Gama -vmargs \
        |      -Dosgi.locking=none \
        |      -Dorg.eclipse.core.resources.disable.workspace.locking=true \
        |      --add-modules=jdk.incubator.vector \
        |      ${'$'}JOGL_ARGS &>/opt/gama/logs/gama.log 2>&1
        |  else
        |    DISPLAY=:0 ./Gama -vmargs \
        |      -Dosgi.locking=none \
        |      -Dorg.eclipse.core.resources.disable.workspace.locking=true \
        |      --add-modules=jdk.incubator.vector \
        |      ${'$'}JOGL_ARGS &>/opt/gama/logs/gama.log 2>&1
        |  fi
        |}
        |
        |if [ -f "${'$'}GAMA_HOME/Gama" ]; then
        |  echo "[startup] Launching GAMA..."
        |  run_gama &
        |  GAMA_PID=${'$'}!
        |  echo "[startup] GAMA PID: ${'$'}GAMA_PID"
        |else
        |  echo "[startup] GAMA binary not found at ${'$'}GAMA_HOME"
        |fi
        |
        |# Keep alive
        |while true; do
        |  sleep 5
        |  if [ -n "${'$'}GAMA_PID" ] && ! kill -0 ${'$'}GAMA_PID 2>/dev/null; then
        |    echo "[startup] GAMA died, restarting..."
        |    run_gama &
        |    GAMA_PID=${'$'}!
        |    echo "[startup] GAMA restarted with PID ${'$'}GAMA_PID"
        |  fi
        |done
        |""".trimMargin()

    private fun downloadOrExtractRootfs(destDir: File, onProgress: ((String) -> Unit)? = null): File? {
        val destFile = File(destDir, ROOTFS_ARCHIVE)

        // Check if already downloaded (>10MB means real file, not pointer)
        if (destFile.exists() && destFile.length() > 10_000_000) {
            Log.i(TAG, "Rootfs archive already downloaded: ${destFile.length()} bytes")
            return destFile
        }

        // Preferred: bundled APK raw resource (authoritative for this build)
        onProgress?.invoke("extracting rootfs from app bundle")
        val bundled = extractRawResource(ROOTFS_ARCHIVE, destDir)
        if (bundled != null && bundled.length() > 10_000_000) {
            Log.i(TAG, "Rootfs archive extracted from bundle: ${bundled.length()} bytes")
            return bundled
        }

        // Fallback: try downloading from GitHub Releases
        onProgress?.invoke("downloading rootfs from GitHub")
        Log.i(TAG, "Downloading rootfs from $ROOTFS_URL")
        try {
            val url = URL(ROOTFS_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 60_000
            conn.readTimeout = 0
            conn.connect()

            val totalSize = conn.contentLength
            Log.i(TAG, "Download size: $totalSize bytes")

            conn.inputStream.use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalRead = 0L
                    var lastPct = -1
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (totalSize > 0) {
                            val pct = (totalRead * 100 / totalSize).toInt()
                            if (pct >= lastPct + 10) {
                                lastPct = pct
                                onProgress?.invoke("downloading rootfs: $pct%")
                            }
                        } else if (totalRead % (10 * 1024 * 1024) < 8192) {
                            val mb = totalRead / (1024 * 1024)
                            onProgress?.invoke("downloading rootfs: ${mb}MB downloaded")
                        }
                    }
                }
            }
            Log.i(TAG, "Download complete: ${destFile.length()} bytes")
            return destFile
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}")
            destFile.delete()
        }

        // Fallback: try extracting from APK raw resource
        return extractRawResource(ROOTFS_ARCHIVE, destDir)
    }

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
