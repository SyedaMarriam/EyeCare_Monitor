package com.eyecare.monitor

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.eyecare.monitor.databinding.ActivityMainBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Premium EyeCare Monitor - Professional Medical Dashboard.
 * Fixed property access errors and improved monitoring flow.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var eyeCareService: EyeCareService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as EyeCareService.LocalBinder
            eyeCareService = binder.getService()
            isBound = true
            setupServiceUpdateListener()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            eyeCareService = null
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.CAMERA] == true) {
            checkBatteryOptimization()
            startMonitoringSequence()
        } else {
            Toast.makeText(this, "Camera permission is required for monitoring.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
        
        // Auto-bind to service if it's already running in the background
        if (EyeCareService.isServiceRunning) {
            val intent = Intent(this, EyeCareService::class.java)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            updateToggleButton(true)
        }
    }

    private fun setupListeners() {
        binding.btnToggleService.setOnClickListener {
            // FIXED: Using static property access 'isServiceRunning' from Companion Object
            if (!EyeCareService.isServiceRunning) {
                handleStartAction()
            } else {
                stopMonitoring()
            }
        }
    }

    private fun handleStartAction() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            startMonitoringSequence()
        } else {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun startMonitoringSequence() {
        lifecycleScope.launch {
            // Show loading state with professional messages
            binding.btnToggleService.visibility = View.GONE
            binding.loadingLayout.visibility = View.VISIBLE
            
            val sequence = listOf(
                "Initializing Camera...",
                "Loading Detection Models...",
                "Preparing Guard System..."
            )

            for (msg in sequence) {
                binding.tvLoadingMsg.text = msg
                delay(800)
            }

            // Start the foreground service
            val intent = Intent(this@MainActivity, EyeCareService::class.java)
            ContextCompat.startForegroundService(this@MainActivity, intent)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            
            binding.loadingLayout.visibility = View.GONE
            binding.btnToggleService.visibility = View.VISIBLE
            updateToggleButton(true)
        }
    }

    private fun stopMonitoring() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        stopService(Intent(this, EyeCareService::class.java))
        eyeCareService = null
        updateToggleButton(false)
        resetDashboard()
    }

    private fun setupServiceUpdateListener() {
        eyeCareService?.onUpdateListener = { result, suggestion, activeMs, unsafeSec ->
            runOnUiThread {
                updateUI(result, suggestion, activeMs, unsafeSec)
            }
        }
    }

    private fun updateUI(result: AnalysisResult, suggestion: String, activeMs: Long, unsafeSec: Int) {
        if (!result.isFaceDetected) {
            binding.tvHealthScore.text = "--"
            binding.tvHealthScore.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            binding.tvDistanceValue.text = "Scanning..."
            binding.tvDistanceValue.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            binding.tvAiSuggestion.text = "Searching for face..."
            return
        }

        // 1. Update Health Score
        binding.tvHealthScore.text = "${result.overallHealthScore}%"
        val scoreColor = when {
            result.overallHealthScore > 80 -> R.color.status_safe
            result.overallHealthScore > 50 -> R.color.status_warning
            else -> R.color.status_danger
        }
        binding.tvHealthScore.setTextColor(ContextCompat.getColor(this, scoreColor))

        // 2. Update Distance with Dynamic Colors
        binding.tvDistanceValue.text = "${result.distanceCm.toInt()} cm"
        val distColor = when (result.distanceStatus) {
            DistanceStatus.SAFE -> R.color.status_safe
            DistanceStatus.WARNING -> R.color.status_warning
            else -> R.color.status_danger
        }
        binding.tvDistanceValue.setTextColor(ContextCompat.getColor(this, distColor))

        // 3. Update Posture
        binding.tvPostureValue.text = if (result.postureStatus == PostureStatus.GOOD) "Good" else "Bad"
        val postColor = if (result.postureStatus == PostureStatus.GOOD) R.color.status_safe else R.color.status_danger
        binding.tvPostureValue.setTextColor(ContextCompat.getColor(this, postColor))

        // 4. Update Eye Strain
        binding.tvStrainValue.text = result.eyeStrainLevel.name
        val strainColor = when (result.eyeStrainLevel) {
            EyeStrainLevel.LOW -> R.color.status_safe
            EyeStrainLevel.MODERATE -> R.color.status_warning
            else -> R.color.status_danger
        }
        binding.tvStrainValue.setTextColor(ContextCompat.getColor(this, strainColor))

        // 5. Update Blink Rate
        binding.tvBlinkRate.text = "${result.blinksPerMinute} BPM"
        binding.tvBlinkRate.setTextColor(ContextCompat.getColor(this, R.color.accent_blue))

        // 6. Analytics & Suggestion
        binding.tvActiveTime.text = formatTime(activeMs)
        binding.tvUnsafeUsage.text = "${unsafeSec}s"
        binding.tvAiSuggestion.text = suggestion
    }

    private fun updateToggleButton(isRunning: Boolean) {
        if (isRunning) {
            binding.btnToggleService.text = "STOP MONITORING"
            binding.btnToggleService.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_danger)
        } else {
            binding.btnToggleService.text = "START MONITORING"
            binding.btnToggleService.backgroundTintList = ContextCompat.getColorStateList(this, R.color.accent_blue)
        }
    }

    private fun resetDashboard() {
        binding.tvHealthScore.text = "--"
        binding.tvHealthScore.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        binding.tvDistanceValue.text = "--"
        binding.tvDistanceValue.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        binding.tvPostureValue.text = "--"
        binding.tvPostureValue.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        binding.tvStrainValue.text = "--"
        binding.tvStrainValue.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        binding.tvBlinkRate.text = "--"
        binding.tvActiveTime.text = "00:00"
        binding.tvUnsafeUsage.text = "0s"
        binding.tvAiSuggestion.text = "System ready"
    }

    private fun checkBatteryOptimization() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                })
            } catch (e: Exception) {}
        }
    }

    private fun formatTime(ms: Long): String {
        val s = (ms / 1000) % 60
        val m = (ms / 60000) % 60
        val h = (ms / 3600000)
        return if (h > 0) String.format("%02d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) unbindService(serviceConnection)
    }
}
