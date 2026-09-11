package com.ilvar.lookingglass

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.ilvar.lookingglass.databinding.ActivityMainBinding
import java.util.concurrent.Executor
import kotlin.math.abs

/**
 * Full-screen camera preview locked to the user's selected magnification
 * (default ~10x), using the telephoto lens when one is available.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraManager: CameraManager

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var isOpeningCamera = false

    private var chosenCamera: CameraChoice? = null
    private var previewSize: Size = Size(1920, 1080)

    companion object {
        private const val TAG = "LookingGlass"
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

        binding.grantButton.setOnClickListener {
            requestPermission.launch(Manifest.permission.CAMERA)
        }
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        // Tap on the preview hands off to the system default camera app.
        // The intent has no zoom extra — the user must pinch to their target
        // zoom (no Android-standard way to preset zoom across OEMs).
        binding.textureView.setOnClickListener { launchStockCamera() }
    }

    private fun launchStockCamera() {
        val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(packageManager) == null) {
            Toast.makeText(this, R.string.no_stock_camera, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, R.string.pinch_to_zoom_hint, Toast.LENGTH_LONG).show()
        startActivity(intent)
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

    /**
     * Describes the camera we will open plus the zoom we will apply.
     * [physicalId] is non-null when [logicalId] is a multi-camera and we want
     * to route output streams to a specific physical sub-camera (e.g. the
     * telephoto lens) rather than letting the OS pick.
     */
    private data class CameraChoice(
        val logicalId: String,
        val physicalId: String?,
        val characteristics: CameraCharacteristics,
        val opticalMultiplier: Float,
        val appliedZoomRatio: Float,
        val effectiveZoom: Float,
        val isTelephoto: Boolean
    )

    /**
     * Build a [CameraChoice] for the user's selected target zoom: pick the
     * longest lens that does not overshoot the target, then crop (digital zoom)
     * to reach it. If every lens overshoots (target below the widest lens) we
     * fall back to the widest lens.
     */
    private fun selectCamera(): CameraChoice? {
        val lenses = LensRegistry.list(this)
        if (lenses.isEmpty()) return null

        val target = Prefs.targetZoom(this)
        val pick: LensInfo = lenses
            .filter { it.opticalMultiplier <= target + 0.05f }
            .maxByOrNull { it.opticalMultiplier }
            ?: lenses.minByOrNull { it.opticalMultiplier }
            ?: return null

        val optical = pick.opticalMultiplier
        val zoomRange = zoomRatioRange(pick.characteristics)
        val applied = (target / optical).coerceIn(zoomRange.lower, zoomRange.upper)

        return CameraChoice(
            logicalId = pick.logicalId,
            physicalId = pick.physicalId,
            characteristics = pick.characteristics,
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
        // Guard against a re-entrant open. On first launch the permission grant
        // re-runs onResume *and* fires the result callback, so without this both
        // paths race to open the same camera and tear each other down.
        if (isOpeningCamera || cameraDevice != null) return
        val choice = selectCamera()
        if (choice == null) {
            binding.infoText.text = "No back camera available"
            return
        }
        chosenCamera = choice
        previewSize = choosePreviewSize(choice.characteristics)

        try {
            isOpeningCamera = true
            cameraManager.openCamera(choice.logicalId, stateCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            isOpeningCamera = false
            Log.e(TAG, "openCamera failed", e)
            fallbackToSafeZoom("open failed: ${e.message}")
        } catch (e: SecurityException) {
            isOpeningCamera = false
            Log.e(TAG, "Missing camera permission", e)
        } catch (e: IllegalArgumentException) {
            isOpeningCamera = false
            // Probed (hidden) IDs can throw IAE for "unknown camera ID".
            Log.e(TAG, "openCamera rejected hidden id ${choice.logicalId}", e)
            fallbackToSafeZoom("hidden id rejected")
        }
    }

    /**
     * Hidden / probed camera IDs sometimes pass [CameraManager.getCameraCharacteristics]
     * but fail to open. When that happens (or any other open error fires) drop
     * the target zoom to the widest lens (always openable) and reopen so the
     * app stays alive. No-op if we are already on the widest lens.
     */
    private fun fallbackToSafeZoom(reason: String) {
        val widest = LensRegistry.list(this)
            .minByOrNull { it.opticalMultiplier }?.opticalMultiplier ?: return
        if (Prefs.targetZoom(this) <= widest + 0.05f) return
        Log.w(TAG, "Falling back to ${widest}x: $reason")
        Prefs.setTargetZoom(this, widest)
        runOnUiThread {
            Toast.makeText(
                this,
                getString(R.string.lens_unavailable_fallback),
                Toast.LENGTH_LONG
            ).show()
            openCamera()
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(device: CameraDevice) {
            isOpeningCamera = false
            cameraDevice = device
            createPreviewSession()
        }
        override fun onDisconnected(device: CameraDevice) {
            isOpeningCamera = false
            device.close(); cameraDevice = null
        }
        override fun onError(device: CameraDevice, error: Int) {
            isOpeningCamera = false
            Log.e(TAG, "Camera error: $error"); device.close(); cameraDevice = null
            fallbackToSafeZoom("error $error")
        }
    }

    private fun createPreviewSession() {
        val device = cameraDevice ?: return
        val choice = chosenCamera ?: return
        val texture = binding.textureView.surfaceTexture ?: return
        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        val previewSurface = Surface(texture)

        val sessionCallback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                if (cameraDevice == null) return
                captureSession = session
                val builder = previewRequestBuilder ?: return
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
        }

        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(previewSurface)
            applyZoom(builder)
            previewRequestBuilder = builder

            val surfaces = listOf(previewSurface)

            if (choice.physicalId != null &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // Multi-camera: route every surface to the chosen physical sub-lens.
                val outputs = surfaces.map { s ->
                    OutputConfiguration(s).apply { setPhysicalCameraId(choice.physicalId) }
                }
                val handler = backgroundHandler!!
                val executor = Executor { handler.post(it) }
                val sessionConfig = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputs,
                    executor,
                    sessionCallback
                )
                device.createCaptureSession(sessionConfig)
            } else {
                @Suppress("DEPRECATION")
                device.createCaptureSession(surfaces, sessionCallback, backgroundHandler)
            }
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
        val camLabel = if (c.physicalId != null) "${c.logicalId}/${c.physicalId}"
                       else c.logicalId
        binding.infoText.text =
            "Lens: $lens (cam $camLabel)\n" +
            "Zoom: ${effective}x  (opt ${optical}x × dig ${applied}x)"
    }

    private fun closeCamera() {
        try {
            isOpeningCamera = false
            captureSession?.close(); captureSession = null
            cameraDevice?.close(); cameraDevice = null
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

    // endregion
}
