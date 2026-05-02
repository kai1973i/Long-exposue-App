package com.longexposure.app

/** The four shooting modes available in DSLR simulation. */
enum class ShootingMode(val label: String) {
    /** Program — auto exposure and auto ISO. */
    PROGRAM("P"),
    /** Aperture Priority — user sets virtual f-stop; shutter speed is computed. */
    APERTURE_PRIORITY("Av"),
    /** Shutter Priority — user sets exposure time; ISO is chosen automatically. */
    SHUTTER_PRIORITY("Tv"),
    /** Manual — both exposure and ISO are fully user-controlled. */
    MANUAL("M")
}
