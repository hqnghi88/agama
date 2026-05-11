package com.simulation.mobile.module

import android.content.Intent
import android.util.Log
import com.facebook.react.bridge.*
import com.simulation.mobile.service.SimulationService

class SimulationModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        const val NAME = "SimulationModule"
        const val TAG = "SimulationModule"
    }

    override fun getName(): String = NAME

    @ReactMethod
    fun startBackend(promise: Promise) {
        try {
            val intent = Intent(reactApplicationContext, SimulationService::class.java).apply {
                action = SimulationService.ACTION_START
            }
            reactApplicationContext.startForegroundService(intent)
            promise.resolve(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start backend", e)
            promise.reject("START_ERROR", e.message)
        }
    }

    @ReactMethod
    fun stopBackend(promise: Promise) {
        try {
            val intent = Intent(reactApplicationContext, SimulationService::class.java).apply {
                action = SimulationService.ACTION_STOP
            }
            reactApplicationContext.startService(intent)
            promise.resolve(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop backend", e)
            promise.reject("STOP_ERROR", e.message)
        }
    }

    @ReactMethod
    fun getStatus(promise: Promise) {
        try {
            val map = Arguments.createMap().apply {
                putString("status", SimulationService.backendStatus)
                putString("progress", SimulationService.backendProgress)
                putInt("pid", SimulationService.backendPid)
                putInt("vncHttpPort", SimulationService.vncHttpPort)
                putInt("vncWsPort", SimulationService.vncWsPort)
            }
            promise.resolve(map)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get status", e)
            promise.reject("STATUS_ERROR", e.message)
        }
    }

    @ReactMethod
    fun openVncViewer(promise: Promise) {
        try {
            val httpPort = SimulationService.vncHttpPort
            val wsPort = SimulationService.vncWsPort
            if (httpPort < 0 || wsPort < 0) {
                promise.reject("VNC_NOT_READY", "VNC proxy is not running")
                return
            }
            promise.resolve(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open VNC viewer", e)
            promise.reject("VNC_ERROR", e.message)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    @ReactMethod
    fun addListener(eventName: String) {}

    @Suppress("UNUSED_PARAMETER")
    @ReactMethod
    fun removeListeners(count: Int) {}
}
