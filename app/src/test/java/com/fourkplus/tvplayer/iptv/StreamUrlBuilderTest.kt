package com.fourkplus.tvplayer.iptv

import com.fourkplus.tvplayer.data.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class StreamUrlBuilderTest {
    private val session = XtreamSession("http://example.com:80/", "test-user", "test-password")
    private fun spec(kind: MediaKind = MediaKind.LIVE, extension: String? = null) =
        PlaybackSpec(session, "123", kind, extension)

    @Test fun equivalentServerAddressesProduceTheSameApiUrl() {
        val expected = "http://example.com/player_api.php?username=test-user&password=test-password"
        listOf("http://example.com", "http://example.com/", "http://example.com:80", "http://example.com:80/").forEach {
            assertEquals(expected, StreamUrlBuilder.api(session.copy(server = it)))
        }
    }

    @Test fun protocolAndCustomPortArePreserved() {
        assertEquals("http://example.com:8080", StreamUrlBuilder.normalize(" http://example.com:8080/ "))
        assertEquals("https://example.com", StreamUrlBuilder.normalize("https://example.com/"))
    }

    @Test fun apiParametersAreEncodedExactlyOnce() {
        val input = session.copy(username = "a +/雪", password = "p&?=%#")
        val url = StreamUrlBuilder.api(input, "get_live_streams").toHttpUrl()
        assertEquals(input.username, url.queryParameter("username"))
        assertEquals(input.password, url.queryParameter("password"))
        assertEquals("get_live_streams", url.queryParameter("action"))
        assertEquals("/player_api.php", url.encodedPath)
    }

    @Test fun credentialsRemainIndividualPathSegments() {
        val input = session.copy(username = "u/a +雪", password = "p/a?#&=%")
        val url = StreamUrlBuilder.candidates(spec().copy(session = input)).first().toHttpUrl()
        assertEquals(listOf("live", input.username, input.password, "123.ts"), url.pathSegments)
        assertNull(url.query)
        assertNull(url.fragment)
    }

    @Test fun liveDefaultsToTsWithHlsAlternative() {
        assertEquals(listOf(
            "http://example.com/live/test-user/test-password/123.ts",
            "http://example.com/live/test-user/test-password/123.m3u8"
        ), StreamUrlBuilder.candidates(spec()))
    }

    @Test fun advertisedHlsOnlyNeverTriesTs() {
        val urls = StreamUrlBuilder.candidates(spec().copy(session = session.copy(formats = listOf("m3u8"))))
        assertEquals(1, urls.size)
        assertTrue(urls.single().endsWith(".m3u8"))
    }

    @Test fun channelContainerTakesPriorityWithinAllowedFormats() {
        assertEquals(listOf("m3u8", "ts"), StreamUrlBuilder.liveFormats(".M3U8", listOf("ts", "m3u8")))
    }

    @Test fun vodUsesReturnedContainerNotHardcodedMp4() {
        for (ext in listOf("mkv", "avi", "mp4", "ts")) {
            assertEquals("http://example.com/movie/test-user/test-password/123.$ext",
                StreamUrlBuilder.candidates(spec(MediaKind.MOVIE, ext)).single())
        }
    }

    @Test fun missingVodContainerIsAnExplicitError() {
        val failure = assertThrows(IptvFailure::class.java) { StreamUrlBuilder.candidates(spec(MediaKind.MOVIE)) }
        assertEquals(FailureKind.FORMAT, failure.kind)
    }

    @Test fun directSourceMustUseAuthenticatedOrigin() {
        val safe = spec().copy(directSource = "/live/test-user/test-password/123.m3u8")
        assertTrue(StreamUrlBuilder.candidates(safe).first().endsWith(".m3u8"))
        for (other in listOf("http://other.example/live/123.ts", "https://example.com/live/123.ts",
            "http://example.com:8080/live/123.ts", "http://user:secret@example.com/123.ts")) {
            assertEquals(StreamUrlBuilder.candidates(spec()), StreamUrlBuilder.candidates(spec().copy(directSource = other)))
        }
    }

    @Test fun invalidServerBasesAndStreamIdsAreRejected() {
        for (base in listOf("http://u:p@example.com", "http://example.com/api", "http://example.com/?secret=value",
            "http://example.com/#fragment", "file:///tmp")) {
            assertThrows(IllegalArgumentException::class.java) { StreamUrlBuilder.normalize(base) }
        }
        assertThrows(IllegalArgumentException::class.java) { StreamUrlBuilder.candidates(spec().copy(streamId = "../123")) }
    }

    @Test fun logsAndModelStringsNeverContainCredentials() {
        val item = PlaylistItem("Test", StreamUrlBuilder.candidates(spec()).first(), "Group", null, "123",
            MediaKind.LIVE, playback = spec())
        val strings = listOf(session.toString(), spec().toString(), item.toString(),
            PlaylistInput("Test", PlaylistKind.PROVIDER_LOGIN, session.server, session.username, session.password).toString(),
            StreamUrlBuilder.redactedPlayback(spec(), item.streamUrl + "?token=private-token"))
        strings.forEach {
            assertFalse(it.contains(session.username))
            assertFalse(it.contains(session.password))
            assertFalse(it.contains("private-token"))
        }
        assertEquals("http://example.com/live/REDACTED/REDACTED/123.ts", strings.last())
    }

    @Test fun onlyBakedInProviderServersAreAllowed() {
        for (address in ApprovedServers.addresses) {
            val source = PlaylistInput("Test", PlaylistKind.PROVIDER_LOGIN, address)
            assertTrue(ApprovedServers.allows(source))
            assertEquals(StreamUrlBuilder.normalize(address), ApprovedServers.candidates(source).first())
            assertEquals(2, ApprovedServers.candidates(source).size)
            assertFalse(ApprovedServers.allows(source.copy(kind = PlaylistKind.M3U_URL)))
        }
        assertFalse(ApprovedServers.allows(PlaylistInput("Test", PlaylistKind.PROVIDER_LOGIN, "http://other.example")))
    }
}
