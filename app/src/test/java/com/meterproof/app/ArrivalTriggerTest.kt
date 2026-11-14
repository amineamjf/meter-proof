package com.meterproof.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrivalTriggerTest {
    @Test fun walkingOrFastBlocksButUnknownSpeedPasses() {
        assertFalse(ArrivalTrigger.motionOk(true, 0f))
        assertFalse(ArrivalTrigger.motionOk(false, 3.0f))
        assertTrue(ArrivalTrigger.motionOk(false, 0.4f))
        assertTrue(ArrivalTrigger.motionOk(false, null))
        assertTrue(ArrivalTrigger.motionOk(false, ArrivalTrigger.MAX_SPEED_MPS))
    }

    @Test fun firstAttemptFires() {
        assertTrue(ArrivalTrigger.shouldFire(1_000_000L, 0L, 0L))
    }

    @Test fun sameMeterWaitsForCooldownThenRetries() {
        val t0 = 1_000_000L
        assertFalse(ArrivalTrigger.shouldFire(t0 + 60_000L, t0, 0L))
        assertTrue(ArrivalTrigger.shouldFire(t0 + ArrivalTrigger.PER_METER_COOLDOWN_MS, t0, 0L))
    }

    @Test fun globalGapSeparatesDifferentMeters() {
        val t0 = 1_000_000L
        assertFalse(ArrivalTrigger.shouldFire(t0 + 3_000L, 0L, t0))
        assertTrue(ArrivalTrigger.shouldFire(t0 + ArrivalTrigger.MIN_GAP_MS, 0L, t0))
    }
}
