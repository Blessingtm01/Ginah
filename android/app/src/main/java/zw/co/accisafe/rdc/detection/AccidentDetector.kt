package zw.co.accisafe.rdc.detection

import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Decides whether a burst of sensor readings looks like a vehicle accident.
 *
 * Detection runs in two stages so that ordinary knocks and drops do not raise alerts:
 *  1. Trigger: total acceleration exceeds [Config.impactG].
 *  2. Evaluation: for [Config.observeMs] after the trigger the detector keeps watching and
 *     scores the event on several independent pieces of evidence:
 *       - impact strength (strong impact counts double)
 *       - violent rotation from the gyroscope around the impact
 *       - a loud bang from the microphone around the impact (when sound detection is on)
 *       - the phone was travelling at vehicle speed just before the impact (GPS)
 *       - the phone came to rest after the impact
 *     Two hard vetoes apply: a known pre-impact speed below vehicle speed (a phone dropped
 *     while walking), and the vehicle still moving normally a few seconds later (a phone
 *     dropped inside a moving car).
 *
 * The detector also compares the direction of gravity before and after the event; a large
 * change is reported as a possible rollover. This classifies the event but does not add points,
 * because a phone thrown from a holder also ends up in a different orientation.
 *
 * Events that pass are handed to the user-facing countdown, which is the final confirmation step.
 * All times are in milliseconds; the class has no Android dependencies so it can be unit tested.
 */
class AccidentDetector(private val config: Config = Config()) {

    data class Config(
        val impactG: Float = 4.0f,
        val strongImpactG: Float = 6.0f,
        val rotationRadPerSec: Float = 3.5f,
        val rotationWindowMs: Long = 1000,
        val loudDbfs: Float = -1.5f,
        val movingKmh: Float = 15f,
        val stillMovingKmh: Float = 20f,
        val speedMaxAgeMs: Long = 15_000,
        val observeMs: Long = 5000,
        val settleMs: Long = 1000,
        val stillnessStdG: Float = 0.35f,
        val scoreWithSpeed: Int = 3,
        val scoreWithoutSpeed: Int = 4,
        val cooldownMs: Long = 10_000,
    )

    data class Evidence(
        val impactAtMs: Long,
        val peakG: Float,
        val peakRotation: Float,
        val peakSoundDbfs: Float?,
        val speedBeforeKmh: Float?,
        val speedAfterKmh: Float?,
        val stillAfter: Boolean,
        val orientationChangeDeg: Float?,
        val score: Int,
        val isAccident: Boolean,
        val reasons: List<String>,
    ) {
        val possibleRollover get() = orientationChangeDeg != null && orientationChangeDeg >= ROLLOVER_REPORT_DEG
        val crashType get() = if (possibleRollover) "Possible rollover" else "Collision"
    }

    private var observing = false
    private var impactAt = 0L
    private var peakG = 0f
    private var peakRotation = 0f
    private var peakSound: Float? = null
    private var speedBefore: Float? = null
    private var speedAfter: Float? = null
    private var cooldownUntil = 0L

    private var lastSpeedKmh: Float? = null
    private var lastSpeedAt = 0L

    // Recent gyro and sound samples, so readings slightly before the impact still count.
    private val gyro = RecentPeaks(config.rotationWindowMs)
    private val sound = RecentPeaks(config.rotationWindowMs)

    // Slowly-updated estimate of gravity direction before the impact.
    private val gravity = FloatArray(3)
    private var gravityReady = false
    private val gravityBefore = FloatArray(3)

    // Running statistics of acceleration after the vehicle has settled.
    private var settledCount = 0
    private var settledSum = 0.0
    private var settledSumSq = 0.0
    private val settledVector = DoubleArray(3)

    val isObserving get() = observing

    fun onSpeed(tMs: Long, kmh: Float) {
        lastSpeedKmh = kmh
        lastSpeedAt = tMs
        if (observing && tMs - impactAt >= config.settleMs) speedAfter = kmh
    }

    fun onGyro(tMs: Long, x: Float, y: Float, z: Float) {
        val mag = sqrt(x * x + y * y + z * z)
        gyro.add(tMs, mag)
        if (observing && tMs - impactAt <= config.rotationWindowMs && mag > peakRotation) peakRotation = mag
    }

    /** Peak microphone level for a short audio block, in dBFS (0 = loudest possible). */
    fun onSound(tMs: Long, dbfs: Float) {
        sound.add(tMs, dbfs)
        if (observing && tMs - impactAt <= config.rotationWindowMs && dbfs > (peakSound ?: Float.NEGATIVE_INFINITY)) {
            peakSound = dbfs
        }
    }

    /** Feeds one accelerometer sample in m/s². Returns a verdict once an event has been evaluated. */
    fun onAccel(tMs: Long, x: Float, y: Float, z: Float): Evidence? {
        val g = sqrt(x * x + y * y + z * z) / GRAVITY

        if (!observing) {
            updateGravity(x, y, z, g)
            if (g >= config.impactG && tMs >= cooldownUntil) startObserving(tMs, g)
            return null
        }

        val sinceImpact = tMs - impactAt
        if (sinceImpact < config.settleMs) {
            if (g > peakG) peakG = g
        } else {
            settledCount++
            settledSum += g
            settledSumSq += g.toDouble() * g
            settledVector[0] += x.toDouble(); settledVector[1] += y.toDouble(); settledVector[2] += z.toDouble()
        }
        return if (sinceImpact >= config.observeMs) finish() else null
    }

    private fun updateGravity(x: Float, y: Float, z: Float, g: Float) {
        // Only learn from calm readings so that bumps do not skew the estimate.
        if (g < 0.8f || g > 1.2f) return
        if (!gravityReady) {
            gravity[0] = x; gravity[1] = y; gravity[2] = z
            gravityReady = true
        } else {
            gravity[0] += GRAVITY_ALPHA * (x - gravity[0])
            gravity[1] += GRAVITY_ALPHA * (y - gravity[1])
            gravity[2] += GRAVITY_ALPHA * (z - gravity[2])
        }
    }

    private fun startObserving(tMs: Long, g: Float) {
        observing = true
        impactAt = tMs
        peakG = g
        peakRotation = gyro.peak(tMs) ?: 0f
        peakSound = sound.peak(tMs)
        speedBefore = lastSpeedKmh?.takeIf { tMs - lastSpeedAt <= config.speedMaxAgeMs }
        speedAfter = null
        settledCount = 0
        settledSum = 0.0
        settledSumSq = 0.0
        settledVector.fill(0.0)
        gravity.copyInto(gravityBefore)
    }

    private fun finish(): Evidence {
        observing = false
        cooldownUntil = impactAt + config.cooldownMs

        val reasons = mutableListOf<String>()
        var score = 0

        if (peakG >= config.strongImpactG) {
            score += 2; reasons += "strong impact %.1fg".format(peakG)
        } else {
            score += 1; reasons += "impact %.1fg".format(peakG)
        }
        if (peakRotation >= config.rotationRadPerSec) {
            score += 1; reasons += "violent rotation %.1f rad/s".format(peakRotation)
        }
        val loud = peakSound
        if (loud != null && loud >= config.loudDbfs) {
            score += 1; reasons += "loud bang"
        }
        val before = speedBefore
        if (before != null && before >= config.movingKmh) {
            score += 1; reasons += "travelling at %.0f km/h".format(before)
        }
        val stillAfter = settledCount > 0 && stdDev() < config.stillnessStdG
        if (stillAfter) {
            score += 1; reasons += "no movement after impact"
        }
        val orientationChange = orientationChangeDeg()
        if (orientationChange != null && orientationChange >= ROLLOVER_REPORT_DEG) {
            reasons += "phone orientation changed %.0f°".format(orientationChange)
        }

        val after = speedAfter
        val isAccident = when {
            before != null && before < config.movingKmh -> {
                reasons += "rejected: not in a moving vehicle (%.0f km/h)".format(before); false
            }
            after != null && after >= config.stillMovingKmh -> {
                reasons += "rejected: vehicle still moving at %.0f km/h".format(after); false
            }
            before != null -> score >= config.scoreWithSpeed
            else -> score >= config.scoreWithoutSpeed
        }

        return Evidence(
            impactAt, peakG, peakRotation, peakSound, before, after, stillAfter,
            orientationChange, score, isAccident, reasons,
        )
    }

    private fun stdDev(): Float {
        val mean = settledSum / settledCount
        val variance = (settledSumSq / settledCount - mean * mean).coerceAtLeast(0.0)
        return sqrt(variance).toFloat()
    }

    private fun orientationChangeDeg(): Float? {
        if (!gravityReady || settledCount == 0) return null
        val ax = gravityBefore[0].toDouble(); val ay = gravityBefore[1].toDouble(); val az = gravityBefore[2].toDouble()
        val bx = settledVector[0]; val by = settledVector[1]; val bz = settledVector[2]
        val na = sqrt(ax * ax + ay * ay + az * az)
        val nb = sqrt(bx * bx + by * by + bz * bz)
        if (na == 0.0 || nb == 0.0) return null
        val cos = ((ax * bx + ay * by + az * bz) / (na * nb)).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(cos)).toFloat()
    }

    /** Keeps samples from the last [windowMs] so their peak can be read at trigger time. */
    private class RecentPeaks(private val windowMs: Long) {
        private val times = ArrayDeque<Long>()
        private val values = ArrayDeque<Float>()

        fun add(tMs: Long, value: Float) {
            times.addLast(tMs)
            values.addLast(value)
            while (times.isNotEmpty() && tMs - times.first() > windowMs) {
                times.removeFirst()
                values.removeFirst()
            }
        }

        fun peak(nowMs: Long): Float? =
            values.filterIndexed { i, _ -> nowMs - times[i] <= windowMs }.maxOrNull()
    }

    companion object {
        const val GRAVITY = 9.80665f
        const val ROLLOVER_REPORT_DEG = 75f
        private const val GRAVITY_ALPHA = 0.02f
    }
}
