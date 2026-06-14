package com.loopa.telezoom

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.loopa.telezoom.databinding.ActivityMainBinding
import kotlin.math.abs

/**
 * Full-screen camera preview locked to ~10x magnification, using the telephoto
 * lens when available. Also runs on-device ML Kit OCR at ~2fps; any digit
 * sequences whose bounding box occupies less than 5% of the frame are enlarged
 * and displayed at the bottom of the screen.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraManager: CameraManager
    private lateinit var textRecognizer: TextRecognizer

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var imageReader: ImageReader? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var chosenCamera: CameraChoice? = null
    private var previewSize: Size = Size(1920, 1080)
    private var analysisSize: Size = Size(640, 480)

    // Degrees the sensor image must be rotated to match the portrait display orientation.
    // Computed once per camera open and used when creating InputImage for ML Kit.
    private var sensorRotation = 0

    private var lastAnalysisTime = 0L

    companion object {
        private const val TAG = "Loopa"
        private const val TARGET_ZOOM = 10f
        private const val ANALYSIS_INTERVAL_MS = 500L
        // Bounding-box-area / frame-area threshold for "small" numbers.
        private const val SMALL_THRESHOLD = 0.05f
        private val DIGIT_REGEX = Regex("""\d+""")
    }

    // region Lifecycle

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                showPermissionPanel(false)
                tryStartPreview()
            } else {
                binding.infoText.text = getString(R.string.camera_permission_denied)
                showPermissionPanel(true)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        binding.grantButton.setOnClickListener {
            requestPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        if (hasCameraPermission()) {
            showPermissionPanel(false)
            if (binding.textureView.isAvailable) openCamera()
            else binding.textureView.surfaceTextureListener = surfaceListener
        } else {
            showPermissionPanel(true)
            requestPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    override fun onDestroy() {
        textRecognizer.close()
        super.onDestroy()
    }

    // endregion

    // region Permission helpers

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun showPermissionPanel(show: Boolean) {
        binding.permissionPanel.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun tryStartPreview() {
        if (binding.textureView.isAvailable) openCamera()
        else binding.textureView.surfaceTextureListener = surfaceListener
    }

    // endregion

    // region TextureView listener

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) = openCamera()
        override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
        override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
        override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
    }

    // endregion

    // region Background thread

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try { backgroundThread?.join() } catch (e: InterruptedException) { /* ignore */ }
        backgroundThread = null
        backgroundHandler = null
    }

    // endregion

    // region Camera selection

    /** Describes the camera we will open plus the zoom we will apply to it. */
    private data class CameraChoice(
        val cameraId: String,
        val characteristics: CameraCharacteristics,
        val opticalMultiplier: Float,
        val appliedZoomRatio: Float,
        val effectiveZoom: Float,
        val isTelephoto: Boolean
    )

    /**
     * Among all openable back-facing cameras, treat the one with the shortest
     * focal length as 1x and pick the lens whose native magnification is the
     * largest that does not overshoot 10x (i.e. the telephoto when one exists).
     */
    private fun selectCamera(): CameraChoice? {
        val backCameras = mutableListOf<Triple<String, CameraCharacteristics, Float>>()
        try {
            for (id in cameraManager.cameraIdList) {
                val c = cameraManager.getCameraCharacteristics(id)
                if (c.get(CameraCharacteristics.LENS_FACING) !=
                    CameraCharacteristics.LENS_FACING_BACK) continue
                val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.maxOrNull() ?: continue
                backCameras.add(Triple(id, c, focal))
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Cannot enumerate cameras", e)
            return null
        }
        if (backCameras.isEmpty()) return null

        val widestFocal = backCameras.minOf { it.third }
        val sorted = backCameras.sortedBy { it.third }
        var pick = sorted.first()
        for (cam in sorted) {
            if (cam.third / widestFocal <= TARGET_ZOOM + 0.01f) pick = cam else break
        }

        val (id, characteristics, focal) = pick
        val optical = focal / widestFocal
        val zoomRange = zoomRatioRange(characteristics)
        val applied = (TARGET_ZOOM / optical).coerceIn(zoomRange.lower, zoomRange.upper)

        return CameraChoice(
            cameraId = id,
            characteristics = characteristics,
            opticalMultiplier = optical,
            appliedZoomRatio = applied,
            effectiveZoom = optical * applied,
            isTelephoto = optical > 1.5f
        )
    }

    private fun zoomRatioRange(c: CameraCharacteristics): Range<Float> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { return it }
        }
        val maxDigital = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        return Range(1f, maxDigital)
    }

    // endregion

    // region Camera open / session

    private fun openCamera() {
        if (!hasCameraPermission()) return
        val choice = selectCamera()
        if (choice == null) {
            binding.infoText.text = "No back camera available"
            return
        }
        chosenCamera = choice
        previewSize = choosePreviewSize(choice.characteristics)
        analysisSize = chooseAnalysisSize(choice.characteristics)
        sensorRotation = choice.characteristics
            .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

        try {
            cameraManager.openCamera(choice.cameraId, stateCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "openCamera failed", e)
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing camera permission", e)
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(device: CameraDevice) {
            cameraDevice = device
            createPreviewSession()
        }
        override fun onDisconnected(device: CameraDevice) {
            device.close(); cameraDevice = null
        }
        override fun onError(device: CameraDevice, error: Int) {
            Log.e(TAG, "Camera error: $error"); device.close(); cameraDevice = null
        }
    }

    private fun createPreviewSession() {
        val device = cameraDevice ?: return
        val texture = binding.textureView.surfaceTexture ?: return
        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        val previewSurface = Surface(texture)

        // Create a second output surface for frame-by-frame OCR analysis.
        imageReader?.close()
        imageReader = ImageReader.newInstance(
            analysisSize.width, analysisSize.height, ImageFormat.YUV_420_888, 2
        ).also { it.setOnImageAvailableListener(imageAnalysisListener, backgroundHandler) }
        val analysisSurface = imageReader!!.surface

        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(previewSurface)
            builder.addTarget(analysisSurface)
            applyZoom(builder)
            previewRequestBuilder = builder

            @Suppress("DEPRECATION")
            device.createCaptureSession(
                listOf(previewSurface, analysisSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) return
                        captureSession = session
                        builder.set(
                            CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                        )
                        session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                        runOnUiThread { updateInfoOverlay() }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Capture session configuration failed")
                    }
                },
                backgroundHandler
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "createPreviewSession failed", e)
        }
    }

    private fun applyZoom(builder: CaptureRequest.Builder) {
        val choice = chosenCamera ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, choice.appliedZoomRatio)
        } else {
            val active = choice.characteristics
                .get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
            val ratio = choice.appliedZoomRatio.coerceAtLeast(1f)
            val cropW = (active.width() / ratio).toInt()
            val cropH = (active.height() / ratio).toInt()
            val left = (active.width() - cropW) / 2
            val top = (active.height() - cropH) / 2
            builder.set(
                CaptureRequest.SCALER_CROP_REGION,
                android.graphics.Rect(left, top, left + cropW, top + cropH)
            )
        }
    }

    private fun updateInfoOverlay() {
        val c = chosenCamera ?: return
        val lens = if (c.isTelephoto) "TELE" else "MAIN"
        val effective = "%.1f".format(c.effectiveZoom)
        val optical = "%.1f".format(c.opticalMultiplier)
        val applied = "%.1f".format(c.appliedZoomRatio)
        binding.infoText.text =
            "Lens: $lens (cam ${c.cameraId})\n" +
            "Zoom: ${effective}x  (optical ${optical}x × digital ${applied}x)\n" +
            "Target: ${TARGET_ZOOM.toInt()}x"
    }

    private fun closeCamera() {
        try {
            captureSession?.close(); captureSession = null
            cameraDevice?.close(); cameraDevice = null
            imageReader?.close(); imageReader = null
        } catch (e: Exception) {
            Log.w(TAG, "Error closing camera", e)
        }
    }

    // endregion

    // region Size helpers

    private fun choosePreviewSize(c: CameraCharacteristics): Size {
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return Size(1920, 1080)
        val sizes = map.getOutputSizes(SurfaceTexture::class.java) ?: return Size(1920, 1080)
        val target = 1920 * 1080
        return sizes.minByOrNull { abs(it.width * it.height - target) } ?: sizes.first()
    }

    /**
     * Pick the largest YUV_420_888 size that fits within 640×480.
     * This guarantees compatibility with simultaneous PREVIEW output (Camera2
     * mandatory stream table), and is large enough for reliable OCR while keeping
     * analysis cheap.
     */
    private fun chooseAnalysisSize(c: CameraCharacteristics): Size {
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return Size(640, 480)
        val sizes = map.getOutputSizes(ImageFormat.YUV_420_888) ?: return Size(640, 480)
        return sizes
            .filter { it.width <= 640 && it.height <= 480 }
            .maxByOrNull { it.width * it.height }
            ?: sizes.minByOrNull { it.width * it.height }
            ?: Size(640, 480)
    }

    // endregion

    // region OCR / digit recognition

    /**
     * Throttled frame consumer. Acquires the latest frame, skips it if the last
     * analysis was within ANALYSIS_INTERVAL_MS, otherwise hands it to ML Kit.
     * The image must stay open until ML Kit finishes — it is closed in both the
     * success and failure callbacks.
     */
    private val imageAnalysisListener = ImageReader.OnImageAvailableListener { reader ->
        val image = reader.acquireLatestImage() ?: return@OnImageAvailableListener
        val now = SystemClock.elapsedRealtime()
        if (now - lastAnalysisTime < ANALYSIS_INTERVAL_MS) {
            image.close()
            return@OnImageAvailableListener
        }
        lastAnalysisTime = now

        try {
            val inputImage = InputImage.fromMediaImage(image, sensorRotation)
            textRecognizer.process(inputImage)
                .addOnSuccessListener { result ->
                    processOcrResult(result, analysisSize.width, analysisSize.height)
                    image.close()
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "OCR failed", e)
                    image.close()
                }
        } catch (e: Exception) {
            Log.e(TAG, "InputImage creation failed", e)
            image.close()
        }
    }

    /**
     * For each recognized text block, extract digit sequences and check whether
     * the bounding box occupies less than [SMALL_THRESHOLD] of the frame area.
     * If so, collect those digits for display at the bottom of the screen.
     *
     * We check at the TextBlock level so that a multi-digit number that spans
     * one block is treated as a single entity for the area test.
     */
    private fun processOcrResult(
        result: com.google.mlkit.vision.text.Text,
        frameWidth: Int,
        frameHeight: Int
    ) {
        val frameArea = frameWidth * frameHeight
        val smallDigits = mutableListOf<String>()

        for (block in result.textBlocks) {
            val bbox = block.boundingBox ?: continue
            val digits = DIGIT_REGEX.findAll(block.text).joinToString(" ") { it.value }
            if (digits.isBlank()) continue

            val bboxArea = bbox.width() * bbox.height()
            val ratio = bboxArea.toFloat() / frameArea
            Log.v(TAG, "Block \"${block.text}\" bbox=$bboxArea frame=$frameArea ratio=${"%.3f".format(ratio)}")

            if (ratio < SMALL_THRESHOLD) {
                smallDigits.add(digits)
            }
        }

        val displayText = smallDigits.joinToString("   ")
        runOnUiThread {
            if (displayText.isNotEmpty()) {
                binding.digitsText.text = displayText
                binding.digitsText.visibility = View.VISIBLE
            } else {
                binding.digitsText.visibility = View.GONE
            }
        }
    }

    // endregion
}
