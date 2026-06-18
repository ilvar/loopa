package com.loopa.telezoom

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraExtensionCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import kotlin.math.abs

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val ocrSwitch = findViewById<SwitchCompat>(R.id.ocrSwitch)
        ocrSwitch.isChecked = Prefs.ocrEnabled(this)
        ocrSwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setOcrEnabled(this, checked)
        }

        val zoomGroup = findViewById<RadioGroup>(R.id.lensGroup)
        val levels = ZoomOptions.list(this)
        val current = Prefs.targetZoom(this)
        // Whichever offered step is nearest the saved zoom is the active one,
        // so exactly one radio is always checked even after a rescan changes
        // the available steps.
        val activeLevel = levels.minByOrNull { abs(it.zoom - current) }

        findViewById<Button>(R.id.rescanButton).setOnClickListener {
            LensRegistry.rescan(this)
            Toast.makeText(this, R.string.settings_rescan_done, Toast.LENGTH_SHORT).show()
            recreate()
        }

        for (level in levels) {
            addZoomRadio(
                zoomGroup,
                zoom = level.zoom,
                title = level.label,
                subtitle = getString(
                    if (level.native) R.string.settings_zoom_optical
                    else R.string.settings_zoom_digital
                ),
                checked = level === activeLevel
            )
        }

        zoomGroup.setOnCheckedChangeListener { _, checkedId ->
            val zoom = findViewById<RadioButton>(checkedId)?.tag as? Float
                ?: return@setOnCheckedChangeListener
            Prefs.setTargetZoom(this, zoom)
        }

        val debug = cameraDiagnostics()
        findViewById<TextView>(R.id.debugText).text = debug
        findViewById<Button>(R.id.copyDebugButton).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Loopa camera diagnostics", debug))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun addZoomRadio(
        group: RadioGroup,
        zoom: Float,
        title: String,
        subtitle: String,
        checked: Boolean
    ) {
        val rb = RadioButton(this).apply {
            id = View.generateViewId()
            tag = zoom
            text = "$title\n$subtitle"
            setTextColor(getColor(android.R.color.white))
            textSize = 14f
            isChecked = checked
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        group.addView(
            rb,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }

    /**
     * Dump every entry in [CameraManager.getCameraIdList] plus, on API 28+,
     * physical sub-camera IDs — regardless of whether the parent advertises
     * the LOGICAL_MULTI_CAMERA capability — so we can see exactly what the
     * device exposes.
     */
    private fun cameraDiagnostics(): String {
        val mgr = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val sb = StringBuilder()
        sb.appendLine("SDK ${Build.VERSION.SDK_INT}  ${Build.MANUFACTURER} ${Build.MODEL}")
        val advertised = try { mgr.cameraIdList.toList() } catch (e: Exception) {
            return sb.append("cameraIdList failed: $e").toString()
        }
        sb.appendLine("cameraIdList = $advertised")
        val probeIds = LinkedHashSet<String>().apply {
            addAll(advertised)
            for (i in 0..15) add(i.toString())
        }

        for (id in probeIds) {
            val c = try { mgr.getCameraCharacteristics(id) } catch (e: Exception) { null }
                ?: continue
            sb.appendLine()
            val tag = if (id in advertised) "advertised" else "HIDDEN"
            sb.appendLine("[cam $id] ($tag)")
            val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
                else -> "?"
            }
            sb.appendLine("  facing: $facing")
            val focals = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            sb.appendLine("  focals(mm): ${focals?.toList()}")
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            sb.appendLine("  caps: ${caps.joinToString { capabilityName(it) }}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                sb.appendLine("  zoomRatioRange: " +
                    "${c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)}")
            }
            sb.appendLine("  maxDigitalZoom: " +
                "${c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val phys = try { c.physicalCameraIds } catch (e: Exception) {
                    sb.appendLine("  physicalCameraIds failed: $e"); emptySet()
                }
                sb.appendLine("  physicalCameraIds: $phys")
                for (pid in phys) {
                    val pc = try { mgr.getCameraCharacteristics(pid) } catch (e: Exception) {
                        sb.appendLine("    [sub $pid] chars failed: $e"); continue
                    }
                    val pf = pc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    sb.appendLine("    [sub $pid] focals(mm)=${pf?.toList()}")
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    val ext = mgr.getCameraExtensionCharacteristics(id)
                    val supported = ext.supportedExtensions
                    sb.appendLine("  extensions: ${supported.map { extName(it) }}")
                    for (e in supported) {
                        val sizes = try {
                            ext.getExtensionSupportedSizes(e, SurfaceTexture::class.java)
                        } catch (ex: Exception) { emptyList() }
                        sb.appendLine("    [ext ${extName(e)}] previewSizes=${sizes.size}")
                    }
                } catch (e: Exception) {
                    sb.appendLine("  extensions failed: $e")
                }
            }
        }
        return sb.toString()
    }

    private fun extName(v: Int): String = when (v) {
        CameraExtensionCharacteristics.EXTENSION_AUTOMATIC -> "AUTOMATIC"
        CameraExtensionCharacteristics.EXTENSION_BOKEH -> "BOKEH"
        CameraExtensionCharacteristics.EXTENSION_HDR -> "HDR"
        CameraExtensionCharacteristics.EXTENSION_NIGHT -> "NIGHT"
        CameraExtensionCharacteristics.EXTENSION_FACE_RETOUCH -> "FACE_RETOUCH"
        else -> "ext$v"
    }

    private fun capabilityName(v: Int): String = when (v) {
        0 -> "BACKWARD_COMPAT"
        1 -> "MANUAL_SENSOR"
        2 -> "MANUAL_POST_PROC"
        3 -> "RAW"
        4 -> "PRIV_REPROC"
        5 -> "READ_SENSOR"
        6 -> "BURST"
        7 -> "YUV_REPROC"
        8 -> "DEPTH"
        9 -> "HIGH_SPEED"
        10 -> "MOTION_TRACK"
        11 -> "LOGICAL_MULTI_CAM"
        12 -> "MONOCHROME"
        13 -> "SECURE"
        14 -> "SYSTEM_CAM"
        15 -> "OFFLINE_PROC"
        16 -> "ULTRA_HIGH_RES"
        17 -> "REMOSAIC_REPROC"
        18 -> "10BIT"
        19 -> "STREAM_USE_CASE"
        20 -> "COLOR_SPACE_PROFILES"
        else -> "cap$v"
    }
}
