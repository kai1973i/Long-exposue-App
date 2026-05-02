package com.longexposure.app

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow

/**
 * Owns all CameraX / Camera2 resources and exposes a simple API for the rest of the app.
 *
 * Responsibilities:
 * - Binding [Preview], [ImageCapture], and [ImageAnalysis] use cases.
 * - Building and applying [CaptureRequestOptions] for every shooting mode.
 * - Computing effective (exposure, ISO) pairs from [CaptureSettings].
 * - Running the luminance [HistogramAnalyzer] and forwarding results via callback.
 */
@OptIn(ExperimentalCamera2Interop::class)
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val executor: ExecutorService
) {

    // ─── Constants ────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "CameraController"
        /** Preview exposure is clamped to this value so the viewfinder stays responsive. */
        const val PREVIEW_MAX_EXPOSURE_NS = 500_000_000L
        /** Target luminosity value used for auto-ISO in Shutter Priority mode. */
        private const val TARGET_LV = 8.0
        /** Minimum interval between histogram updates to avoid thrashing the UI thread. */
        private const val HISTOGRAM_UPDATE_INTERVAL_MS = 1_000L

        val EXPOSURE_TIMES_NS = longArrayOf(
            33_333_333L,      // 1/30 s
            66_666_667L,      // 1/15 s
            125_000_000L,     // 1/8  s
            250_000_000L,     // 1/4  s
            500_000_000L,     // 1/2  s
            1_000_000_000L,   // 1    s
            2_000_000_000L,   // 2    s
            4_000_000_000L,   // 4    s
            8_000_000_000L,   // 8    s
            15_000_000_000L,  // 15   s
            30_000_000_000L   // 30   s
        )

        /** One extra entry "BULB" at the end — only selectable in Manual mode. */
        val EXPOSURE_LABELS = arrayOf(
            "1/30 s", "1/15 s", "1/8 s", "1/4 s", "1/2 s",
            "1 s", "2 s", "4 s", "8 s", "15 s", "30 s", "BULB"
        )

        /** Index of the BULB entry in [EXPOSURE_LABELS]. */
        const val BULB_INDEX = 11

        val ISO_VALUES = intArrayOf(100, 200, 400, 800, 1600, 3200)

        /** Virtual f-stop presets used for Aperture Priority simulation. */
        val APERTURE_VALUES = floatArrayOf(1.8f, 2.8f, 4.0f, 5.6f, 8.0f, 11.0f)

        val WB_MODE_VALUES = arrayOf(
            WhiteBalanceMode.AUTO,
            WhiteBalanceMode.DAYLIGHT,
            WhiteBalanceMode.CLOUDY,
            WhiteBalanceMode.INCANDESCENT,
            WhiteBalanceMode.FLUORESCENT,
            WhiteBalanceMode.SHADE
        )

        val SELF_TIMER_SECONDS = intArrayOf(0, 2, 5, 10)

        /**
         * Computes the composite Exposure Value used by the EV indicator:
         * `EV = log₂(ISO / 100) + log₂(1_000_000_000 / exposureNs)`
         */
        fun computeEV(exposureNs: Long, iso: Int): Double =
            log2(iso.toDouble() / 100.0) + log2(1_000_000_000.0 / exposureNs.toDouble())

        /** Formats a duration in nanoseconds as a human-readable shutter speed string. */
        fun formatExposureNs(ns: Long): String {
            val seconds = ns / 1_000_000_000.0
            return if (seconds < 1.0) {
                "1/${(1_000_000_000.0 / ns).toInt()} s"
            } else {
                val v = ns / 1_000_000_000.0
                if (v == v.toLong().toDouble()) "${v.toLong()} s" else "%.1f s".format(v)
            }
        }
    }

    // ─── State ────────────────────────────────────────────────────────────────

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    /** Minimum focus distance in diopters reported by the sensor (0 for fixed-focus cameras). */
    private var minFocusDistDiopters: Float = 0f

    // ─── Callbacks ────────────────────────────────────────────────────────────

    /** Invoked on the main thread once the camera is bound, with the device's min focus distance. */
    var onCameraReady: ((minFocusDist: Float) -> Unit)? = null
    /** Invoked from the [executor] thread with a fresh 256-bin luminance histogram. */
    var onHistogramUpdate: ((IntArray) -> Unit)? = null
    /** Invoked on the main thread when a non-recoverable camera error occurs. */
    var onCameraError: ((String) -> Unit)? = null

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    /**
     * Obtains the [ProcessCameraProvider] and binds [Preview], [ImageCapture] and
     * [ImageAnalysis] use cases to [lifecycleOwner].
     */
    fun startCamera(surfaceProvider: Preview.SurfaceProvider) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = try {
                future.get()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get CameraProvider", e)
                onCameraError?.invoke(context.getString(R.string.no_camera_found))
                return@addListener
            }
            cameraProvider = provider

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(surfaceProvider)
            }
            val capture = ImageCapture.Builder()
                .setJpegQuality(95)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also {
                    it.setAnalyzer(executor, HistogramAnalyzer { hist ->
                        onHistogramUpdate?.invoke(hist)
                    })
                }

            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview, capture, analysis
                )
                imageCapture = capture

                minFocusDistDiopters = Camera2CameraInfo.from(camera!!.cameraInfo)
                    .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
                    ?: 0f

                onCameraReady?.invoke(minFocusDistDiopters)
            } catch (e: Exception) {
                Log.e(TAG, "Use case binding failed", e)
                onCameraError?.invoke(context.getString(R.string.no_camera_found))
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** Releases all camera resources. Call from [android.app.Activity.onDestroy]. */
    fun unbind() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        imageCapture = null
        camera = null
    }

    // ─── Settings application ─────────────────────────────────────────────────

    /**
     * Applies the currently selected settings to the live preview.
     * Exposure time is clamped to [PREVIEW_MAX_EXPOSURE_NS] to keep the viewfinder responsive.
     */
    fun applyPreviewSettings(settings: CaptureSettings) {
        val cam = camera ?: return
        applyFocusControl(settings)
        Camera2CameraControl.from(cam.cameraControl).captureRequestOptions =
            buildOptionsForMode(settings, forCapture = false)
    }

    /** Applies the full (unclamped) settings for a still capture. */
    fun applyFullSettingsForCapture(settings: CaptureSettings) {
        val cam = camera ?: return
        applyFocusControl(settings)
        Camera2CameraControl.from(cam.cameraControl).captureRequestOptions =
            buildOptionsForMode(settings, forCapture = true)
    }

    /**
     * Applies a custom [exposureNs] for Bulb mode (hold-to-expose).
     * ISO and all other parameters come from [settings].
     */
    fun applyBulbExposure(exposureNs: Long, settings: CaptureSettings) {
        val cam = camera ?: return
        applyFocusControl(settings)
        val iso = ISO_VALUES[settings.isoIndex]
        Camera2CameraControl.from(cam.cameraControl).captureRequestOptions =
            buildManualOptions(exposureNs, iso, settings)
    }

    /**
     * Applies a fully explicit override of [exposureNs] and [iso] (e.g. for AEB bracketing)
     * while keeping all other parameters from [settings].
     */
    fun applyOverrideSettings(exposureNs: Long, iso: Int, settings: CaptureSettings) {
        val cam = camera ?: return
        applyFocusControl(settings)
        Camera2CameraControl.from(cam.cameraControl).captureRequestOptions =
            buildManualOptions(exposureNs, iso, settings)
    }

    /**
     * Handles autofocus using the CameraX [androidx.camera.core.CameraControl] API.
     *
     * When [CaptureSettings.isAfEnabled] is `true`, [androidx.camera.core.CameraControl.cancelFocusAndMetering]
     * releases any tap-to-focus lock and restores the camera's default continuous-AF behaviour —
     * no Camera2 interop is needed for this path.
     *
     * When [CaptureSettings.isAfEnabled] is `false`, focus is controlled manually via
     * `CONTROL_AF_MODE_OFF` + `LENS_FOCUS_DISTANCE` in the Camera2 interop options built by
     * [buildManualOptions].
     */
    private fun applyFocusControl(settings: CaptureSettings) {
        val cam = camera ?: return
        if (settings.isAfEnabled) {
            cam.cameraControl.cancelFocusAndMetering()
        }
    }

    // ─── Capture ──────────────────────────────────────────────────────────────

    /** Fires a single still capture; results are delivered to [callback] on [executor]. */
    fun takePicture(
        outputOptions: ImageCapture.OutputFileOptions,
        callback: ImageCapture.OnImageSavedCallback
    ) {
        imageCapture?.takePicture(outputOptions, executor, callback)
    }

    // ─── Accessors ────────────────────────────────────────────────────────────

    /** Returns true after the camera has been successfully bound. */
    fun isCameraReady(): Boolean = camera != null

    /**
     * Returns the minimum focus distance the back camera supports in diopters.
     * A value of 0 means the camera is fixed-focus and focus control is unavailable.
     */
    fun getMinFocusDist(): Float = minFocusDistDiopters

    // ─── Effective parameter computation ──────────────────────────────────────

    /**
     * Computes the actual (exposureNs, ISO) pair that will be used for a capture,
     * taking the active [ShootingMode] into account.
     *
     * - **PROGRAM**: returns a representative pair (1 s, ISO 100); AE is auto.
     * - **MANUAL**: returns the user-selected values directly.
     * - **Tv**: fixes the selected exposure, picks ISO to target [TARGET_LV].
     * - **Av**: fixes the selected ISO, adjusts exposure for the virtual aperture.
     */
    fun computeEffectiveParams(settings: CaptureSettings): Pair<Long, Int> {
        val baseExposureNs = safeExposureNs(settings.exposureIndex)
        val baseIso = ISO_VALUES[settings.isoIndex]

        return when (settings.shootingMode) {
            ShootingMode.MANUAL, ShootingMode.PROGRAM ->
                Pair(baseExposureNs, baseIso)

            ShootingMode.SHUTTER_PRIORITY -> {
                val autoIso = computeAutoIso(baseExposureNs)
                Pair(baseExposureNs, autoIso)
            }

            ShootingMode.APERTURE_PRIORITY -> {
                val refAperture = APERTURE_VALUES[0]
                val selAperture = APERTURE_VALUES[settings.virtualApertureIndex]
                val ratio = (selAperture / refAperture).toDouble().pow(2.0)
                val adjustedNs = (baseExposureNs * ratio).toLong()
                    .coerceIn(EXPOSURE_TIMES_NS.first(), 30_000_000_000L)
                Pair(adjustedNs, baseIso)
            }
        }
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    /**
     * Returns [EXPOSURE_TIMES_NS] at [index], or a safe default for BULB (index 11)
     * which has no corresponding entry in that array.
     */
    private fun safeExposureNs(index: Int): Long =
        if (index < EXPOSURE_TIMES_NS.size) EXPOSURE_TIMES_NS[index]
        else EXPOSURE_TIMES_NS.last()

    private fun buildOptionsForMode(
        settings: CaptureSettings,
        forCapture: Boolean
    ): CaptureRequestOptions {
        if (settings.shootingMode == ShootingMode.PROGRAM) {
            return buildProgramOptions(settings)
        }
        val (exposureNs, iso) = computeEffectiveParams(settings)
        val finalNs = if (forCapture) exposureNs else minOf(exposureNs, PREVIEW_MAX_EXPOSURE_NS)
        return buildManualOptions(finalNs, iso, settings)
    }

    private fun buildManualOptions(
        exposureNs: Long,
        iso: Int,
        settings: CaptureSettings
    ): CaptureRequestOptions {
        val builder = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF
            )
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF
            )
            .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
            .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
            .setCaptureRequestOption(
                CaptureRequest.SENSOR_FRAME_DURATION, exposureNs + 1_000_000L
            )
            .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, settings.wbMode.awbValue)
        // Manual focus: let Camera2 interop lock AF off and set focus distance.
        // When AF is enabled, focus is handled by CameraX CameraControl (cancelFocusAndMetering),
        // so no AF-related Camera2 options are needed.
        if (!settings.isAfEnabled) {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF
            )
            builder.setCaptureRequestOption(
                CaptureRequest.LENS_FOCUS_DISTANCE, settings.focusDistance
            )
        }
        return builder.build()
    }

    private fun buildProgramOptions(settings: CaptureSettings): CaptureRequestOptions =
        CaptureRequestOptions.Builder()
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON
            )
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO
            )
            .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, settings.wbMode.awbValue)
            // AF is handled by CameraX CameraControl (cancelFocusAndMetering in applyFocusControl).
            .build()

    /**
     * Picks the ISO from [ISO_VALUES] that comes closest to the ideal ISO needed
     * to achieve [TARGET_LV] luminosity value for the given [exposureNs].
     *
     * Formula: LV = log₂(ISO/100) + log₂(1e9/exposureNs)
     * → ISO = 100 × 2^(TARGET_LV − log₂(1e9/exposureNs))
     */
    private fun computeAutoIso(exposureNs: Long): Int {
        val exposureLv = log2(1_000_000_000.0 / exposureNs.toDouble())
        val idealIso = 100.0 * 2.0.pow(TARGET_LV - exposureLv)
        return ISO_VALUES.minByOrNull { abs(it - idealIso) } ?: ISO_VALUES[2]
    }

    // ─── Histogram analyzer ───────────────────────────────────────────────────

    /** Computes a 256-bin luminance histogram from the Y plane of a YUV frame. */
    private class HistogramAnalyzer(
        private val onHistogram: (IntArray) -> Unit
    ) : ImageAnalysis.Analyzer {

        private var lastUpdateMs = 0L

        override fun analyze(image: ImageProxy) {
            try {
                val now = System.currentTimeMillis()
                // Throttle to at most once per HISTOGRAM_UPDATE_INTERVAL_MS to avoid
                // excessive Bitmap allocations and UI-thread posting overhead.
                if (now - lastUpdateMs < HISTOGRAM_UPDATE_INTERVAL_MS) return
                lastUpdateMs = now

                val histogram = IntArray(256)
                val buffer = image.planes[0].buffer  // Y plane (luminance)
                val data = ByteArray(buffer.remaining())
                buffer.get(data)
                // Sample every 4th pixel: retains enough statistical accuracy for a
                // smooth histogram while reducing per-frame work by 4×.
                var i = 0
                while (i < data.size) {
                    histogram[data[i].toInt() and 0xFF]++
                    i += 4
                }
                onHistogram(histogram)
            } finally {
                image.close()
            }
        }
    }
}
