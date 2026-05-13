package com.surendramaran.yolov8tflite

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.surendramaran.yolov8tflite.Constants.LABELS_PATH
import com.surendramaran.yolov8tflite.Constants.MODEL_PATH
import com.surendramaran.yolov8tflite.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.speech.tts.TextToSpeech
import java.util.Locale

class MainActivity : AppCompatActivity(), Detector.DetectorListener, TextToSpeech.OnInitListener {
    private lateinit var tts: TextToSpeech
    private lateinit var binding: ActivityMainBinding
    private val isFrontCamera = false
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var detector: Detector
    private var lastSeenMap = mutableMapOf<String, Long>()
    private var lastSpeakTime = 0L

    private lateinit var cameraExecutor: ExecutorService
    private val excludedLabels = setOf("airplane",
        "dog", "bicycle","sheep", "cow", "elephant"
        , "bear", "zebra", "giraffe","kite","banana"
        , "apple","sandwich","orange","broccoli"
        ,"carrot","hot dog","pizza","donut","cake"
        ,"teddy bear")
    private val importantLabels = setOf("person",
        "bicycle", "car", "motorcycle", "bus",
        "train", "truck", "traffic light",
        "fire hydrant", "stop sign", "parking meter"
        , "bench", "cat", "dog", "skateboard",
        "chair", "couch", "potted plant", "bed"
        , "dining table", "oven", "sink", "refrigerator"
        , "vase", "door", "wall")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        detector = Detector(baseContext, MODEL_PATH, LABELS_PATH, this)
        detector.setup()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider  = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: throw IllegalStateException("Camera initialization failed.")

        val rotation = binding.viewFinder.display?.rotation ?: android.view.Surface.ROTATION_0

        val cameraSelector = CameraSelector
            .Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        preview =  Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(rotation)
            .build()

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(rotation)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        imageAnalyzer?.setAnalyzer(cameraExecutor) { imageProxy ->
            val bitmapBuffer =
                Bitmap.createBitmap(
                    imageProxy.width,
                    imageProxy.height,
                    Bitmap.Config.ARGB_8888
                )
            imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }
            imageProxy.close()

            val matrix = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())

                if (isFrontCamera) {
                    postScale(
                        -1f,
                        1f,
                        imageProxy.width.toFloat(),
                        imageProxy.height.toFloat()
                    )
                }
            }

            val rotatedBitmap = Bitmap.createBitmap(
                bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height,
                matrix, true
            )

            detector.detect(rotatedBitmap)
        }

        cameraProvider.unbindAll()

        try {
            camera = cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalyzer
            )

            preview?.setSurfaceProvider(binding.viewFinder.surfaceProvider)
        } catch(exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) {
        if (it[Manifest.permission.CAMERA] == true) { startCamera() }
    }

    override fun onDestroy() {
        super.onDestroy()
        tts.stop()
        tts.shutdown()
        detector.clear()
        cameraExecutor.shutdown()
    }

    override fun onResume() {
        super.onResume()
        if (allPermissionsGranted()){
            startCamera()
        } else {
            requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    companion object {
        private const val TAG = "Camera"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = mutableListOf (
            Manifest.permission.CAMERA
        ).toTypedArray()
    }

    override fun onEmptyDetect() {
        binding.overlay.invalidate()
    }

    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            if (!::tts.isInitialized) return@runOnUiThread

            val currentTime = System.currentTimeMillis()
            val currentObjects = boundingBoxes.filter{ it.clsName !in excludedLabels }.map { box ->
                var position = ""
                val isClose = box.cy >= 0.75

                if (box.cy < 0.25) {
                    position += "far"
                }
                else if (box.cy < 0.75){
                    position += "ahead"
                }
                else{
                    position += "close"
                }
                if (box.cx < 0.25) {
                    position += " on the left"
                }
                else if (box.cx < 0.75){
                    position += " in the middle"
                }
                else{
                    position += " on the right"
                }

                val name = "${box.clsName} $position"
                if (isClose && box.clsName in importantLabels) {
                    "Careful! $name"
                } else {
                    name
                }
            }.toSet()

            //Remove objects from memory that haven't been seen for more than 2 seconds
            val iterator = lastSeenMap.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (currentTime - entry.value > 2000L) {
                    iterator.remove()
                }
            }

            //Identify New objects
            val newObjects = currentObjects.filter { it !in lastSeenMap }

            //Update memory with current objects and current time
            currentObjects.forEach { lastSeenMap[it] = currentTime }

            //Speak only the actually new objects
            if (newObjects.isNotEmpty()) {
                val alerts = newObjects.filter { it.startsWith("Careful") }
                val normal = newObjects.filter { !it.startsWith("Careful") }

                if (alerts.isNotEmpty()) {
                    // Alert objects use QUEUE_ADD to ensure they are never skipped
                    val alertText = alerts.joinToString(", ")
                    tts.speak(alertText, TextToSpeech.QUEUE_ADD, null, null)
                    lastSpeakTime = currentTime
                }

                if (normal.isNotEmpty() && currentTime - lastSpeakTime > 1500L) {
                    // Normal objects use QUEUE_FLUSH to stay real-time
                    val speechText = normal.joinToString(", ")
                    tts.speak(speechText, TextToSpeech.QUEUE_FLUSH, null, null)
                    lastSpeakTime = currentTime
                }
            }

            // 5. Update UI
            binding.inferenceTime.text = "${inferenceTime}ms"
            binding.overlay.apply {
                setResults(boundingBoxes)
                invalidate()
            }
        }
    }
}
