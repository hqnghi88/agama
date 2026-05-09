package com.simulation.mobile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class SimulationService : Service() {

    companion object {
        const val TAG = "SimulationService"
        const val ACTION_START = "com.simulation.mobile.START"
        const val ACTION_STOP = "com.simulation.mobile.STOP"
        const val ACTION_STATUS = "com.simulation.mobile.STATUS"
        const val CHANNEL_ID = "simulation_service"
        const val NOTIFICATION_ID = 1001
        const val BACKEND_PORT = 8080
        const val GUEST_BACKEND_PORT = 8081

        @Volatile
        var backendStatus: String = "stopped"
            private set
        @Volatile
        var backendPid: Int = -1
            private set
    }

    private var prootManager: PRootManager? = null
    private var fallbackServer: FallbackHealthServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val isRunning = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Creating simulation service")
        createNotificationChannel()
        prootManager = PRootManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, createNotification("Starting backend..."))
                acquireWakeLock()
                startBackend()
            }
            ACTION_STOP -> {
                stopBackend()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startBackend() {
        if (isRunning.getAndSet(true)) {
            Log.d(TAG, "Backend already running")
            return
        }

        Thread {
            try {
                backendStatus = "initializing"
                updateNotification("Initializing...")

                val rootfsDir = File(filesDir, "rootfs")
                val workspaceDir = File(filesDir, "workspace")
                workspaceDir.mkdirs()

                prootManager?.let { pm ->
                    val success = pm.setupRootfs(rootfsDir)
                    if (!success) {
                        Log.e(TAG, "Failed to setup rootfs")
                        backendStatus = "error: rootfs setup failed"
                        isRunning.set(false)
                        return@Thread
                    }

                    // Start proxy server on port 8080 BEFORE PRoot.
                    // Proxies API calls to the real guest backend on port 8081.
                    // Falls back to {"status":"ok","mode":"fallback"} for health checks
                    // when the guest backend is not yet ready.
                    backendStatus = "starting"
                    updateNotification("Starting proxy server...")
                    fallbackServer = FallbackHealthServer(BACKEND_PORT, GUEST_BACKEND_PORT)
                    val fallbackStarted = fallbackServer!!.start()
                    if (fallbackStarted) {
                        Log.i(TAG, "Proxy server started on 127.0.0.1:$BACKEND_PORT -> 127.0.0.1:$GUEST_BACKEND_PORT")
                        backendStatus = "running"
                        backendPid = -1
                        updateNotification("Backend running on port $BACKEND_PORT")
                    } else {
                        Log.w(TAG, "Proxy server failed to start")
                    }

                    updateNotification("Starting Linux container...")
                    pm.startPRoot(rootfsDir, workspaceDir, GUEST_BACKEND_PORT) { pid ->
                        backendPid = pid
                        Log.i(TAG, "PRoot backend started with PID $pid")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start backend", e)
                backendStatus = "error: ${e.message}"
                isRunning.set(false)
            }
        }.apply {
            name = "backend-starter"
            start()
        }
    }

    private fun stopBackend() {
        Log.d(TAG, "Stopping backend")
        backendStatus = "stopping"
        updateNotification("Stopping...")
        prootManager?.stopPRoot()
        fallbackServer?.stop()
        fallbackServer = null
        backendStatus = "stopped"
        backendPid = -1
        isRunning.set(false)
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Simulation Backend",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "GAMA simulation backend service"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        val iconId = resources.getIdentifier("ic_notification", "drawable", packageName)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GAMA Simulation")
            .setContentText(text)
            .setSmallIcon(if (iconId != 0) iconId else android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "simulation:backend"
            )
            wakeLock?.acquire(10 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopBackend()
        super.onDestroy()
    }
}
