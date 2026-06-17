package com.eyecare.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.*
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Professional Autonomous Foreground Service for EyeCare Monitoring.
 * Works fully automatically without manual configuration.
 */
class EyeCareService : LifecycleService(), TextToSpeech.OnInitListener {

    companion object {
        const val CHANNEL_ID = "eyecare_autonomous_status"
        const val ALERT_CHANNEL_ID = "eyecare_health_alerts"
        const val NOTIFICATION_ID = 9001

        private const val ALERT_THROTTLE_MS = 20000L
        private const val RULE_20_MIN_MS = 20 * 60 * 1000L

        @Volatile
        var isServiceRunning: Boolean = false
            private set
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private var powerManager: PowerManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var vibrator: Vibrator? = null
    private var tts: TextToSpeech? = null

    private var cameraExecutor: ExecutorService? = null
    private var cameraProvider: ProcessCameraProvider? = null

    // Real-time State
    private var activeTimeMs = 0L
    private var unsafeSec = 0
    private var lastReminderTime = System.currentTimeMillis()
    private var lastAlertTime = 0L

    var onUpdateListener: ((AnalysisResult, String, Long, Int) -> Unit)? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    stopCamera()
                    wakeLock?.let { if (it.isHeld) it.release() }
                }
                Intent.ACTION_SCREEN_ON -> {
                    if (isServiceRunning) {
                        startCamera()
                        wakeLock?.acquire(8 * 60 * 60 * 1000L)
                    }
                }
            }
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): EyeCareService = this@EyeCareService
    }

    override fun onCreate() {
        super.onCreate()
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        
        tts = TextToSpeech(this, this)
        cameraExecutor = Executors.newSingleThreadExecutor()
        
        createNotificationChannels()
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        })
        
        wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EyeCare::WakeLock")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (!isServiceRunning) {
            isServiceRunning = true
            wakeLock?.acquire(8 * 60 * 60 * 1000L)
            startForeground(NOTIFICATION_ID, buildStatusNotification("Autonomous Guard Active", "Protecting your vision health"))
            startCamera()
            startLogicLoop()
        }
        return START_STICKY
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                val analyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setImageQueueDepth(1)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor!!, FaceDistanceAnalyzer { result ->
                            handleResult(result)
                        })
                    }

                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analyzer)
            } catch (e: Exception) {
                isServiceRunning = false
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        cameraProvider?.unbindAll()
    }

    private fun handleResult(result: AnalysisResult) {
        if (result.isFaceDetected) {
            activeTimeMs += 100 // Based on analyzer interval
            if (result.distanceStatus == DistanceStatus.CLOSE || result.postureStatus == PostureStatus.BAD) {
                unsafeSec++
            }
            checkCriticalAlerts(result)
        }
        
        val msg = getIntelligentMessage(result)
        onUpdateListener?.invoke(result, msg, activeTimeMs, unsafeSec)
    }

    private fun checkCriticalAlerts(result: AnalysisResult) {
        val now = System.currentTimeMillis()
        if (now - lastAlertTime < ALERT_THROTTLE_MS) return

        when {
            result.distanceStatus == DistanceStatus.CLOSE -> {
                triggerCritical("Too Close!", "Move back from screen.", true)
                speak("Please keep distance.")
                lastAlertTime = now
            }
            result.postureStatus == PostureStatus.BAD -> {
                triggerCritical("Posture Alert", "Check sitting posture.", false)
                speak("Try to sit up straight.")
                lastAlertTime = now
            }
            result.eyeStrainDetected && result.eyeStrainLevel == EyeStrainLevel.HIGH -> {
                triggerCritical("High Eye Strain", "Your eyes need a break.", false)
                speak("Take a short break.")
                lastAlertTime = now
            }
        }
    }

    private fun getIntelligentMessage(res: AnalysisResult): String = when {
        !res.isFaceDetected -> "Scanning..."
        res.distanceStatus == DistanceStatus.CLOSE -> "Increase screen distance"
        res.postureStatus == PostureStatus.BAD -> "Improve your posture"
        res.eyeActivity == EyeActivityState.LOW_BLINK -> "Blink more frequently"
        else -> "Eye health is optimal"
    }

    private fun startLogicLoop() {
        serviceScope.launch {
            while (isServiceRunning) {
                delay(1000)
                if (System.currentTimeMillis() - lastReminderTime >= RULE_20_MIN_MS) {
                    triggerCritical("20-20-20 Rule", "Look 20 feet away for 20 seconds.", false)
                    speak("Time for an eye break.")
                    lastReminderTime = System.currentTimeMillis()
                }
            }
        }
    }

    private fun triggerCritical(title: String, msg: String, urgent: Boolean) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(if (urgent) 500 else 300, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION") vibrator?.vibrate(if (urgent) 500 else 300)
        }

        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_eye_logo)
            .setContentTitle(title)
            .setContentText(msg)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setColor(ContextCompat.getColor(this, if (urgent) R.color.status_danger else R.color.status_warning))
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            .setAutoCancel(true)
            .build()
            
        nm.notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "EyeCareID")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts?.language = Locale.US
    }

    private fun buildStatusNotification(title: String, text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(this, 0, 
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_eye_logo)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setColor(ContextCompat.getColor(this, R.color.accent_blue))
            .build()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Service Status", NotificationManager.IMPORTANCE_LOW))
            val alertChannel = NotificationChannel(ALERT_CHANNEL_ID, "Health Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build())
            }
            nm.createNotificationChannel(alertChannel)
        }
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onDestroy() {
        isServiceRunning = false
        wakeLock?.let { if (it.isHeld) it.release() }
        serviceScope.cancel()
        try { unregisterReceiver(screenReceiver) } catch (e: Exception) {}
        cameraExecutor?.shutdown()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
