package com.meterproof.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturePolicyTest {
    private val t0 = 1_000_000L

    @Test fun stopWithoutDbPhotoOnTakesStopPhoto() =
        assertEquals(CapturePolicy.StopAction.STOP, CapturePolicy.decideStop(false, true, t0, 0))

    @Test fun stopWithoutDbPhotoOffSkips() =
        assertEquals(CapturePolicy.StopAction.SKIP_DISABLED, CapturePolicy.decideStop(false, false, t0, 0))

    @Test fun arrivalWinsEvenWhenPhotoOnStopOff() =
        assertEquals(CapturePolicy.StopAction.ARRIVAL, CapturePolicy.decideStop(true, false, t0, 0))

    @Test fun arrivalWinsOverStopWhenBothApply() =
        assertEquals(CapturePolicy.StopAction.ARRIVAL, CapturePolicy.decideStop(true, true, t0, 0))

    @Test fun tooSoonAfterLastPhotoSkips() {
        val last = t0 - 5_000
        assertEquals(CapturePolicy.StopAction.SKIP_TOO_SOON, CapturePolicy.decideStop(false, true, t0, last))
        assertEquals(CapturePolicy.StopAction.SKIP_TOO_SOON, CapturePolicy.decideStop(true, true, t0, last))
    }

    @Test fun exactlyAtGapIsAllowed() {
        val last = t0 - CapturePolicy.MIN_GAP_BETWEEN_STOP_PHOTOS_MS
        assertEquals(CapturePolicy.StopAction.STOP, CapturePolicy.decideStop(false, true, t0, last))
    }

    @Test fun disabledBeatsTooSoon() =
        assertEquals(CapturePolicy.StopAction.SKIP_DISABLED, CapturePolicy.decideStop(false, false, t0, t0 - 1))

    @Test fun failureDetailIsFixedCodesOnly() {
        assertEquals("mode=stop;reason=CAPTURE_ERROR", CapturePolicy.failureDetail("stop", CapturePolicy.Reason.CAPTURE_ERROR))
        val d = CapturePolicy.failureDetail("a;b=c", "x y;z")
        assertTrue(!d.substring(5).contains(" ") && d.count { it == ';' } == 1)
    }

    @Test fun presetsMatchRequirements() {
        assertEquals(listOf(10, 15, 20, 30), CapturePolicy.STOP_SECONDS.toList())
        assertEquals(listOf(1 to 2, 2 to 5, 5 to 30), CapturePolicy.INTERVALS.toList())
        assertEquals(1, CapturePolicy.intervalIndex(2, 5))
        assertEquals(-1, CapturePolicy.intervalIndex(3, 7))
        assertEquals(1, CapturePolicy.stopIndex(15))
        assertEquals(-1, CapturePolicy.stopIndex(45))
    }
}
