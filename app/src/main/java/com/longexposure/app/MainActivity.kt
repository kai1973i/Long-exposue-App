package com.longexposure.app

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.longexposure.app.databinding.ActivityMainBinding
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

        // Frame-count options for burst stacking
        private val FRAME_COUNT_OPTIONS = intArrayOf(4, 8, 16, 32)

        /** Fallback minimum frame duration when the camera does not report one (≈30 FPS). */
        private const val DEFAULT_MIN_FRAME_DURATION_NS = 33_333_333L

        /** Small overhead added to frame duration to cover sensor readout after exposure. */
        private const val FRAME_DURATION_MARGIN_NS = 1_000_000L

        /** Extra slots in the RAW ImageReader buffer beyond the max burst size. */
        private const val IMAGE_READER_BUFFER_MARGIN = 4
    }

    private lateinit var binding: ActivityMainBinding

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null

    // RAW capture support
    private var rawImageReader: ImageReader? = null
    private var rawMinFrameDuration = DEFAULT_MIN_FRAME_DURATION_NS
    private var cfaPattern = 0                      // RGGB default
    private var whiteLevel = 4095                   // 12-bit default
    private var isRawSupported = false

    private lateinit var backgroundThread: HandlerThread
    private lateinit var backgroundHandler: Handler

    private var selectedExposureIndex = 5  // default: 1 s
    private var selectedIsoIndex = 0        // default: ISO 100
    private var selectedFrameCountIndex = 2 // default: 16 frames
    private var isCapturing = false

    // Burst stacking state (accessed only from backgroundHandler thread)
    @Volatile private var isBurstCapturing = false
    @Volatile private var framesProcessedCount = 0

    private var previewSize: Size = Size(1280, 720)
    private var previewSurface: Surface? = null

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupExposureSeekBar()
        setupIsoSeekBar()
        setupFrameCountSeekBar()
        setupCaptureButton()
        setupBurstButton()
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        if (binding.textureView.isAvailable) {
            openCamera(binding.textureView.width, binding.textureView.height)
        } else {
            binding.textureView.surfaceTextureListener = surfaceTextureListener
        }
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
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
                openCamera(binding.textureView.width, binding.textureView.height)
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
                updatePreviewExposure()
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
                updatePreviewExposure()
            }
        })
    }

    private fun setupCaptureButton() {
        binding.btnCapture.setOnClickListener {
            if (!isCapturing && !isBurstCapturing) captureImage()
        }
    }

    private fun setupBurstButton() {
        binding.btnBurstStack.setOnClickListener {
            if (!isCapturing && !isBurstCapturing) startBurstCapture()
        }
    }

    private fun updateExposureLabel() {
        binding.tvExposureValue.text = EXPOSURE_LABELS[selectedExposureIndex]
    }

    private fun updateIsoLabel() {
        binding.tvIsoValue.text = "ISO ${ISO_VALUES[selectedIsoIndex]}"
    }

    private fun setupFrameCountSeekBar() {
        binding.seekBarFrameCount.max = FRAME_COUNT_OPTIONS.size - 1
        binding.seekBarFrameCount.progress = selectedFrameCountIndex
        updateFrameCountLabel()

        binding.seekBarFrameCount.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                selectedFrameCountIndex = progress
                updateFrameCountLabel()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
    }

    private fun updateFrameCountLabel() {
        binding.tvFrameCountValue.text = "${FRAME_COUNT_OPTIONS[selectedFrameCountIndex]}"
    }

    // ─── Background Thread ────────────────────────────────────────────────────

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread.quitSafely()
        try {
            backgroundThread.join()
        } catch (e: InterruptedException) {
            Log.e(TAG, "Background thread interrupted", e)
        }
    }

    // ─── Camera Lifecycle ─────────────────────────────────────────────────────

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            openCamera(width, height)
        }
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture) = true
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    private fun openCamera(width: Int, height: Int) {
        if (!hasCameraPermission()) {
            requestCameraPermission()
            return
        }

        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = selectBackCamera(manager) ?: run {
            Toast.makeText(this, R.string.no_camera_found, Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val map: StreamConfigurationMap = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            ) ?: return

            // Pick a suitable preview / capture size
            previewSize = chooseOptimalSize(
                map.getOutputSizes(SurfaceTexture::class.java), width, height
            )

            val captureSize = chooseOptimalSize(
                map.getOutputSizes(ImageFormat.JPEG), 1920, 1080
            )

            imageReader = ImageReader.newInstance(
                captureSize.width, captureSize.height, ImageFormat.JPEG, 2
            ).apply {
                setOnImageAvailableListener(onImageAvailable, backgroundHandler)
            }

            // ── RAW capability detection ──────────────────────────────────────
            val caps = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            val rawSizes = map.getOutputSizes(ImageFormat.RAW_SENSOR)
            isRawSupported = caps != null &&
                    caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) &&
                    rawSizes != null && rawSizes.isNotEmpty()

            if (isRawSupported) {
                val rawSize = chooseOptimalSize(rawSizes!!, 4096, 3072)
                rawMinFrameDuration = map.getOutputMinFrameDuration(
                    ImageFormat.RAW_SENSOR, rawSize
                ).let { if (it > 0) it else DEFAULT_MIN_FRAME_DURATION_NS }
                cfaPattern = characteristics.get(
                    CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT
                ) ?: 0
                whiteLevel = characteristics.get(
                    CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL
                ) ?: 4095

                // maxImages = largest burst size + margin for in-flight frames
                val maxImages = FRAME_COUNT_OPTIONS.last() + IMAGE_READER_BUFFER_MARGIN
                rawImageReader = ImageReader.newInstance(
                    rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, maxImages
                ).apply {
                    setOnImageAvailableListener(onRawImageAvailable, backgroundHandler)
                }
                Log.d(TAG, "RAW supported: ${rawSize.width}×${rawSize.height}, " +
                        "minFD=${rawMinFrameDuration}ns, CFA=$cfaPattern, WL=$whiteLevel")
            } else {
                Log.d(TAG, "RAW_SENSOR not supported on this device")
                rawImageReader = null
            }

            manager.openCamera(cameraId, cameraStateCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "openCamera failed", e)
        }
    }

    private fun selectBackCamera(manager: CameraManager): String? {
        for (id in manager.cameraIdList) {
            val facing = manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK) return id
        }
        return manager.cameraIdList.firstOrNull()
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            createCameraPreviewSession()
        }
        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            cameraDevice = null
        }
        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            cameraDevice = null
            Log.e(TAG, "Camera device error: $error")
        }
    }

    private fun createCameraPreviewSession() {
        val camera = cameraDevice ?: return
        try {
            val texture = binding.textureView.surfaceTexture ?: return
            texture.setDefaultBufferSize(previewSize.width, previewSize.height)
            val surface = Surface(texture)
            previewSurface = surface
            val readerSurface = imageReader?.surface ?: return

            val previewRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                applyManualExposureSettings(this, isPreview = true)
            }.build()

            // Include RAW reader surface only when RAW is supported
            val surfaces = mutableListOf(surface, readerSurface)
            rawImageReader?.surface?.let { surfaces.add(it) }

            @Suppress("DEPRECATION")
            camera.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            session.setRepeatingRequest(previewRequest, null, backgroundHandler)
                        } catch (e: CameraAccessException) {
                            Log.e(TAG, "setRepeatingRequest failed", e)
                        }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Session configuration failed")
                    }
                },
                backgroundHandler
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "createCameraPreviewSession failed", e)
        }
    }

    private fun closeCamera() {
        captureSession?.close(); captureSession = null
        cameraDevice?.close(); cameraDevice = null
        imageReader?.close(); imageReader = null
        rawImageReader?.close(); rawImageReader = null
        previewSurface = null
    }

    // ─── Exposure / ISO helpers ───────────────────────────────────────────────

    /**
     * Applies manual exposure settings to a capture request builder.
     * For the live preview we clamp the exposure to ≤ 500 ms so the viewfinder
     * remains usable; for the still capture we use the full selected value.
     */
    private fun applyManualExposureSettings(
        builder: CaptureRequest.Builder,
        isPreview: Boolean
    ) {
        val exposureNs = EXPOSURE_TIMES_NS[selectedExposureIndex]
        val iso = ISO_VALUES[selectedIsoIndex]

        // Disable AE so our manual values are used
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF)

        // For the preview, cap exposure so the screen stays responsive
        val previewExposureNs = if (isPreview) minOf(exposureNs, 500_000_000L) else exposureNs
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, previewExposureNs)
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)

        // Fixed frame duration that fits the exposure time (add a small overhead)
        builder.set(
            CaptureRequest.SENSOR_FRAME_DURATION,
            previewExposureNs + 1_000_000L
        )

        // Disable other auto controls
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f)  // hyperfocal
    }

    private fun updatePreviewExposure() {
        val session = captureSession ?: return
        val camera = cameraDevice ?: return
        val surface = previewSurface ?: return
        try {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                applyManualExposureSettings(this, isPreview = true)
            }.build()
            session.setRepeatingRequest(request, null, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "updatePreviewExposure failed", e)
        }
    }

    // ─── Capture ──────────────────────────────────────────────────────────────

    private fun captureImage() {
        val session = captureSession ?: return
        val camera = cameraDevice ?: return
        val reader = imageReader ?: return

        isCapturing = true
        runOnUiThread {
            binding.btnCapture.isEnabled = false
            binding.btnBurstStack.isEnabled = false
            binding.tvStatus.text = getString(
                R.string.capturing_status, EXPOSURE_LABELS[selectedExposureIndex]
            )
        }

        try {
            val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                applyManualExposureSettings(this, isPreview = false)
                set(CaptureRequest.JPEG_QUALITY, 95.toByte())
            }.build()

            session.capture(captureRequest, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    Log.d(TAG, "Capture completed")
                }
                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure
                ) {
                    isCapturing = false
                    runOnUiThread {
                        binding.btnCapture.isEnabled = true
                        binding.btnBurstStack.isEnabled = isRawSupported
                        binding.tvStatus.text = getString(R.string.capture_failed)
                    }
                    Log.e(TAG, "Capture failed: ${failure.reason}")
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            isCapturing = false
            runOnUiThread {
                binding.btnCapture.isEnabled = true
                binding.btnBurstStack.isEnabled = isRawSupported
                binding.tvStatus.text = getString(R.string.capture_failed)
            }
            Log.e(TAG, "captureImage failed", e)
        }
    }

    private val onImageAvailable = ImageReader.OnImageAvailableListener { reader ->
        val image: Image = reader.acquireLatestImage() ?: return@OnImageAvailableListener
        try {
            saveImageToGallery(image)
        } finally {
            image.close()
            isCapturing = false
            runOnUiThread {
                binding.btnCapture.isEnabled = true
                binding.btnBurstStack.isEnabled = isRawSupported
                binding.tvStatus.text = getString(R.string.image_saved)
            }
        }
    }

    // ─── Burst RAW Capture ────────────────────────────────────────────────────

    /**
     * Starts a burst capture at the camera's maximum frame rate in RAW_SENSOR mode.
     * Frames are aligned and accumulated with brightest-pixel blending by [BurstProcessor].
     */
    private fun startBurstCapture() {
        if (!isRawSupported) {
            Toast.makeText(this, R.string.raw_not_supported, Toast.LENGTH_LONG).show()
            return
        }
        val session = captureSession ?: return
        val camera = cameraDevice ?: return
        val rawReader = rawImageReader ?: return

        val target = FRAME_COUNT_OPTIONS[selectedFrameCountIndex]

        isBurstCapturing = true
        framesProcessedCount = 0

        runOnUiThread {
            binding.btnCapture.isEnabled = false
            binding.btnBurstStack.isEnabled = false
            binding.progressBurst.max = target
            binding.progressBurst.progress = 0
            binding.progressBurst.visibility = View.VISIBLE
            binding.tvStatus.text = getString(R.string.burst_capturing_status, 0, target)
        }

        // Reset the stacking processor on the background thread so it is
        // guaranteed to be cleared before the first image callback fires.
        backgroundHandler.post {
            // Drain any stale images left in the reader from prior activity
            // before resetting the processor to avoid mixing frame sets.
            var stale = rawReader.acquireLatestImage()
            while (stale != null) {
                stale.close()
                stale = rawReader.acquireLatestImage()
            }
            BurstProcessor.reset()

            val exposureNs = EXPOSURE_TIMES_NS[selectedExposureIndex]
            // Use the minimum possible frame duration to maximise FPS while
            // still fitting the full exposure time.
            val frameDuration = maxOf(rawMinFrameDuration, exposureNs + FRAME_DURATION_MARGIN_NS)

            try {
                val burstRequest = camera
                    .createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                        addTarget(rawReader.surface)
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF)
                        set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
                        set(CaptureRequest.SENSOR_SENSITIVITY, ISO_VALUES[selectedIsoIndex])
                        set(CaptureRequest.SENSOR_FRAME_DURATION, frameDuration)
                        set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                        set(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f)
                    }.build()

                // Stop preview; the burst repeating request takes over.
                session.stopRepeating()
                session.setRepeatingRequest(burstRequest, null, backgroundHandler)
                Log.d(TAG, "Burst started: $target frames, FD=${frameDuration}ns")
            } catch (e: CameraAccessException) {
                isBurstCapturing = false
                Log.e(TAG, "startBurstCapture failed", e)
                runOnUiThread {
                    binding.progressBurst.visibility = View.GONE
                    binding.btnCapture.isEnabled = true
                    binding.btnBurstStack.isEnabled = true
                    binding.tvStatus.text = getString(R.string.capture_failed)
                }
            }
        }
    }

    /**
     * Called by [ImageReader] every time a new RAW_SENSOR frame is ready.
     * Processes the frame, updates progress, and finalises the burst once
     * the target frame count is reached.
     */
    private val onRawImageAvailable = ImageReader.OnImageAvailableListener { reader ->
        if (!isBurstCapturing) {
            reader.acquireLatestImage()?.close()
            return@OnImageAvailableListener
        }

        val image: Image = reader.acquireNextImage() ?: return@OnImageAvailableListener
        try {
            BurstProcessor.processFrame(image)
        } finally {
            image.close()
        }

        framesProcessedCount++
        val target = FRAME_COUNT_OPTIONS[selectedFrameCountIndex]
        runOnUiThread {
            binding.progressBurst.progress = framesProcessedCount
            binding.tvStatus.text = getString(
                R.string.burst_capturing_status, framesProcessedCount, target
            )
        }

        if (framesProcessedCount >= target) {
            isBurstCapturing = false
            try {
                captureSession?.stopRepeating()
            } catch (e: CameraAccessException) {
                Log.w(TAG, "stopRepeating after burst failed", e)
            }
            finalizeBurstCapture()
        }
    }

    /**
     * Converts the accumulated [BurstProcessor] max-stack to a JPEG Bitmap and
     * saves it to the gallery, then restores the live preview.
     * Runs on the background thread.
     */
    private fun finalizeBurstCapture() {
        runOnUiThread {
            binding.tvStatus.text = getString(
                R.string.burst_processing_status,
                FRAME_COUNT_OPTIONS[selectedFrameCountIndex]
            )
        }

        val bitmap = BurstProcessor.getResultBitmap(cfaPattern, whiteLevel)
        if (bitmap != null) {
            saveBitmapToGallery(bitmap)
            bitmap.recycle()
            runOnUiThread {
                binding.progressBurst.visibility = View.GONE
                binding.tvStatus.text = getString(R.string.burst_saved)
                binding.btnCapture.isEnabled = true
                binding.btnBurstStack.isEnabled = true
            }
        } else {
            runOnUiThread {
                binding.progressBurst.visibility = View.GONE
                binding.tvStatus.text = getString(R.string.capture_failed)
                binding.btnCapture.isEnabled = true
                binding.btnBurstStack.isEnabled = true
            }
        }

        // Restore the live preview
        updatePreviewExposure()
    }

    // ─── Save to Gallery ──────────────────────────────────────────────────────

    private fun saveImageToGallery(image: Image) {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val filename = "LE_${timestamp}.jpg"

        val outputStream: OutputStream?
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/LongExposure")
            }
            val uri = contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues
            )
            outputStream = uri?.let { contentResolver.openOutputStream(it) }
        } else {
            @Suppress("DEPRECATION")
            val dir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DCIM
            )
            val folder = java.io.File(dir, "LongExposure").also { it.mkdirs() }
            val file = java.io.File(folder, filename)
            outputStream = java.io.FileOutputStream(file)
            // Notify gallery on older Android using MediaScannerConnection
            android.media.MediaScannerConnection.scanFile(
                this,
                arrayOf(file.absolutePath),
                arrayOf("image/jpeg"),
                null
            )
        }

        outputStream?.use { it.write(bytes) }
        Log.d(TAG, "Image saved: $filename")
    }

    /**
     * Compresses [bitmap] as JPEG and writes it to the gallery under DCIM/LongExposure.
     * The filename is prefixed with "LE_STACK_" to distinguish stacked images.
     */
    private fun saveBitmapToGallery(bitmap: Bitmap) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val filename = "LE_STACK_${timestamp}.jpg"

        val bytes = ByteArrayOutputStream().use { bos ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, bos)
            bos.toByteArray()
        }

        val outputStream: OutputStream?
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/LongExposure")
            }
            val uri = contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues
            )
            outputStream = uri?.let { contentResolver.openOutputStream(it) }
        } else {
            @Suppress("DEPRECATION")
            val dir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DCIM
            )
            val folder = java.io.File(dir, "LongExposure").also { it.mkdirs() }
            val file = java.io.File(folder, filename)
            outputStream = java.io.FileOutputStream(file)
            android.media.MediaScannerConnection.scanFile(
                this,
                arrayOf(file.absolutePath),
                arrayOf("image/jpeg"),
                null
            )
        }

        outputStream?.use { it.write(bytes) }
        Log.d(TAG, "Stacked image saved: $filename")
    }

    // ─── Size selection ───────────────────────────────────────────────────────

    private fun chooseOptimalSize(choices: Array<Size>, maxWidth: Int, maxHeight: Int): Size {
        val suitable = choices.filter {
            it.width <= maxWidth * 2 && it.height <= maxHeight * 2
        }.sortedByDescending { it.width.toLong() * it.height }
        return suitable.firstOrNull() ?: choices.first()
    }
}
