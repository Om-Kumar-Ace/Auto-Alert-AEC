package com.example.eyetonode

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.ExperimentalGetImage
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var surfaceView: SurfaceView
    private lateinit var cameraExecutor: ExecutorService
    private val client = OkHttpClient()
    private lateinit var sharedPreferences: SharedPreferences

    private var blinkCount = 0
    private var isYawning = false
    private var eyesClosedDuration = 0L

    private lateinit var blinkCountTextView: TextView
    private lateinit var yawnStatusTextView: TextView

    private companion object {
        const val REQUEST_CODE_PERMISSIONS = 10
        val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        const val BLINK_THRESHOLD = 0.4
        const val YAWN_THRESHOLD = 0.1
        const val YAWN_DURATION_LIMIT = 1000L // milliseconds
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize SharedPreferences
        sharedPreferences = getSharedPreferences("EyeToNodePrefs", MODE_PRIVATE)

        // Load saved data
        blinkCount = sharedPreferences.getInt("blinkCount", 0)
        isYawning = sharedPreferences.getBoolean("isYawning", false)

        createRequiredDirectories()

        surfaceView = findViewById(R.id.surface_view)
        blinkCountTextView = findViewById(R.id.blink_count)
        yawnStatusTextView = findViewById(R.id.yawn_status)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Update the TextViews with saved data
        updateBlinkCountTextView()
        updateYawnStatusTextView()

        setupSurfaceView()
    }

    private fun setupSurfaceView() {
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.d("Camera", "Surface created. Checking permissions...")
                if (allPermissionsGranted()) {
                    startCamera(holder)
                } else {
                    requestCameraPermission()
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Log.d("Camera", "Surface changed: width = $width, height = $height")
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.d("Camera", "Surface destroyed.")
            }
        })
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        AlertDialog.Builder(this)
            .setTitle("Camera Permission Required")
            .setMessage("This app requires camera access to detect blinks and yawns. Please enable it in settings.")
            .setPositiveButton("OK") { _, _ ->
                ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .create()
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera(surfaceView.holder)
            } else {
                showPermissionDeniedMessage()
            }
        }
    }

    private fun createRequiredDirectories() {
        val obbDir = getExternalFilesDir(null)?.resolve("obb")
        val dataDir = getExternalFilesDir(null)?.resolve("data")

        obbDir?.mkdirs()
        dataDir?.mkdirs()
    }

    private fun showPermissionDeniedMessage() {
        AlertDialog.Builder(this)
            .setTitle("Permission Denied")
            .setMessage("Camera permission is required to use this feature. Please enable it in settings.")
            .setPositiveButton("Go to Settings") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .create()
            .show()
    }

    private fun startCamera(holder: SurfaceHolder) {
        Log.d("Camera", "Starting camera...")
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
                setupCameraPreview(holder, cameraProvider)
                setupImageAnalysis(cameraProvider)
                Log.d("Camera", "Camera started and bound to lifecycle.")
            } catch (e: Exception) {
                Log.e("Camera", "Error starting camera: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setupCameraPreview(holder: SurfaceHolder, cameraProvider: ProcessCameraProvider) {
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider { surfaceRequest ->
                surfaceRequest.provideSurface(holder.surface, cameraExecutor) { _ -> }
            }
        }

        val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
        cameraProvider.bindToLifecycle(this, cameraSelector, preview)
    }

    @OptIn(ExperimentalGetImage::class)
    private fun setupImageAnalysis(cameraProvider: ProcessCameraProvider) {
        val imageAnalyzer = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_BLOCK_PRODUCER).build().also {
            it.setAnalyzer(cameraExecutor) { imageProxy -> processImage(imageProxy) }
        }

        val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
        cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalyzer)
    }

    @OptIn(ExperimentalGetImage::class)
    private fun processImage(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image ?: return
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees

        val inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)

        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .build()

        val detector = FaceDetection.getClient(options)

        detector.process(inputImage)
            .addOnSuccessListener { faces -> handleFaceDetectionSuccess(faces) }
            .addOnFailureListener { e -> Log.e("Error", "Face detection failed: $e") }
            .addOnCompleteListener {
                imageProxy.close()
                Log.d("Image Processing", "Image processed and closed.")
            }
    }

    private fun handleFaceDetectionSuccess(faces: List<Face>) {
        Log.d("FaceDetection", "Face detection successful: ${faces.size} face(s) detected.")
        for (face in faces) {
            detectBlinkAndYawn(face)
        }
    }

    private fun detectBlinkAndYawn(face: Face) {
        val leftEyeOpen = face.leftEyeOpenProbability?.toDouble() ?: 1.0
        val rightEyeOpen = face.rightEyeOpenProbability?.toDouble() ?: 1.0

        detectBlink(leftEyeOpen, rightEyeOpen)
        detectYawn(leftEyeOpen, rightEyeOpen)
    }

    private fun detectBlink(leftEyeOpen: Double, rightEyeOpen: Double) {
        if (leftEyeOpen < BLINK_THRESHOLD && rightEyeOpen < BLINK_THRESHOLD) {
            blinkCount++
            isYawning=false
            Log.d("Blink Detection", "Blink detected! Total blinks: $blinkCount")
            updateBlinkCountTextView()
            if (blinkCount >= 35) {
                isYawning=true
                Log.d("Blink Detection", "5 or more blinks detected! Activating buzzer.")
                sendSignalToNodeMCU("buzzer_high")
                blinkCount = 0 // Reset blink count after buzzer activation
            }
        } else {
            resetEyesClosedDuration()
            sendSignalToNodeMCU("buzzer_low")
        }
    }

    private fun detectYawn(leftEyeOpen: Double, rightEyeOpen: Double) {
        if (leftEyeOpen < YAWN_THRESHOLD && rightEyeOpen < YAWN_THRESHOLD) {
            eyesClosedDuration += 5
            Log.d("Yawning Detection", "Eyes closed for: $eyesClosedDuration ms")
            checkForYawn()
        } else {

            updateYawnStatusTextView()
        }
    }

    private fun resetEyesClosedDuration() {
        eyesClosedDuration = 0
    }

    private fun checkForYawn() {
        if (eyesClosedDuration > YAWN_DURATION_LIMIT) {
            if (!isYawning) {
                isYawning=true
                Log.d("Yawn Detection", "Yawn detected!")
                updateYawnStatusTextView()
                sendSignalToNodeMCU("buzzer_low")
            }
        }
    }

    private fun sendSignalToNodeMCU(signal: String) {
        val request = Request.Builder()
            .url("http://192.168.4.13/send_signal?value=$signal")
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                // Check if the response is successful
                if (response.isSuccessful) {
                    // Log the response body
                    val responseBody = response.body?.string() ?: "No response body"
                    Log.d("Network Response", "Signal sent successfully: $responseBody")
                } else {
                    // Log if the request failed
                    Log.e("Network Error", "Failed to send signal: ${response.code}")
                }
            }

            override fun onFailure(call: okhttp3.Call, e: IOException) {
                Log.e("Network Error", "Failed to send signal: $e")
            }
        })
    }


    private fun updateBlinkCountTextView() {
        blinkCountTextView.text = "Blinks: $blinkCount"
    }

    private fun updateYawnStatusTextView() {
        yawnStatusTextView.text = if (isYawning) "Yawning: Yes" else "Yawning: No"
    }

    private fun saveData() {
        val editor = sharedPreferences.edit()
        editor.putInt("blinkCount", blinkCount)
        editor.putBoolean("isYawning", isYawning)
        editor.apply() // Use apply() for async save, commit() for sync save
    }

    override fun onDestroy() {
        super.onDestroy()
        saveData()
        cameraExecutor.shutdown()
    }
}
