package com.longexposure.app

/**
 * White balance presets supported by the app.
 *
 * [awbValue] maps to the corresponding `CaptureRequest.CONTROL_AWB_MODE_*` constant used
 * internally by [CameraController] via the Camera2 interop layer of CameraX.
 * All other parts of the app reference this enum only — no direct Camera2 imports required.
 */
enum class WhiteBalanceMode(internal val awbValue: Int) {
    AUTO(1),          // CONTROL_AWB_MODE_AUTO
    DAYLIGHT(5),      // CONTROL_AWB_MODE_DAYLIGHT
    CLOUDY(6),        // CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
    INCANDESCENT(2),  // CONTROL_AWB_MODE_INCANDESCENT
    FLUORESCENT(3),   // CONTROL_AWB_MODE_FLUORESCENT
    SHADE(8)          // CONTROL_AWB_MODE_SHADE
}
