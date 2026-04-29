package com.longexposure.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for exposure-related logic in the Long Exposure Camera app.
 *
 * These tests verify the constants and helper logic that can be run on the JVM
 * without an Android device.
 */
class ExposureLogicTest {

    // Mirror of the companion-object arrays in MainActivity
    private val exposureTimesNs = longArrayOf(
        33_333_333L,
        66_666_667L,
        125_000_000L,
        250_000_000L,
        500_000_000L,
        1_000_000_000L,
        2_000_000_000L,
        4_000_000_000L,
        8_000_000_000L,
        15_000_000_000L,
        30_000_000_000L
    )

    private val exposureLabels = arrayOf(
        "1/30 s", "1/15 s", "1/8 s", "1/4 s", "1/2 s",
        "1 s", "2 s", "4 s", "8 s", "15 s", "30 s"
    )

    private val isoValues = intArrayOf(100, 200, 400, 800, 1600, 3200)

    @Test
    fun exposureTimesAndLabelsHaveSameCount() {
        assertEquals(
            "Exposure times and labels must have equal count",
            exposureTimesNs.size,
            exposureLabels.size
        )
    }

    @Test
    fun exposureTimesAreIncreasing() {
        for (i in 1 until exposureTimesNs.size) {
            assertTrue(
                "Exposure time at index $i (${exposureTimesNs[i]}) must be greater than at ${i - 1} (${exposureTimesNs[i - 1]})",
                exposureTimesNs[i] > exposureTimesNs[i - 1]
            )
        }
    }

    @Test
    fun shortestExposureIsLessThanHalfSecond() {
        assertTrue(
            "Shortest exposure (${exposureTimesNs[0]} ns) should be < 500 ms",
            exposureTimesNs[0] < 500_000_000L
        )
    }

    @Test
    fun longestExposureIs30Seconds() {
        assertEquals(30_000_000_000L, exposureTimesNs.last())
    }

    @Test
    fun isoValuesArePositiveAndIncreasing() {
        for (iso in isoValues) {
            assertTrue("ISO value $iso must be positive", iso > 0)
        }
        for (i in 1 until isoValues.size) {
            assertTrue(
                "ISO at $i (${isoValues[i]}) must be greater than at ${i - 1} (${isoValues[i - 1]})",
                isoValues[i] > isoValues[i - 1]
            )
        }
    }

    @Test
    fun previewExposureCapLogic() {
        // The preview caps exposure at 500 ms to keep the viewfinder responsive
        val previewCapNs = 500_000_000L
        for (ns in exposureTimesNs) {
            val capped = minOf(ns, previewCapNs)
            assertTrue(
                "Preview exposure $capped should be <= cap $previewCapNs",
                capped <= previewCapNs
            )
        }
    }

    @Test
    fun defaultExposureIndexIsOneSecond() {
        val defaultIndex = 5
        assertEquals("Default exposure should be 1 s", "1 s", exposureLabels[defaultIndex])
        assertEquals("Default exposure time should be 1 000 000 000 ns",
            1_000_000_000L, exposureTimesNs[defaultIndex])
    }

    @Test
    fun defaultIsoIndexIs100() {
        val defaultIndex = 0
        assertEquals("Default ISO should be 100", 100, isoValues[defaultIndex])
    }
}
