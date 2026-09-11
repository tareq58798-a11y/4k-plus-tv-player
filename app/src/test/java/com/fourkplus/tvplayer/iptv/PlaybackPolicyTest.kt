package com.fourkplus.tvplayer.iptv

import org.junit.Assert.*
import org.junit.Test

class PlaybackPolicyTest {
    @Test fun permissionFailuresNeverTriggerAutomaticRetryOrFormatChanges() {
        for (http in listOf(401, 403)) {
            assertFalse(PlaybackPolicy.tryAlternate(http, 2004))
            assertFalse(PlaybackPolicy.reconnect(http, 2001))
            assertTrue(PlaybackPolicy.message(http, 2004).contains(http.toString()))
        }
    }
    @Test fun unavailableFormatCanUseAnAdvertisedAlternative() {
        assertTrue(PlaybackPolicy.tryAlternate(404, 2004))
        assertTrue(PlaybackPolicy.tryAlternate(415, 2004))
        assertTrue(PlaybackPolicy.tryAlternate(null, 3001))
        assertFalse(PlaybackPolicy.reconnect(404, 2004))
    }
    @Test fun onlyTemporaryFailuresReconnect() {
        assertTrue(PlaybackPolicy.reconnect(503, 2004))
        assertTrue(PlaybackPolicy.reconnect(null, 2002))
        assertFalse(PlaybackPolicy.reconnect(null, 4003))
        assertFalse(PlaybackPolicy.tryAlternate(null, 4003))
    }
    @Test fun errorsHaveActionableAndDistinctMessages() {
        val cases = listOf(401 to 2004, 403 to 2004, 404 to 2004, 503 to 2004,
            null to 2002, null to 3001, null to 4003, null to 2007, null to 1000)
        val messages = cases.map { PlaybackPolicy.message(it.first, it.second) }
        assertEquals(messages.size, messages.distinct().size)
        assertTrue(messages.none { it == "Playback failed." })
    }
}
