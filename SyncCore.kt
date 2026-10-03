// SYNORA sync core (Kotlin). Created by Joe (Creator / Builder). Original concept: Sai Krishna.
// Flat single-file version of core/sync, made so the tests can run from a phone via GitHub Actions.
package com.synora.core.sync

import kotlin.math.abs
import kotlin.math.sqrt

/** One ping/pong exchange. offsetMs = hostClock - localClock. atMs = local time the reply arrived. */
data class ClockSample(val offsetMs: Double, val rttMs: Double, val atMs: Double)

data class OffsetEstimate(
    val offsetMs: Double,
    val rttMs: Double,
    val spreadMs: Double,
    val slopePpm: Double?,
    val used: Int,
)

/** NTP-style clock offset estimation. Port of the tested web prototype (synora-core.js). */
object ClockSync {
    /** t0 = local send, t1 = host receive, t2 = host send, t3 = local receive (all in ms). */
    fun sample(t0: Double, t1: Double, t2: Double, t3: Double): ClockSample =
        ClockSample(
            offsetMs = ((t1 - t0) + (t2 - t3)) / 2.0,
            rttMs = (t3 - t0) - (t2 - t1),
            atMs = t3,
        )

    fun median(values: List<Double>): Double {
        require(values.isNotEmpty()) { "median of an empty list" }
        val s = values.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2.0
    }

    /** Samples with the smallest round trip are the most trustworthy. */
    fun pickOffset(samples: List<ClockSample>, keep: Int = 3): OffsetEstimate? {
        val ok = samples.filter { it.offsetMs.isFinite() && it.rttMs.isFinite() && it.rttMs >= 0.0 }
        if (ok.isEmpty()) return null
        val best = ok.sortedBy { it.rttMs }.take(keep)
        val offs = best.map { it.offsetMs }
        return OffsetEstimate(
            offsetMs = median(offs),
            rttMs = best.first().rttMs,
            spreadMs = offs.max() - offs.min(),
            slopePpm = null,
            used = best.size,
        )
    }

    /**
     * Offset at [nowMs], using a straight-line fit over the last minute so slow clock
     * drift (ppm) is followed. Falls back to [pickOffset] when there is too little data.
     */
    fun predictOffset(samples: List<ClockSample>, nowMs: Double, keep: Int = 3): OffsetEstimate? {
        val win = samples.filter { nowMs - it.atMs <= 60_000.0 && it.offsetMs.isFinite() && it.rttMs >= 0.0 }
        val base = pickOffset(if (win.isNotEmpty()) win else samples, keep) ?: return null
        if (win.size < 8) return base
        val span = win.maxOf { it.atMs } - win.minOf { it.atMs }
        if (span < 15_000.0) return base

        val good = win.sortedBy { it.rttMs }.take(maxOf(6, win.size / 2))
        val n = good.size.toDouble()
        var sx = 0.0
        var sy = 0.0
        var sxx = 0.0
        var sxy = 0.0
        for (s in good) {
            val x = s.atMs - nowMs
            sx += x
            sy += s.offsetMs
            sxx += x * x
            sxy += x * s.offsetMs
        }
        val den = n * sxx - sx * sx
        if (abs(den) < 1e-6) return base
        val b = (n * sxy - sx * sy) / den
        val a = (sy - b * sx) / n
        if (abs(b) > 1e-3 || abs(a - base.offsetMs) > 50.0) return base // implausible fit

        var ss = 0.0
        for (s in good) {
            val r = s.offsetMs - (a + b * (s.atMs - nowMs))
            ss += r * r
        }
        return OffsetEstimate(a, base.rttMs, sqrt(ss / n), b * 1e6, good.size)
    }
}

enum class Status { IDLE, PLAYING, PAUSED }

/** Shared playback timeline. All times marked Host are on the host's clock. */
data class SessionState(
    val status: Status = Status.IDLE,
    val startHostMs: Double = 0.0,
    val startPosSec: Double = 0.0,
    val posSec: Double = 0.0,
    val track: String? = null,
)

sealed interface Command {
    /** [setTrack] false keeps the current track; true switches to [track] (null = built-in test signal). */
    data class Play(
        val startHostMs: Double,
        val posSec: Double? = null,
        val setTrack: Boolean = false,
        val track: String? = null,
    ) : Command

    data class Pause(val atHostMs: Double) : Command
    object Stop : Command
    data class Seek(val posSec: Double, val startHostMs: Double = 0.0) : Command
}

object Timeline {
    /** Track position (seconds) at a given host-clock time (ms). */
    fun positionAt(state: SessionState, hostNowMs: Double, durationSec: Double? = null): Double =
        when (state.status) {
            Status.PLAYING -> {
                val p = state.startPosSec + maxOf(0.0, hostNowMs - state.startHostMs) / 1000.0
                if (durationSec == null) p else minOf(p, durationSec)
            }
            Status.PAUSED -> state.posSec
            Status.IDLE -> 0.0
        }

    fun reduce(state: SessionState, cmd: Command, durationSec: Double? = null): SessionState {
        fun clamp(p: Double) = minOf(maxOf(p, 0.0), durationSec ?: Double.POSITIVE_INFINITY)
        return when (cmd) {
            is Command.Play -> {
                val p = clamp(cmd.posSec ?: state.posSec)
                SessionState(Status.PLAYING, cmd.startHostMs, p, p, if (cmd.setTrack) cmd.track else state.track)
            }
            is Command.Pause ->
                if (state.status != Status.PLAYING) state
                else SessionState(Status.PAUSED, 0.0, 0.0, positionAt(state, cmd.atHostMs, durationSec), state.track)
            Command.Stop -> SessionState()
            is Command.Seek -> {
                val p = clamp(cmd.posSec)
                if (state.status == Status.PLAYING) SessionState(Status.PLAYING, cmd.startHostMs, p, p, state.track)
                else SessionState(Status.PAUSED, 0.0, 0.0, p, state.track)
            }
        }
    }
}

/** Commands carry a sequence number; old or repeated ones are ignored. */
object CommandOrder {
    data class Result(val accept: Boolean, val lastSeq: Long)

    fun accept(lastSeq: Long, seq: Long?): Result =
        if (seq == null || seq <= lastSeq) Result(false, lastSeq) else Result(true, seq)
}

data class StartPlan(val waitMs: Double, val posSec: Double, val late: Boolean, val lateMs: Double)

object Scheduling {
    fun hostToLocal(hostMs: Double, offsetMs: Double): Double = hostMs - offsetMs

    /** Wait before starting; if already late, start now and skip ahead by the lateness. */
    fun planStart(startHostMs: Double, posSec: Double, offsetMs: Double, nowLocalMs: Double): StartPlan {
        val wait = hostToLocal(startHostMs, offsetMs) - nowLocalMs
        return if (wait >= 0.0) StartPlan(wait, posSec, false, 0.0)
        else StartPlan(0.0, posSec + (-wait) / 1000.0, true, -wait)
    }
}

enum class SyncHealth { GOOD, FAIR, POOR, UNKNOWN }

object Health {
    /** Thresholds are PROTOTYPE GUESSES, not verified targets. */
    fun of(spreadMs: Double?, rttMs: Double?): SyncHealth = when {
        spreadMs == null || rttMs == null -> SyncHealth.UNKNOWN
        spreadMs <= 5.0 && rttMs <= 150.0 -> SyncHealth.GOOD
        spreadMs <= 15.0 && rttMs <= 400.0 -> SyncHealth.FAIR
        else -> SyncHealth.POOR
    }
}
