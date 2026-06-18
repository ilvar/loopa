package com.loopa.telezoom

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log

/**
 * One driveable back-facing lens — either a standalone camera ID from
 * [CameraManager.getCameraIdList] or a physical sub-camera advertised by a
 * logical multi-camera. The [opticalMultiplier] is relative to a heuristic
 * "1x" focal length (see [LensRegistry.rescan]).
 */
data class LensInfo(
    val logicalId: String,
    val physicalId: String?,
    val characteristics: CameraCharacteristics,
    val focalMm: Float,
    val opticalMultiplier: Float
) {
    val key: String get() = "$logicalId|${physicalId ?: ""}"
    val label: String get() =
        if (physicalId != null) "cam $logicalId / sub $physicalId"
        else "cam $logicalId"
    val description: String get() =
        "%.1fmm  (≈%.2fx)".format(focalMm, opticalMultiplier)
}

/**
 * Discovers and remembers the set of back-facing lenses on the device.
 *
 * Cold-scan probing is expensive on devices like Motorola Edge where most
 * camera IDs are hidden — `getCameraCharacteristics` throws for every
 * non-existent ID. The result is cached in [Prefs] keyed on
 * [Build.FINGERPRINT] (so an OS / vendor update transparently invalidates it).
 * [list] returns from cache when possible; [rescan] forces a fresh probe.
 */
object LensRegistry {
    private const val TAG = "Loopa"

    /** Cached if available, otherwise a fresh [rescan]. */
    fun list(ctx: Context): List<LensInfo> = readCache(ctx) ?: rescan(ctx)

    /** Force-probe every back lens and persist the result. */
    fun rescan(ctx: Context): List<LensInfo> {
        val lenses = probe(ctx)
        writeCache(ctx, lenses)
        return lenses
    }

    fun find(ctx: Context, key: String): LensInfo? =
        list(ctx).firstOrNull { it.key == key }

    // region Cache

    private data class Seed(
        val logicalId: String,
        val physicalId: String?,
        val characteristics: CameraCharacteristics,
        val focalMm: Float
    )

    private fun readCache(ctx: Context): List<LensInfo>? {
        val raw = Prefs.lensCache(ctx) ?: return null
        val lines = raw.lines()
        if (lines.firstOrNull() != Build.FINGERPRINT) return null
        val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val seeds = mutableListOf<Seed>()
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val parts = line.split('|')
            if (parts.size < 3) return null
            val lid = parts[0]
            val pid = parts[1].ifEmpty { null }
            val focal = parts[2].toFloatOrNull() ?: return null
            val chars = try { mgr.getCameraCharacteristics(pid ?: lid) }
                        catch (e: Exception) {
                            Log.w(TAG, "Cached id ${pid ?: lid} no longer answers", e)
                            return null
                        }
            seeds.add(Seed(lid, pid, chars, focal))
        }
        if (seeds.isEmpty()) return null
        return assignOpticals(seeds)
    }

    private fun writeCache(ctx: Context, lenses: List<LensInfo>) {
        val sb = StringBuilder()
        sb.appendLine(Build.FINGERPRINT)
        for (l in lenses) {
            sb.append(l.logicalId).append('|')
            sb.append(l.physicalId ?: "").append('|')
            sb.append(l.focalMm).append('\n')
        }
        Prefs.setLensCache(ctx, sb.toString())
    }

    // endregion

    // region Probe

    /**
     * Full enumeration. Walks [CameraManager.getCameraIdList] plus the numeric
     * IDs `"0".."15"` (FreeDCam-style probe — some OEMs hide tele/ultrawide
     * from the list but still answer `getCameraCharacteristics`). For each
     * logical multi-camera, drills into `physicalCameraIds` regardless of the
     * `LOGICAL_MULTI_CAMERA` capability flag.
     *
     * The "1x reference" focal is the shortest focal in the set that is at
     * least one-third of the longest. This rejects ultrawides (typically
     * ≤ 0.3x of the main) so on a [ultrawide, main, tele] phone the main is
     * reported as 1x and the tele as ≈3x.
     */
    private fun probe(ctx: Context): List<LensInfo> {
        val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val advertised = try { mgr.cameraIdList.toList() } catch (e: Exception) {
            Log.e(TAG, "cameraIdList failed", e); emptyList()
        }
        val probeIds = LinkedHashSet<String>().apply {
            addAll(advertised)
            for (i in 0..15) add(i.toString())
        }

        val raw = mutableListOf<Triple<String, String?, CameraCharacteristics>>()
        for (id in probeIds) {
            val c = try { mgr.getCameraCharacteristics(id) } catch (e: Exception) { continue }
            if (c.get(CameraCharacteristics.LENS_FACING) !=
                CameraCharacteristics.LENS_FACING_BACK) continue

            val physicals: Set<String> =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    try { c.physicalCameraIds } catch (e: Exception) {
                        Log.w(TAG, "physicalCameraIds failed for $id", e); emptySet()
                    }
                } else emptySet()

            if (physicals.isNotEmpty()) {
                for (pid in physicals) {
                    val pc = try { mgr.getCameraCharacteristics(pid) } catch (e: Exception) {
                        Log.w(TAG, "physical chars failed for $pid", e); continue
                    }
                    raw.add(Triple(id, pid, pc))
                }
            } else {
                raw.add(Triple(id, null, c))
            }
        }

        val deduped = raw.distinctBy { (lid, pid, _) -> "$lid|${pid ?: ""}" }
        val seeds = deduped.mapNotNull { (lid, pid, ch) ->
            val f = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.firstOrNull() ?: return@mapNotNull null
            Seed(lid, pid, ch, f)
        }
        return if (seeds.isEmpty()) emptyList() else assignOpticals(seeds)
    }

    private fun assignOpticals(seeds: List<Seed>): List<LensInfo> {
        val maxFocal = seeds.maxOf { it.focalMm }
        val baseFocal = seeds
            .map { it.focalMm }
            .filter { it * 3 >= maxFocal }
            .minOrNull()
            ?: seeds.minOf { it.focalMm }
        return seeds.map {
            LensInfo(
                logicalId = it.logicalId,
                physicalId = it.physicalId,
                characteristics = it.characteristics,
                focalMm = it.focalMm,
                opticalMultiplier = it.focalMm / baseFocal
            )
        }
    }

    // endregion
}
