package com.watchwire.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.watchwire.app.MainActivity
import com.watchwire.app.R
import com.watchwire.app.WatchWireRepository
import com.watchwire.app.motion.MotionAnalyzer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val TAG = "MonitoringService"
private const val CHANNEL_ID = "watchwire_monitoring"
private const val NOTIFICATION_ID = 1001
private const val ACTION_START = "com.watchwire.app.action.START"
private const val ACTION_STOP = "com.watchwire.app.action.STOP"

/** Foreground service that owns the CameraX pipeline and runs motion detection while the
 * screen is off. It never talks to the network directly -- all WebSocket traffic goes
 * through [WatchWireRepository], which is a process-wide singleton, so this service and
 * MainActivity always see the same connection/pairing state. Being a proper foreground
 * service (with a persistent notification and a "camera" service type) is what lets the
 * OS keep this process, and therefore the camera pipeline, alive with the screen off. */
class MonitoringService : LifecycleService() {

    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisExecutor: ExecutorService? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isRunning = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopMonitoring()
            return START_NOT_STICKY
        }
        startMonitoring()
        return START_STICKY
    }

    private fun startMonitoring() {
        if (isRunning) return
        isRunning = true

        ensureNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        acquireWakeLock()
        bindCamera()
        WatchWireRepository.setMonitoringActive(true)
    }

    private fun stopMonitoring() {
        WatchWireRepository.setMonitoringActive(false)
        unbindCamera()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        isRunning = false
    }

    private fun bindCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener(
            {
                val provider = providerFuture.get()
                cameraProvider = provider

                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(320, 240), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                    )
                    .build()

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(resolutionSelector)
                    .build()

                val executor = Executors.newSingleThreadExecutor()
                analysisExecutor = executor
                analysis.setAnalyzer(
                    executor,
                    MotionAnalyzer(sensitivity = WatchWireRepository.sensitivity) { score ->
                        WatchWireRepository.reportMotion(score)
                    },
                )

                try {
                    provider.unbindAll()
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to bind camera for motion analysis", e)
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun unbindCamera() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        analysisExecutor?.shutdown()
        analysisExecutor = null
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WatchWire::MonitoringWakeLock").apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 60 * 1000L) // 10h safety cap; renewed implicitly by service restarts if ever needed
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun ensureNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, MonitoringService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WatchWire is watching for motion")
            .setContentText("Tap to open. Video is never recorded or uploaded.")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        unbindCamera()
        releaseWakeLock()
        if (WatchWireRepository.monitoringActive.value) {
            WatchWireRepository.setMonitoringActive(false)
        }
        isRunning = false
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, MonitoringService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, MonitoringService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
