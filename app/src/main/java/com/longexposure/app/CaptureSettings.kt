package com.longexposure.app

import android.hardware.camera2.CaptureRequest
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Immutable snapshot of every user-adjustable camera setting.
 * The [CaptureSettingsViewModel] holds a [StateFlow] of this object so that
 * any UI component (info bar, EV display, histogram toggle, etc.) can react
 * to changes without direct coupling to [MainActivity].
 */
data class CaptureSettings(
    val shootingMode: ShootingMode = ShootingMode.MANUAL,
    /** Index into [CameraController.EXPOSURE_TIMES_NS] / [CameraController.EXPOSURE_LABELS]. */
    val exposureIndex: Int = 5,
    /** Index into [CameraController.ISO_VALUES]. */
    val isoIndex: Int = 0,
    /** One of the [CaptureRequest.CONTROL_AWB_MODE_*] constants. */
    val wbMode: Int = CaptureRequest.CONTROL_AWB_MODE_AUTO,
    /** Focus distance in diopters (0 = infinity). */
    val focusDistance: Float = 0.0f,
    /** Index into [CameraController.APERTURE_VALUES]. */
    val virtualApertureIndex: Int = 0,
    /** Whether continuous AF is active. When false, manual [focusDistance] is used. */
    val isAfEnabled: Boolean = false,
    /** Index into [CameraController.SELF_TIMER_SECONDS] (0 = off). */
    val selfTimerIndex: Int = 0,
    /** Bracketing step in EV stops: 0 = off, 1 = ±1 EV, 2 = ±2 EV. */
    val bracketingEv: Int = 0,
    val isGridVisible: Boolean = false,
    val isHistogramVisible: Boolean = false
)

/** ViewModel that owns the single source of truth for [CaptureSettings]. */
class CaptureSettingsViewModel : ViewModel() {
    private val _settings = MutableStateFlow(CaptureSettings())
    val settings: StateFlow<CaptureSettings> = _settings.asStateFlow()

    fun update(transform: (CaptureSettings) -> CaptureSettings) {
        _settings.value = transform(_settings.value)
    }
}
