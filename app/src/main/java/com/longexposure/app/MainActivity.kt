package com.longexposure.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.MediaActionSound
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.longexposure.app.CameraController.Companion.APERTURE_VALUES
import com.longexposure.app.CameraController.Companion.BULB_INDEX
import com.longexposure.app.CameraController.Companion.EXPOSURE_LABELS
import com.longexposure.app.CameraController.Companion.EXPOSURE_TIMES_NS
import com.longexposure.app.CameraController.Companion.ISO_VALUES
import com.longexposure.app.CameraController.Companion.SELF_TIMER_SECONDS
import com.longexposure.app.CameraController.Companion.WB_MODE_VALUES
import com.longexposure.app.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.pow

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CAMERA_PERMISSION = 100
    }

    // ─── Binding / ViewModel ──────────────────────────────────────────────────

    private lateinit var binding: ActivityMainBinding
    private val settingsVm: CaptureSettingsViewModel by viewModels()

    // ─── Camera ───────────────────────────────────────────────────────────────

    private lateinit var cameraController: CameraController
    private lateinit var cameraExecutor: ExecutorService

    // ─── Capture state ────────────────────────────────────────────────────────

    private var isCapturing = false
    private var countDownTimer: CountDownTimer? = null

    // Bulb mode bookkeeping
    private var bulbStartMs = 0L
    private var bulbElapsedTimer: CountDownTimer? = null

    // ─── Misc ─────────────────────────────────────────────────────────────────

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var mediaActionSound: MediaActionSound

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mediaActionSound = MediaActionSound()
        cameraExecutor = Executors.newSingleThreadExecutor()
        cameraController = CameraController(this, this, cameraExecutor)

        cameraController.onCameraReady = { minFocusDist ->
            runOnUiThread { onCameraReady(minFocusDist) }
        }
        cameraController.onHistogramUpdate = { histogram ->
            runOnUiThread {
                if (currentSettings().isHistogramVisible) {
                    binding.histogramView.updateHistogram(histogram)
                }
            }
        }
        cameraController.onCameraError = { msg ->
            runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
        }

        setupModeToggle()        // setupModeToggle() fires applyMode(MANUAL) synchronously via check()
        setupExposureSeekBar()
        setupIsoSeekBar()
        setupApertureSeekBar()
        setupWbSpinner()
        setupFocusControls()
        setupTimerSpinner()
        setupBracketingSpinner()
        setupCaptureButton()
        setupOverlayToggles()

        // Observe settings changes → update info bar
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsVm.settings.collect { settings ->
                    updateInfoBar(settings)
                }
            }
        }

        if (hasCameraPermission()) startCamera() else requestCameraPermission()
    }

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
        bulbElapsedTimer?.cancel()
        cameraController.unbind()
        cameraExecutor.shutdown()
        mediaActionSound.release()
    }

    // ─── Permissions ──────────────────────────────────────────────────────────

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(
            this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCamera()
            else Toast.makeText(this, R.string.camera_permission_required, Toast.LENGTH_LONG).show()
        }
    }

    // ─── Camera ───────────────────────────────────────────────────────────────

    private fun startCamera() {
        cameraController.startCamera(binding.previewView.surfaceProvider)
    }

    /** Called on the main thread once the camera is bound and focus distance is known. */
    private fun onCameraReady(minFocusDist: Float) {
        // Configure focus seekbar range based on device capability
        if (minFocusDist > 0f) {
            binding.seekBarFocus.max = 100  // maps 0..100 → 0..minFocusDist diopters
            binding.rowFocus.visibility = View.VISIBLE
            binding.seekBarFocus.visibility = View.VISIBLE
        } else {
            // Fixed-focus camera — hide focus controls
            binding.rowFocus.visibility = View.GONE
            binding.seekBarFocus.visibility = View.GONE
        }
        cameraController.applyPreviewSettings(currentSettings())
    }

    // ─── UI Setup ─────────────────────────────────────────────────────────────

    private fun setupModeToggle() {
        binding.toggleGroupMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.btnModeP  -> ShootingMode.PROGRAM
                R.id.btnModeAv -> ShootingMode.APERTURE_PRIORITY
                R.id.btnModeTv -> ShootingMode.SHUTTER_PRIORITY
                else           -> ShootingMode.MANUAL
            }
            settingsVm.update { it.copy(shootingMode = mode) }
            applyMode(mode)
        }
        // Select M as the initial mode (fires listener above)
        binding.toggleGroupMode.check(R.id.btnModeM)
    }

    private fun setupExposureSeekBar() {
        binding.seekBarExposure.max = EXPOSURE_LABELS.size - 1  // includes BULB in M mode
        binding.seekBarExposure.progress = currentSettings().exposureIndex

        binding.seekBarExposure.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                settingsVm.update { it.copy(exposureIndex = progress) }
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {
                setupCaptureButton()   // bulb mode check
                cameraController.applyPreviewSettings(currentSettings())
            }
        })
    }

    private fun setupIsoSeekBar() {
        binding.seekBarIso.max = ISO_VALUES.size - 1
        binding.seekBarIso.progress = currentSettings().isoIndex

        binding.seekBarIso.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                settingsVm.update { it.copy(isoIndex = progress) }
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {
                cameraController.applyPreviewSettings(currentSettings())
            }
        })
    }

    private fun setupApertureSeekBar() {
        binding.seekBarAperture.max = APERTURE_VALUES.size - 1
        binding.seekBarAperture.progress = currentSettings().virtualApertureIndex

        binding.seekBarAperture.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                settingsVm.update { it.copy(virtualApertureIndex = progress) }
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {
                cameraController.applyPreviewSettings(currentSettings())
            }
        })
    }

    private fun setupWbSpinner() {
        val adapter = ArrayAdapter.createFromResource(
            this, R.array.wb_labels, android.R.layout.simple_spinner_item
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.spinnerWb.adapter = adapter

        binding.spinnerWb.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                settingsVm.update { it.copy(wbMode = WB_MODE_VALUES[pos]) }
                cameraController.applyPreviewSettings(currentSettings())
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFocusControls() {
        binding.seekBarFocus.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val minDist = cameraController.getMinFocusDist()
                val diopters = if (minDist > 0f) progress / 100f * minDist else 0f
                settingsVm.update { it.copy(focusDistance = diopters, isAfEnabled = false) }
                updateAfButton(false)
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {
                cameraController.applyPreviewSettings(currentSettings())
            }
        })

        binding.btnAf.setOnClickListener {
            val nowAf = !currentSettings().isAfEnabled
            settingsVm.update { it.copy(isAfEnabled = nowAf) }
            updateAfButton(nowAf)
            cameraController.applyPreviewSettings(currentSettings())
        }
    }

    private fun setupTimerSpinner() {
        val adapter = ArrayAdapter.createFromResource(
            this, R.array.timer_labels, android.R.layout.simple_spinner_item
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.spinnerTimer.adapter = adapter

        binding.spinnerTimer.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                settingsVm.update { it.copy(selfTimerIndex = pos) }
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private fun setupBracketingSpinner() {
        val adapter = ArrayAdapter.createFromResource(
            this, R.array.bracketing_labels, android.R.layout.simple_spinner_item
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.spinnerBracketing.adapter = adapter

        binding.spinnerBracketing.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                settingsVm.update { it.copy(bracketingEv = pos) }
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupCaptureButton() {
        binding.btnCapture.setOnClickListener {
            if (!isCapturing && !isBulbMode()) onCaptureButtonClicked()
        }
        binding.btnCapture.setOnTouchListener { _, event ->
            if (isBulbMode()) {
                handleBulbTouch(event)
                true
            } else {
                false  // pass through to click listener
            }
        }
    }

    private fun setupOverlayToggles() {
        binding.btnToggleGrid.setOnClickListener {
            val visible = !currentSettings().isGridVisible
            settingsVm.update { it.copy(isGridVisible = visible) }
            binding.gridOverlay.visibility = if (visible) View.VISIBLE else View.GONE
            binding.btnToggleGrid.setTextColor(
                ContextCompat.getColor(this, if (visible) R.color.accent else R.color.text_secondary)
            )
        }
        binding.btnToggleHistogram.setOnClickListener {
            val visible = !currentSettings().isHistogramVisible
            settingsVm.update { it.copy(isHistogramVisible = visible) }
            binding.histogramView.visibility = if (visible) View.VISIBLE else View.GONE
            binding.btnToggleHistogram.setTextColor(
                ContextCompat.getColor(this, if (visible) R.color.accent else R.color.text_secondary)
            )
        }
    }

    // ─── Mode management ──────────────────────────────────────────────────────

    /**
     * Shows / hides / enables the relevant controls for [mode] and
     * reconfigures the camera capture options.
     */
    private fun applyMode(mode: ShootingMode) {
        val settings = currentSettings()

        // Reset seekbar states
        binding.seekBarIso.isEnabled = true
        binding.seekBarExposure.isEnabled = true

        when (mode) {
            ShootingMode.PROGRAM -> {
                binding.rowExposure.visibility = View.GONE
                binding.seekBarExposure.visibility = View.GONE
                binding.rowIso.visibility = View.GONE
                binding.seekBarIso.visibility = View.GONE
                binding.rowAperture.visibility = View.GONE
                binding.seekBarAperture.visibility = View.GONE
            }
            ShootingMode.APERTURE_PRIORITY -> {
                // Exposure is computed — show the label row as read-only, hide seekbar
                binding.rowExposure.visibility = View.VISIBLE
                binding.seekBarExposure.visibility = View.GONE
                binding.rowIso.visibility = View.VISIBLE
                binding.seekBarIso.visibility = View.VISIBLE
                binding.rowAperture.visibility = View.VISIBLE
                binding.seekBarAperture.visibility = View.VISIBLE
                // Clamp away BULB if it was selected
                if (settings.exposureIndex >= EXPOSURE_TIMES_NS.size) {
                    val newIdx = EXPOSURE_TIMES_NS.size - 1
                    settingsVm.update { it.copy(exposureIndex = newIdx) }
                    binding.seekBarExposure.progress = newIdx
                }
            }
            ShootingMode.SHUTTER_PRIORITY -> {
                binding.rowExposure.visibility = View.VISIBLE
                binding.seekBarExposure.visibility = View.VISIBLE
                // Cap seekbar at 30 s (no BULB in Tv)
                binding.seekBarExposure.max = EXPOSURE_TIMES_NS.size - 1
                // ISO is auto — show the row as read-only without seekbar
                binding.rowIso.visibility = View.VISIBLE
                binding.seekBarIso.visibility = View.GONE
                binding.rowAperture.visibility = View.GONE
                binding.seekBarAperture.visibility = View.GONE
                // Clamp away BULB if it was selected
                if (settings.exposureIndex >= EXPOSURE_TIMES_NS.size) {
                    val newIdx = EXPOSURE_TIMES_NS.size - 1
                    settingsVm.update { it.copy(exposureIndex = newIdx) }
                    binding.seekBarExposure.progress = newIdx
                }
            }
            ShootingMode.MANUAL -> {
                binding.rowExposure.visibility = View.VISIBLE
                binding.seekBarExposure.visibility = View.VISIBLE
                binding.seekBarExposure.max = EXPOSURE_LABELS.size - 1  // includes BULB
                binding.rowIso.visibility = View.VISIBLE
                binding.seekBarIso.visibility = View.VISIBLE
                binding.rowAperture.visibility = View.VISIBLE
                binding.seekBarAperture.visibility = View.VISIBLE
            }
        }

        setupCaptureButton()
        if (cameraController.isCameraReady()) {
            cameraController.applyPreviewSettings(currentSettings())
        }
    }

    // ─── Info bar ─────────────────────────────────────────────────────────────

    /** Updates every element of the top info bar and the inline control labels. */
    private fun updateInfoBar(settings: CaptureSettings) {
        // Mode
        binding.tvInfoMode.text = settings.shootingMode.label

        // Effective params
        val (effectiveNs, effectiveIso) = if (cameraController.isCameraReady()) {
            cameraController.computeEffectiveParams(settings)
        } else {
            Pair(EXPOSURE_TIMES_NS[settings.exposureIndex.coerceAtMost(EXPOSURE_TIMES_NS.size - 1)],
                 ISO_VALUES[settings.isoIndex.coerceAtMost(ISO_VALUES.size - 1)])
        }

        // Exposure label
        val isBulb = settings.shootingMode == ShootingMode.MANUAL &&
                settings.exposureIndex == BULB_INDEX
        val exposureStr = if (isBulb) "BULB"
        else EXPOSURE_LABELS[settings.exposureIndex.coerceAtMost(EXPOSURE_LABELS.size - 1)]
        val effectiveStr = if (isBulb) "BULB"
        else CameraController.formatExposureNs(effectiveNs)

        // Inline seekbar label: show effective (computed) exposure in Av/Tv/P
        binding.tvExposureValue.text = when (settings.shootingMode) {
            ShootingMode.MANUAL, ShootingMode.SHUTTER_PRIORITY -> exposureStr
            ShootingMode.APERTURE_PRIORITY -> effectiveStr
            ShootingMode.PROGRAM -> "Auto"
        }
        binding.tvInfoExposure.text = if (settings.shootingMode == ShootingMode.PROGRAM) "Auto"
        else effectiveStr

        // ISO label
        val isoStr = "ISO $effectiveIso"
        binding.tvIsoValue.text = if (settings.shootingMode == ShootingMode.SHUTTER_PRIORITY)
            "$isoStr (auto)" else isoStr
        binding.tvInfoIso.text = isoStr

        // Aperture label
        val apertureStr = formatAperture(APERTURE_VALUES[settings.virtualApertureIndex])
        binding.tvApertureValue.text = apertureStr
        binding.tvInfoAperture.text = apertureStr

        // White balance
        val wbLabels = resources.getStringArray(R.array.wb_labels)
        val wbIdx = WB_MODE_VALUES.indexOf(settings.wbMode).coerceAtLeast(0)
        binding.tvInfoWb.text = wbLabels.getOrElse(wbIdx) { "AWB" }

        // Focus distance
        binding.tvFocusValue.text = if (settings.focusDistance == 0f) "∞"
        else "%.2f m".format(1.0f / settings.focusDistance)

        // EV
        if (settings.shootingMode != ShootingMode.PROGRAM && !isBulb) {
            val ev = CameraController.computeEV(effectiveNs, effectiveIso)
            binding.tvInfoEv.text = "EV %.1f".format(ev)
        } else {
            binding.tvInfoEv.text = if (settings.shootingMode == ShootingMode.PROGRAM) "EV —" else "BULB"
        }
    }

    // ─── Capture orchestration ────────────────────────────────────────────────

    private fun onCaptureButtonClicked() {
        if (isCapturing) {
            // Second click while timer is running → cancel
            if (countDownTimer != null) cancelTimer()
            return
        }
        val timerSecs = SELF_TIMER_SECONDS[currentSettings().selfTimerIndex]
        if (timerSecs > 0) startSelfTimer(timerSecs) else captureNow()
    }

    private fun startSelfTimer(seconds: Int) {
        binding.btnCapture.text = getString(R.string.btn_capture)
        countDownTimer = object : CountDownTimer(seconds * 1000L, 500L) {
            override fun onTick(millisUntilFinished: Long) {
                val remaining = (millisUntilFinished / 1000L) + 1
                binding.tvStatus.text = getString(R.string.timer_countdown, remaining.toInt())
            }
            override fun onFinish() {
                countDownTimer = null
                captureNow()
            }
        }.start()
    }

    private fun cancelTimer() {
        countDownTimer?.cancel()
        countDownTimer = null
        binding.tvStatus.text = getString(R.string.timer_cancelled)
    }

    private fun captureNow() {
        val settings = currentSettings()
        val bracketingEv = settings.bracketingEv

        isCapturing = true
        binding.btnCapture.isEnabled = false
        playShutterSound()
        animateShutter()

        if (bracketingEv > 0) {
            captureWithBracketing(settings, bracketingEv)
        } else {
            performSingleCapture(settings, suffix = "")
        }
    }

    // ─── Single capture ───────────────────────────────────────────────────────

    private fun performSingleCapture(settings: CaptureSettings, suffix: String) {
        binding.tvStatus.text = getString(
            R.string.capturing_status,
            if (suffix.isEmpty()) EXPOSURE_LABELS[settings.exposureIndex.coerceAtMost(EXPOSURE_LABELS.size - 1)]
            else suffix
        )

        cameraController.applyFullSettingsForCapture(settings)

        val (outputOptions, scanFile) = try {
            FileSaveHelper.buildOutputOptions(this, suffix)
        } catch (e: Exception) {
            Log.e(TAG, "Could not create output file", e)
            handleCaptureEnd(succeeded = false, bracketCount = 0)
            return
        }

        cameraController.takePicture(outputOptions, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                Log.d(TAG, "Image saved (suffix='$suffix')")
                scanFile?.let { FileSaveHelper.notifyMediaScanner(this@MainActivity, it) }
                runOnUiThread { handleCaptureEnd(succeeded = true, bracketCount = 0) }
            }
            override fun onError(exception: ImageCaptureException) {
                Log.e(TAG, "Capture failed: ${exception.message}", exception)
                runOnUiThread { handleCaptureEnd(succeeded = false, bracketCount = 0) }
            }
        })
    }

    // ─── Bracketing capture ───────────────────────────────────────────────────

    private fun captureWithBracketing(settings: CaptureSettings, stepEv: Int) {
        binding.tvStatus.text = getString(R.string.bracketing_status, 1, 3)
        val (baseNs, baseIso) = cameraController.computeEffectiveParams(settings)
        val multipliers = listOf(
            1.0 / 2.0.pow(stepEv),   // −N EV: divide exposure by 2^N
            1.0,                     //  0 EV: base exposure unchanged
            2.0.pow(stepEv)          // +N EV: multiply exposure by 2^N
        )
        val suffixes = listOf("-${stepEv}EV", "0EV", "+${stepEv}EV")
        val exposures = multipliers.map { mult ->
            (baseNs * mult).toLong().coerceIn(EXPOSURE_TIMES_NS.first(), 30_000_000_000L)
        }
        captureBracketFrame(settings, baseIso, exposures, suffixes, 0)
    }

    private fun captureBracketFrame(
        settings: CaptureSettings,
        iso: Int,
        exposures: List<Long>,
        suffixes: List<String>,
        index: Int
    ) {
        // This function always runs on the main thread (via mainHandler.post)
        if (index >= exposures.size) {
            binding.tvStatus.text = getString(R.string.brackets_saved)
            handleCaptureEnd(succeeded = true, bracketCount = exposures.size)
            return
        }

        binding.tvStatus.text = getString(R.string.bracketing_status, index + 1, exposures.size)
        cameraController.applyOverrideSettings(exposures[index], iso, settings)

        val (outputOptions, scanFile) = try {
            FileSaveHelper.buildOutputOptions(this, suffixes[index])
        } catch (e: Exception) {
            Log.e(TAG, "Could not create output file for bracket $index", e)
            handleCaptureEnd(succeeded = false, bracketCount = 0)
            return
        }

        // Brief delay to let the new exposure take effect in the repeating request
        mainHandler.postDelayed({
            cameraController.takePicture(outputOptions, object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Log.d(TAG, "Bracket ${index + 1}/${exposures.size} saved")
                    scanFile?.let { FileSaveHelper.notifyMediaScanner(this@MainActivity, it) }
                    // Always post next frame to main thread for safe UI updates
                    mainHandler.post {
                        captureBracketFrame(settings, iso, exposures, suffixes, index + 1)
                    }
                }
                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Bracket $index failed: ${exception.message}", exception)
                    mainHandler.post { handleCaptureEnd(succeeded = false, bracketCount = 0) }
                }
            })
        }, 400L)
    }

    // ─── Bulb mode ────────────────────────────────────────────────────────────

    private fun isBulbMode(): Boolean {
        val s = currentSettings()
        return s.shootingMode == ShootingMode.MANUAL && s.exposureIndex == BULB_INDEX
    }

    private fun handleBulbTouch(event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (isCapturing) return
                isCapturing = true
                bulbStartMs = System.currentTimeMillis()
                // Show live elapsed timer
                bulbElapsedTimer = object : CountDownTimer(Long.MAX_VALUE, 100L) {
                    override fun onTick(ms: Long) {
                        val elapsed = (System.currentTimeMillis() - bulbStartMs) / 1000.0
                        binding.tvStatus.text = getString(R.string.bulb_elapsed).format(elapsed)
                    }
                    override fun onFinish() {}
                }.start()
                cameraController.applyBulbExposure(30_000_000_000L, currentSettings())
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isCapturing) return
                bulbElapsedTimer?.cancel()
                bulbElapsedTimer = null

                val elapsedNs = ((System.currentTimeMillis() - bulbStartMs) * 1_000_000L)
                    .coerceIn(EXPOSURE_TIMES_NS.first(), 30_000_000_000L)

                binding.btnCapture.isEnabled = false
                playShutterSound()
                animateShutter()
                cameraController.applyBulbExposure(elapsedNs, currentSettings())

                val settings = currentSettings()
                val (outputOptions, scanFile) = try {
                    FileSaveHelper.buildOutputOptions(this, "BULB")
                } catch (e: Exception) {
                    Log.e(TAG, "Could not create bulb output file", e)
                    handleCaptureEnd(succeeded = false, bracketCount = 0)
                    return
                }
                binding.tvStatus.text = getString(
                    R.string.capturing_status,
                    CameraController.formatExposureNs(elapsedNs)
                )
                cameraController.takePicture(outputOptions, object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        Log.d(TAG, "Bulb image saved")
                        scanFile?.let { FileSaveHelper.notifyMediaScanner(this@MainActivity, it) }
                        runOnUiThread { handleCaptureEnd(succeeded = true, bracketCount = 0) }
                    }
                    override fun onError(exception: ImageCaptureException) {
                        Log.e(TAG, "Bulb capture failed: ${exception.message}", exception)
                        runOnUiThread { handleCaptureEnd(succeeded = false, bracketCount = 0) }
                    }
                })
            }
        }
    }

    // ─── Capture end ─────────────────────────────────────────────────────────

    private fun handleCaptureEnd(succeeded: Boolean, bracketCount: Int) {
        isCapturing = false
        binding.btnCapture.isEnabled = true
        if (succeeded && bracketCount == 0) {
            binding.tvStatus.text = getString(R.string.image_saved)
        } else if (!succeeded) {
            binding.tvStatus.text = getString(R.string.capture_failed)
        }
        // Restore preview exposure settings
        cameraController.applyPreviewSettings(currentSettings())
    }

    // ─── Visual / audio feedback ──────────────────────────────────────────────

    private fun animateShutter() {
        binding.shutterOverlay.alpha = 1f
        binding.shutterOverlay.visibility = View.VISIBLE
        binding.shutterOverlay.animate()
            .alpha(0f)
            .setDuration(250)
            .withEndAction { binding.shutterOverlay.visibility = View.GONE }
            .start()
    }

    private fun playShutterSound() {
        try {
            mediaActionSound.play(MediaActionSound.SHUTTER_CLICK)
        } catch (e: Exception) {
            Log.w(TAG, "Could not play shutter sound", e)
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun currentSettings(): CaptureSettings = settingsVm.settings.value

    private fun updateAfButton(afEnabled: Boolean) {
        binding.btnAf.text = getString(if (afEnabled) R.string.btn_af_on else R.string.btn_af_off)
        binding.btnAf.setTextColor(
            ContextCompat.getColor(this, if (afEnabled) R.color.af_active else R.color.text_primary)
        )
    }

    private fun formatAperture(f: Float): String =
        if (f == f.toLong().toFloat()) "f/${f.toLong()}" else "f/${"%.1f".format(f)}"
}

