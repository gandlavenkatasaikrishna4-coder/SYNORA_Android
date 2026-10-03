package com.synora.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ClockSyncTest {
    @Test
    fun symmetricDelayGivesExactOffset() {
        // receiver clock 500 ms behind host, 20 ms each way
        val s = ClockSync.sample(1000.0, 1520.0, 1521.0, 1041.0)
        assertEquals(500.0, s.offsetMs, 1e-9)
        assertEquals(40.0, s.rttMs, 1e-9)
    }

    @Test
    fun medianOddAndEven() {
        assertEquals(2.0, ClockSync.median(listOf(3.0, 1.0, 2.0)), 1e-9)
        assertEquals(2.5, ClockSync.median(listOf(1.0, 2.0, 3.0, 4.0)), 1e-9)
    }

    @Test
    fun pickOffsetPrefersLowRoundTrip() {
        val r = ClockSync.pickOffset(
            listOf(
                ClockSample(500.0, 20.0, 0.0), ClockSample(501.0, 22.0, 0.0), ClockSample(499.0, 25.0, 0.0),
                ClockSample(560.0, 300.0, 0.0), ClockSample(430.0, 400.0, 0.0),
            ),
            keep = 3,
        )
        assertNotNull(r)
        assertEquals(500.0, r.offsetMs, 1e-9)
        assertEquals(20.0, r.rttMs, 1e-9)
        assertEquals(2.0, r.spreadMs, 1e-9)
        assertEquals(3, r.used)
    }

    @Test
    fun pickOffsetEmptyAndInvalid() {
        assertNull(ClockSync.pickOffset(emptyList()))
        assertNull(ClockSync.pickOffset(listOf(ClockSample(Double.NaN, 5.0, 0.0), ClockSample(1.0, -1.0, 0.0))))
    }

    @Test
    fun predictOffsetTracksTwoHundredPpmDrift() {
        val smp = (0..30).map { i ->
            val t = i * 1000.0
            ClockSample(offsetMs = 500.0 + 0.0002 * t, rttMs = 20.0 + (i % 3) * 5.0, atMs = t)
        }
        val r = ClockSync.predictOffset(smp, 30_000.0, 3)
        assertNotNull(r)
        assertEquals(506.0, r.offsetMs, 0.01)
        assertEquals(200.0, assertNotNull(r.slopePpm), 0.5)
    }

    @Test
    fun predictOffsetFallsBackWithLittleData() {
        val smp = listOf(ClockSample(500.0, 20.0, 0.0), ClockSample(502.0, 22.0, 400.0), ClockSample(498.0, 25.0, 800.0))
        val r = ClockSync.predictOffset(smp, 1000.0, 3)
        assertNotNull(r)
        assertNull(r.slopePpm)
        assertEquals(500.0, r.offsetMs, 1e-9)
        assertNull(ClockSync.predictOffset(emptyList(), 0.0, 3))
    }

    @Test
    fun predictOffsetIgnoresAbsurdFit() {
        val smp = (0..30).map { i -> ClockSample(500.0 + 0.01 * i * 1000.0, 20.0, i * 1000.0) }
        val r = ClockSync.predictOffset(smp, 30_000.0, 3)
        assertNotNull(r)
        assertNull(r.slopePpm)
    }
}

class TimelineTest {
    private val idle = SessionState()

    @Test
    fun positionPlayingPausedIdleAndClamp() {
        val play = Timeline.reduce(idle, Command.Play(startHostMs = 1000.0, posSec = 10.0), 120.0)
        assertEquals(10.0, Timeline.positionAt(play, 1000.0, 120.0), 1e-9)
        assertEquals(12.5, Timeline.positionAt(play, 3500.0, 120.0), 1e-9)
        assertEquals(10.0, Timeline.positionAt(play, 500.0, 120.0), 1e-9)   // before start: hold
        assertEquals(120.0, Timeline.positionAt(play, 1e9, 120.0), 1e-9)    // clamped
        assertEquals(0.0, Timeline.positionAt(idle, 5000.0, 120.0), 1e-9)
    }

    @Test
    fun pauseFreezesThenResume() {
        var s = Timeline.reduce(idle, Command.Play(0.0, 0.0), 120.0)
        s = Timeline.reduce(s, Command.Pause(4000.0), 120.0)
        assertEquals(Status.PAUSED, s.status)
        assertEquals(4.0, s.posSec, 1e-9)
        s = Timeline.reduce(s, Command.Play(9000.0, s.posSec), 120.0)
        assertEquals(6.0, Timeline.positionAt(s, 11000.0, 120.0), 1e-9)
    }

    @Test
    fun pauseWhenNotPlayingChangesNothing() {
        assertSame(idle, Timeline.reduce(idle, Command.Pause(1.0), 120.0))
    }

    @Test
    fun seekWhilePlayingAndPaused() {
        var s = Timeline.reduce(idle, Command.Play(0.0, 0.0), 120.0)
        s = Timeline.reduce(s, Command.Seek(60.0, 5000.0), 120.0)
        assertEquals(Status.PLAYING, s.status)
        assertEquals(62.0, Timeline.positionAt(s, 7000.0, 120.0), 1e-9)
        s = Timeline.reduce(s, Command.Pause(7000.0), 120.0)
        s = Timeline.reduce(s, Command.Seek(30.0), 120.0)
        assertEquals(Status.PAUSED, s.status)
        assertEquals(30.0, s.posSec, 1e-9)
    }

    @Test
    fun seekIsClampedToTrack() {
        val s = Timeline.reduce(idle, Command.Seek(-5.0), 120.0)
        assertEquals(0.0, s.posSec, 1e-9)
        assertEquals(120.0, Timeline.reduce(s, Command.Seek(999.0), 120.0).posSec, 1e-9)
    }

    @Test
    fun stopResets() {
        val s = Timeline.reduce(Timeline.reduce(idle, Command.Play(0.0, 5.0), 120.0), Command.Stop, 120.0)
        assertEquals(SessionState(), s)
    }

    @Test
    fun trackFollowsPlayPauseSeek() {
        var s = Timeline.reduce(idle, Command.Play(0.0, 0.0, setTrack = true, track = "s1"), 100.0)
        assertEquals("s1", s.track)
        s = Timeline.reduce(s, Command.Pause(1000.0), 100.0)
        assertEquals("s1", s.track)
        s = Timeline.reduce(s, Command.Seek(5.0), 100.0)
        assertEquals("s1", s.track)
        s = Timeline.reduce(s, Command.Play(0.0, 5.0), 100.0)   // setTrack false keeps it
        assertEquals("s1", s.track)
    }

    @Test
    fun commandOrdering() {
        var r = CommandOrder.accept(0, 1)
        assertTrue(r.accept)
        assertEquals(1L, r.lastSeq)
        r = CommandOrder.accept(5, 5)
        assertFalse(r.accept)
        r = CommandOrder.accept(5, 3)
        assertFalse(r.accept)
        assertEquals(5L, r.lastSeq)
        assertTrue(CommandOrder.accept(5, 9).accept)            // gaps allowed
        assertFalse(CommandOrder.accept(0, null).accept)
    }

    @Test
    fun planStartOnTimeAndLate() {
        val onTime = Scheduling.planStart(10_000.0, 0.0, 500.0, 9_400.0)
        assertEquals(100.0, onTime.waitMs, 1e-9)
        assertFalse(onTime.late)
        val late = Scheduling.planStart(10_000.0, 20.0, 500.0, 9_700.0)   // 200 ms late
        assertEquals(0.0, late.waitMs, 1e-9)
        assertTrue(late.late)
        assertEquals(200.0, late.lateMs, 1e-9)
        assertEquals(20.2, late.posSec, 1e-9)
    }

    @Test
    fun healthThresholds() {
        assertEquals(SyncHealth.GOOD, Health.of(2.0, 50.0))
        assertEquals(SyncHealth.FAIR, Health.of(10.0, 200.0))
        assertEquals(SyncHealth.POOR, Health.of(30.0, 50.0))
        assertEquals(SyncHealth.UNKNOWN, Health.of(null, null))
    }
}
