package zw.co.accisafe.rdc.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccidentDetectorTest {

    private val g = AccidentDetector.GRAVITY
    private val stepMs = 20L // ~50 Hz, like SENSOR_DELAY_GAME

    /** Replays a scenario and returns the first verdict the detector produces. */
    private fun run(
        speedBefore: Float?,
        impactG: Float,
        rotation: Float,
        afterNoiseG: Float,
        speedAfter: Float? = null,
        bangDbfs: Float? = null,
        landsOnSide: Boolean = false,
    ): AccidentDetector.Evidence? {
        val d = AccidentDetector()
        var t = 0L
        // 3 s of normal travel, phone lying flat (gravity on z)
        while (t < 3000) {
            if (speedBefore != null && t % 1000 == 0L) d.onSpeed(t, speedBefore)
            d.onGyro(t, 0.1f, 0f, 0f)
            if (t % 50 == 0L) d.onSound(t, -30f)
            assertNull(d.onAccel(t, 0f, 0f, g))
            t += stepMs
        }
        // 200 ms impact
        val impactEnd = t + 200
        while (t < impactEnd) {
            d.onGyro(t, rotation, 0f, 0f)
            if (bangDbfs != null) d.onSound(t, bangDbfs)
            d.onAccel(t, impactG * g, 0f, 0f)
            t += stepMs
        }
        // aftermath, alternating noise around 1 g; gravity moves to x if the vehicle rolled
        var flip = false
        while (t < 15_000) {
            if (speedAfter != null && t % 1000 == 0L) d.onSpeed(t, speedAfter)
            d.onGyro(t, 0.05f, 0f, 0f)
            flip = !flip
            val a = (1f + if (flip) afterNoiseG else -afterNoiseG) * g
            val verdict = if (landsOnSide) d.onAccel(t, a, 0f, 0f) else d.onAccel(t, 0f, 0f, a)
            verdict?.let { return it }
            t += stepMs
        }
        return null
    }

    @Test
    fun loudBangAddsEvidence() {
        val quiet = run(speedBefore = null, impactG = 5f, rotation = 5f, afterNoiseG = 0.02f)
        assertFalse(quiet!!.isAccident) // 1 + 1 + 1 = 3, below the no-GPS bar of 4
        val loud = run(speedBefore = null, impactG = 5f, rotation = 5f, afterNoiseG = 0.02f, bangDbfs = -0.5f)
        assertTrue(loud!!.isAccident)
        assertTrue(loud.reasons.contains("loud bang"))
    }

    @Test
    fun orientationChangeIsReportedAsPossibleRollover() {
        val v = run(speedBefore = 70f, impactG = 8f, rotation = 6f, afterNoiseG = 0.02f, landsOnSide = true)
        assertTrue(v!!.isAccident)
        assertTrue(v.possibleRollover)
        assertEquals("Possible rollover", v.crashType)
        val upright = run(speedBefore = 70f, impactG = 8f, rotation = 6f, afterNoiseG = 0.02f)
        assertEquals("Collision", upright!!.crashType)
    }

    @Test
    fun crashAtSpeedIsDetected() {
        val v = run(speedBefore = 60f, impactG = 8f, rotation = 6f, afterNoiseG = 0.02f, speedAfter = 0f)
        assertNotNull(v)
        assertTrue(v!!.isAccident)
        assertEquals(5, v.score)
    }

    @Test
    fun phoneDroppedWhileWalkingIsRejected() {
        val v = run(speedBefore = 5f, impactG = 7f, rotation = 8f, afterNoiseG = 0.01f)
        assertFalse(v!!.isAccident)
    }

    @Test
    fun potholeDoesNotTrigger() {
        val v = run(speedBefore = 60f, impactG = 2.5f, rotation = 1f, afterNoiseG = 0.3f, speedAfter = 58f)
        assertNull(v)
    }

    @Test
    fun phoneDroppedInMovingCarIsRejected() {
        val v = run(speedBefore = 80f, impactG = 5f, rotation = 5f, afterNoiseG = 0.05f, speedAfter = 78f)
        assertFalse(v!!.isAccident)
    }

    @Test
    fun strongCrashWithoutGpsIsDetected() {
        val v = run(speedBefore = null, impactG = 9f, rotation = 7f, afterNoiseG = 0.02f)
        assertTrue(v!!.isAccident)
    }

    @Test
    fun moderateImpactWithoutGpsIsRejected() {
        val v = run(speedBefore = null, impactG = 4.5f, rotation = 5f, afterNoiseG = 0.5f)
        assertFalse(v!!.isAccident)
    }
}
