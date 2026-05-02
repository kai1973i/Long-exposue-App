package com.longexposure.app

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.longexposure.app.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@OptIn(ExperimentalCamera2Interop::class)
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LongExposureApp"
        private const val REQUEST_CAMERA_PERMISSION = 100

        // Exposure time presets in nanoseconds
        private val EXPOSURE_TIMES_NS = longArrayOf(
            33_333_333L,         // 1/30 s
            66_666_667L,         // 1/15 s
            125_000_000L,        // 1/8 s
            250_000_000L,        // 1/4 s
            500_000_000L,        // 1/2 s
            1_000_000_000L,      // 1 s
            2_000_000_000L,      // 2 s
            4_000_000_000L,      // 4 s
            8_000_000_000L,      // 8 s
            15_000_000_000L,     // 15 s
            30_000_000_000L      // 30 s
        )

        private val EXPOSURE_LABELS = arrayOf(
            "1/30 s", "1/15 s", "1/8 s", "1/4 s", "1/2 s",
            "1 s", "2 s", "4 s", "8 s", "15 s", "30 s"
        )

        // ISO presets
        private val ISO_VALUES = intArrayOf(100, 200, 400, 800, 1600, 3200)
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null

    private var selectedExposureIndex = 5  // default: 1 s
    private var selectedIsoIndex = 0        // default: ISO 100
    private var isCapturing = false

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        setupExposureSeekBar()
        setupIsoSeekBar()
        setupCaptureButton()

        if (hasCameraPermission()) {
            startCamera()
        } else {
            requestCameraPermission()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Unbind camera first so any in-flight captures are cancelled before
        // the executor that handles their callbacks is shut down
        cameraProvider?.unbindAll()
        cameraProvider = null
        imageCapture = null
        camera = null
        cameraExecutor.shutdown()
    }

    // ─── Permissions ──────────────────────────────────────────────────────────

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            REQUEST_CAMERA_PERMISSION
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                Toast.makeText(this, R.string.camera_permission_required, Toast.LENGTH_LONG).show()
            }
        }
    }

    // ─── UI Setup ─────────────────────────────────────────────────────────────

    private fun setupExposureSeekBar() {
        binding.seekBarExposure.max = EXPOSURE_LABELS.size - 1
        binding.seekBarExposure.progress = selectedExposureIndex
        updateExposureLabel()

        binding.seekBarExposure.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                selectedExposureIndex = progress
                updateExposureLabel()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                applyPreviewExposureSettings()
            }
        })
    }

    private fun setupIsoSeekBar() {
        binding.seekBarIso.max = ISO_VALUES.size - 1
        binding.seekBarIso.progress = selectedIsoIndex
        updateIsoLabel()

        binding.seekBarIso.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                selectedIsoIndex = progress
                updateIsoLabel()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                applyPreviewExposureSettings()
            }
        })
    }

    private fun setupCaptureButton() {
        binding.btnCapture.setOnClickListener {
            if (!isCapturing) captureImage()
        }
    }

    private fun updateExposureLabel() {
        binding.tvExposureValue.text = EXPOSURE_LABELS[selectedExposureIndex]
    }

    private fun updateIsoLabel() {
        binding.tvIsoValue.text = "ISO ${ISO_VALUES[selectedIsoIndex]}"
    }

    // ─── Camera Setup ─────────────────────────────────────────────────────────

    /**
     * Obtains the [ProcessCameraProvider] and binds [Preview] and [ImageCapture] use cases.
     * Called once at startup (or after permission is granted).
     * All manual exposure/ISO settings are managed via [Camera2CameraControl.captureRequestOptions]
     * after binding, avoiding the need to rebuild use cases when settings change.
     */
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider: ProcessCameraProvider
            try {
                provider = future.get()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get CameraProvider", e)
                Toast.makeText(this, R.string.no_camera_found, Toast.LENGTH_SHORT).show()
                return@addListener
            }
            cameraProvider = provider

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }
            val capture = ImageCapture.Builder()
                .setJpegQuality(95)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()

            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture
                )
                imageCapture = capture
                // Apply initial clamped preview exposure after binding
                applyPreviewExposureSettings()
            } catch (e: Exception) {
                Log.e(TAG, "Use case binding failed", e)
                Toast.makeText(this, R.string.no_camera_found, Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ─── Exposure / ISO helpers ───────────────────────────────────────────────

    /**
     * Builds a [CaptureRequestOptions] containing manual exposure settings.
     *
     * @param exposureNs the sensor exposure time in nanoseconds
     * @param iso        the sensor sensitivity (ISO)
     */
    private fun buildCaptureRequestOptions(exposureNs: Long, iso: Int): CaptureRequestOptions =
        CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            .setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF)
            .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
            .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
            .setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, exposureNs + 1_000_000L)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f)
            .build()

    /**
     * Pushes the currently selected (clamped) exposure and ISO to the live preview
     * via [Camera2CameraControl.captureRequestOptions].
     *
     * The preview exposure is clamped to ≤ 500 ms so the viewfinder remains usable
     * for long-exposure settings. Called on seekbar release and after initial binding.
     */
    private fun applyPreviewExposureSettings() {
        val cam = camera ?: return
        val exposureNs = EXPOSURE_TIMES_NS[selectedExposureIndex]
        val iso = ISO_VALUES[selectedIsoIndex]
        // Cap the live-preview exposure so the viewfinder stays responsive
        val previewExposureNs = minOf(exposureNs, 500_000_000L)
        Camera2CameraControl.from(cam.cameraControl).captureRequestOptions =
            buildCaptureRequestOptions(previewExposureNs, iso)
    }

    /**
     * Temporarily switches [Camera2CameraControl.captureRequestOptions] to the full
     * (unclamped) selected exposure and ISO so that the still capture uses the correct
     * settings. The preview clamped settings are restored in the capture callback.
     */
    private fun applyFullExposureForCapture() {
        val cam = camera ?: return
        val exposureNs = EXPOSURE_TIMES_NS[selectedExposureIndex]
        val iso = ISO_VALUES[selectedIsoIndex]
        Camera2CameraControl.from(cam.cameraControl).captureRequestOptions =
            buildCaptureRequestOptions(exposureNs, iso)
    }

    // ─── Capture ──────────────────────────────────────────────────────────────

    /** Resets the button/status UI and restores preview exposure after a capture ends. */
    private fun handleCaptureEnd(succeeded: Boolean) {
        isCapturing = false
        binding.btnCapture.isEnabled = true
        binding.tvStatus.text = getString(
            if (succeeded) R.string.image_saved else R.string.capture_failed
        )
        applyPreviewExposureSettings()
    }

    private fun captureImage() {
        val capture = imageCapture ?: return

        isCapturing = true
        binding.btnCapture.isEnabled = false
        binding.tvStatus.text = getString(R.string.capturing_status, EXPOSURE_LABELS[selectedExposureIndex])

        // Switch to full (unclamped) exposure for the still capture
        applyFullExposureForCapture()

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val filename = "LE_${timestamp}.jpg"

        val outputOptions: ImageCapture.OutputFileOptions
        // Pre-Q: holds the target File so the callback can notify the media scanner
        var outputFileForScanner: java.io.File? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/LongExposure")
            }
            outputOptions = ImageCapture.OutputFileOptions.Builder(
                contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            ).build()
        } else {
            @Suppress("DEPRECATION")
            val dir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DCIM
            )
            val folder = java.io.File(dir, "LongExposure")
            if (!folder.exists()) {
                if (!folder.mkdirs()) {
                    Log.e(TAG, "Failed to create output folder: ${folder.absolutePath}")
                    handleCaptureEnd(succeeded = false)
                    return
                }
            }
            val outputFile = java.io.File(folder, filename)
            outputFileForScanner = outputFile
            outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()
        }

        capture.takePicture(
            outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Log.d(TAG, "Image saved: $filename")
                    // On pre-Q devices, notify the media scanner using the known file path
                    outputFileForScanner?.let { file ->
                        android.media.MediaScannerConnection.scanFile(
                            this@MainActivity,
                            arrayOf(file.absolutePath),
                            arrayOf("image/jpeg"),
                            null
                        )
                    }
                    runOnUiThread { handleCaptureEnd(succeeded = true) }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Image capture failed: ${exception.message}", exception)
                    runOnUiThread { handleCaptureEnd(succeeded = false) }
                }
            }
        )
    }
}
