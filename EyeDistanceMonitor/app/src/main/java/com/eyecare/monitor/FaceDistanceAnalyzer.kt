package com.eyecare.monitor

import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlin.math.abs
import kotlin.math.hypot

enum class DistanceStatus { CLOSE, WARNING, SAFE, NO_FACE }
enum class PostureStatus { GOOD, BAD, NO_FACE }
enum class EyeStrainLevel { LOW, MODERATE, HIGH }
enum class EyeActivityState { NORMAL, LOW_BLINK, EXCESSIVE, TIRED }

data class AnalysisResult(
    val isFaceDetected: Boolean,
    val distanceCm: Float,
    val distanceStatus: DistanceStatus,
    val postureStatus: PostureStatus,
    val eyeStrainLevel: EyeStrainLevel,
    val eyeActivity: EyeActivityState,
    val totalBlinks: Int,
    val blinksPerMinute: Int,
    val overallHealthScore: Int,
    val eyeStrainDetected: Boolean,
    val isStable: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

class FaceDistanceAnalyzer(
    private val onResult: (AnalysisResult) -> Unit
) : ImageAnalysis.Analyzer {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.15f)
            .build()
    )

    private var totalBlinksCount = 0
    private val blinkWindow = mutableListOf<Long>()
    private var isEyeClosed = false
    private var blinkStartTime = 0L
    private val distHistory = mutableListOf<Float>()
    private var lastAnalysisTime = 0L
    private var monitoringStartTime = System.currentTimeMillis()

    companion object {
        private const val FOCAL_FACTOR = 4050f 
        private const val ANALYSIS_INTERVAL_MS = 100L // 10 FPS for high responsiveness
        private const val HISTORY_SIZE = 5
        private const val BLINK_CLOSE_THRESHOLD = 0.22f
        private const val BLINK_OPEN_THRESHOLD = 0.55f
        
        // Autonomous Medical Thresholds
        private const val DIST_CLOSE_CM = 30f
        private const val DIST_WARNING_CM = 40f
        private const val HEALTHY_BLINK_RATE = 12
    }

    @ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastAnalysisTime < ANALYSIS_INTERVAL_MS) {
            imageProxy.close()
            return
        }
        lastAnalysisTime = now

        val mediaImage = imageProxy.image ?: run { imageProxy.close(); return }
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        detector.process(image)
            .addOnSuccessListener { faces ->
                val face = faces.maxByOrNull { it.boundingBox.width() }
                if (face == null) {
                    onResult(emptyResult())
                } else {
                    processFace(face, now)
                }
            }
            .addOnCompleteListener { imageProxy.close() }
    }

    private fun processFace(face: Face, now: Long) {
        // 1. Precise Distance Calculation
        val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
        
        val rawDist = if (leftEye != null && rightEye != null) {
            val px = hypot((leftEye.x - rightEye.x).toDouble(), (leftEye.y - rightEye.y).toDouble()).toFloat()
            (FOCAL_FACTOR / px).coerceIn(5f, 150f)
        } else {
            (FOCAL_FACTOR * 1.4f / face.boundingBox.width()).coerceIn(5f, 150f)
        }

        distHistory.add(rawDist)
        if (distHistory.size > HISTORY_SIZE) distHistory.removeAt(0)
        val smoothDist = distHistory.average().toFloat()

        val distStatus = when {
            smoothDist <= DIST_CLOSE_CM -> DistanceStatus.CLOSE
            smoothDist <= DIST_WARNING_CM -> DistanceStatus.WARNING
            else -> DistanceStatus.SAFE
        }

        // 2. Posture Check
        val isBadPosture = abs(face.headEulerAngleX) > 18f || abs(face.headEulerAngleZ) > 15f

        // 3. Stabilized Blink Detection
        val leftOpen = face.leftEyeOpenProbability ?: 0.5f
        val rightOpen = face.rightEyeOpenProbability ?: 0.5f
        val avgOpen = (leftOpen + rightOpen) / 2f
        
        if (avgOpen < BLINK_CLOSE_THRESHOLD && !isEyeClosed) {
            isEyeClosed = true
            blinkStartTime = now
        } else if (avgOpen > BLINK_OPEN_THRESHOLD && isEyeClosed) {
            isEyeClosed = false
            val duration = now - blinkStartTime
            if (duration in 70..500) { // Valid human blink range
                totalBlinksCount++
                blinkWindow.add(now)
            }
        }
        
        blinkWindow.removeAll { it < now - 60000 }
        val bpm = blinkWindow.size

        // 4. Intelligent Eye Strain Analysis
        var strainScore = 0
        if (distStatus == DistanceStatus.CLOSE) strainScore += 35
        if (isBadPosture) strainScore += 20
        if (bpm < HEALTHY_BLINK_RATE) strainScore += 30
        
        val sessionMin = (now - monitoringStartTime) / 60000
        if (sessionMin > 20) strainScore += 15

        val strainLevel = when {
            strainScore >= 65 -> EyeStrainLevel.HIGH
            strainScore >= 35 -> EyeStrainLevel.MODERATE
            else -> EyeStrainLevel.LOW
        }

        // 5. Dynamic Health Score
        val healthScore = (100 - strainScore).coerceIn(0, 100)

        onResult(AnalysisResult(
            isFaceDetected = true,
            distanceCm = smoothDist,
            distanceStatus = distStatus,
            postureStatus = if (isBadPosture) PostureStatus.BAD else PostureStatus.GOOD,
            eyeStrainLevel = strainLevel,
            eyeActivity = if (bpm < 10) EyeActivityState.LOW_BLINK else EyeActivityState.NORMAL,
            totalBlinks = totalBlinksCount,
            blinksPerMinute = bpm,
            overallHealthScore = healthScore,
            eyeStrainDetected = strainLevel == EyeStrainLevel.HIGH,
            isStable = distHistory.size >= 3
        ))
    }

    private fun emptyResult() = AnalysisResult(
        false, 0f, DistanceStatus.NO_FACE, PostureStatus.NO_FACE,
        EyeStrainLevel.LOW, EyeActivityState.NORMAL, totalBlinksCount, 0, 100, false, false
    )
}
