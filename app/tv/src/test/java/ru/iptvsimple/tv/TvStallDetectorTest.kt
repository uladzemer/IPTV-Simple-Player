package ru.iptvsimple.tv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TvStallDetectorTest {
    private val buffering = 2
    private val ready = 3
    private val ended = 4

    private fun detector() = TvStallDetector(
        startTimeoutMillis = 15_000,
        bufferingTimeoutMillis = 12_000,
        frozenTimeoutMillis = 8_000,
    ).apply { reset(0) }

    @Test
    fun `channel that never starts is restarted after the start timeout`() {
        val d = detector()
        assertNull(d.check(now = 14_000, state = buffering, playWhenReady = true, position = 0))
        assertEquals(TvStallReason.NO_START, d.check(now = 15_000, state = buffering, playWhenReady = true, position = 0))
    }

    @Test
    fun `normal playback with moving position is left alone`() {
        val d = detector()
        for (t in 1..30) {
            assertNull(d.check(now = t * 2_000L, state = ready, playWhenReady = true, position = t * 2_000L))
        }
    }

    @Test
    fun `frozen position while playing is detected`() {
        val d = detector()
        assertNull(d.check(now = 2_000, state = ready, playWhenReady = true, position = 500))
        assertNull(d.check(now = 6_000, state = ready, playWhenReady = true, position = 500))
        assertEquals(TvStallReason.FROZEN, d.check(now = 10_000, state = ready, playWhenReady = true, position = 500))
    }

    @Test
    fun `paused playback is never treated as frozen`() {
        val d = detector()
        for (t in 1..20) {
            assertNull(d.check(now = t * 2_000L, state = ready, playWhenReady = false, position = 500))
        }
    }

    @Test
    fun `long buffering after playback started is detected`() {
        val d = detector()
        assertNull(d.check(now = 2_000, state = ready, playWhenReady = true, position = 1_000))
        assertNull(d.check(now = 4_000, state = buffering, playWhenReady = true, position = 1_000))
        assertNull(d.check(now = 14_000, state = buffering, playWhenReady = true, position = 1_000))
        assertEquals(TvStallReason.STUCK_BUFFERING, d.check(now = 16_000, state = buffering, playWhenReady = true, position = 1_000))
    }

    @Test
    fun `short buffering hiccups are tolerated`() {
        val d = detector()
        assertNull(d.check(now = 2_000, state = ready, playWhenReady = true, position = 1_000))
        assertNull(d.check(now = 4_000, state = buffering, playWhenReady = true, position = 1_000))
        assertNull(d.check(now = 10_000, state = ready, playWhenReady = true, position = 3_000))
        assertNull(d.check(now = 12_000, state = buffering, playWhenReady = true, position = 3_000))
        assertNull(d.check(now = 20_000, state = buffering, playWhenReady = true, position = 3_000))
    }

    @Test
    fun `after a soft resume buffering gets a fresh timeout, not the start timeout`() {
        val d = detector()
        assertNull(d.check(now = 2_000, state = ready, playWhenReady = true, position = 1_000))
        assertNull(d.check(now = 4_000, state = buffering, playWhenReady = true, position = 1_000))
        assertEquals(TvStallReason.STUCK_BUFFERING, d.check(now = 16_000, state = buffering, playWhenReady = true, position = 1_000))
        d.restartBufferingTimer(16_000)
        assertNull(d.check(now = 26_000, state = buffering, playWhenReady = true, position = 1_000))
        assertEquals(TvStallReason.STUCK_BUFFERING, d.check(now = 28_000, state = buffering, playWhenReady = true, position = 1_000))
    }

    @Test
    fun `ended live stream is restarted`() {
        assertEquals(TvStallReason.ENDED, detector().check(now = 1_000, state = ended, playWhenReady = true, position = 0))
    }
}
