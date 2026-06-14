package com.loopa.telezoom

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.loopa.telezoom.databinding.ActivityMainBinding
import kotlin.math.abs

/**
 * Opens a full-screen camera preview locked to roughly 10x magnification.
 *
 * Strategy: among all openable back-facing cameras we treat the one with the
 * shortest focal length as the reference (1x). For a 10x target we pick the
 * back camera whose native optical magnification is the largest that still does
 * not overshoot 10x — i.e. the telephoto lens when one is present — and apply
 * the remaining factor as zoom ratio (or sensor crop on older devices).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraManager: CameraManager

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var chosenCamera: CameraChoice? = null
    private var previewSize: Size = Size(1920, 1080)

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
        binding.grantButton.setOnClickListener {
            requestPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        if (hasCameraPermission()) {
            showPermissionPanel(false)
            if (binding.textureView.isAvailable) {
                openCamera()
            } else {
                binding.textureView.surfaceTextureListener = surfaceListener
            }
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

    // region Permission helpers
    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun showPermissionPanel(show: Boolean) {
        binding.permissionPanel.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun tryStartPreview() {
        if (binding.textureView.isAvailable) openCamera()
        else binding.textureView.surfaceTextureListener = surfaceListener
    }
    // endregion

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) = openCamera()
        override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
        override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
        override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted stopping background thread", e)
        }
        backgroundThread = null
        backgroundHandler = null
    }

    /** Describes the camera we will open plus the zoom we will apply to it. */
    private data class CameraChoice(
        val cameraId: String,
        val characteristics: CameraCharacteristics,
        val opticalMultiplier: Float, // native magnification vs. the widest back lens
        val appliedZoomRatio: Float,  // residual zoom applied on top of the lens
        val effectiveZoom: Float,     // opticalMultiplier * appliedZoomRatio (best effort)
        val isTelephoto: Boolean
    )

    /**
     * Choose the best back-facing camera for ~10x and compute the zoom to apply.
     */
    private fun selectCamera(): CameraChoice? {
        val backCameras = mutableListOf<Triple<String, CameraCharacteristics, Float>>()
        try {
            for (id in cameraManager.cameraIdList) {
                val c = cameraManager.getCameraCharacteristics(id)
                if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
                val focals = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val focal = focals?.maxOrNull() ?: continue
                backCameras.add(Triple(id, c, focal))
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Cannot enumerate cameras", e)
            return null
        }
        if (backCameras.isEmpty()) return null

        val widestFocal = backCameras.minOf { it.third }

        // Prefer the longest lens that does not already overshoot the target; that
        // is the telephoto when present, otherwise the main camera.
        val sorted = backCameras.sortedBy { it.third }
        var pick = sorted.first()
        for (cam in sorted) {
            val optical = cam.third / widestFocal
            if (optical <= TARGET_ZOOM + 0.01f) pick = cam else break
        }

        val (id, characteristics, focal) = pick
        val optical = focal / widestFocal
        val zoomRange = zoomRatioRange(characteristics)
        val desiredResidual = TARGET_ZOOM / optical
        val applied = desiredResidual.coerceIn(zoomRange.lower, zoomRange.upper)
        val isTele = optical > 1.5f

        return CameraChoice(
            cameraId = id,
            characteristics = characteristics,
            opticalMultiplier = optical,
            appliedZoomRatio = applied,
            effectiveZoom = optical * applied,
            isTelephoto = isTele
        )
    }

    private fun zoomRatioRange(c: CameraCharacteristics): Range<Float> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { return it }
        }
        val maxDigital = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        return Range(1f, maxDigital)
    }

    private fun openCamera() {
        if (!hasCameraPermission()) return
        val choice = selectCamera()
        if (choice == null) {
            binding.infoText.text = "No back camera available"
            return
        }
        chosenCamera = choice
        previewSize = choosePreviewSize(choice.characteristics)
        configureTransform(previewSize)

        try {
            // Permission is checked above; suppress lint for the explicit guard.
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
            device.close()
            cameraDevice = null
        }

        override fun onError(device: CameraDevice, error: Int) {
            Log.e(TAG, "Camera error: $error")
            device.close()
            cameraDevice = null
        }
    }

    private fun createPreviewSession() {
        val device = cameraDevice ?: return
        val texture = binding.textureView.surfaceTexture ?: return
        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        val surface = Surface(texture)

        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(surface)
            applyZoom(builder)
            previewRequestBuilder = builder

            @Suppress("DEPRECATION")
            device.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) return
                        captureSession = session
                        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
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

    /** Apply the chosen zoom using CONTROL_ZOOM_RATIO (API 30+) or a sensor crop. */
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
        val effective = String.format("%.1f", c.effectiveZoom)
        val optical = String.format("%.1f", c.opticalMultiplier)
        val applied = String.format("%.1f", c.appliedZoomRatio)
        binding.infoText.text =
            "Lens: $lens (cam ${c.cameraId})\n" +
                "Zoom: ${effective}x  (optical ${optical}x x digital ${applied}x)\n" +
                "Target: ${TARGET_ZOOM.toInt()}x"
    }

    private fun choosePreviewSize(c: CameraCharacteristics): Size {
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return Size(1920, 1080)
        val sizes = map.getOutputSizes(SurfaceTexture::class.java) ?: return Size(1920, 1080)
        // Prefer 1080p-ish for a smooth preview without overtaxing the GPU.
        val target = 1920 * 1080
        return sizes.minByOrNull { abs(it.width * it.height - target) } ?: sizes.first()
    }

    private fun configureTransform(size: Size) {
        // The TextureView is stretched to fill the screen by the layout. A full
        // matrix transform could be added here if exact aspect preservation of
        // [size] is required, but for a fullscreen preview the simple fill is fine.
    }

    private fun closeCamera() {
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
        } catch (e: Exception) {
            Log.w(TAG, "Error closing camera", e)
        }
    }

    companion object {
        private const val TAG = "Loopa"
        private const val TARGET_ZOOM = 10f
    }
}
