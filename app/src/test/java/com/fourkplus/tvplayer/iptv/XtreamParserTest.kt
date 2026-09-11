package com.fourkplus.tvplayer.iptv

import com.fourkplus.tvplayer.data.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class XtreamParserTest {
    private val session = XtreamSession("http://example.com", "test-user", "test-password")

    @Test fun authenticationKeepsSelectedOriginAndEnteredCredentials() {
        val data = JSONObject("""{"user_info":{"auth":1,"status":"Active","exp_date":"1900000000",
            "username":"different","password":"different","allowed_output_formats":["m3u8"]},
            "server_info":{"url":"different.example","port":"443","server_protocol":"https"}}""")
        val authenticated = XtreamParser.authentication(data, session)
        assertEquals(session.server, authenticated.server)
        assertEquals(session.username, authenticated.username)
        assertEquals(session.password, authenticated.password)
        assertEquals(listOf("m3u8"), authenticated.formats)
        assertEquals(1900000000L, authenticated.expiry)
    }

    @Test fun stringAuthAndUnknownFormatsAreTolerated() {
        val data = JSONObject("""{"user_info":{"auth":"1","allowed_output_formats":["unknown"]}}""")
        assertEquals(listOf("ts", "m3u8"), XtreamParser.authentication(data, session).formats)
    }

    @Test fun invalidOrInactiveAuthenticationIsDistinctFromBadJson() {
        for (value in listOf("""{"auth":0}""", """{"auth":1,"status":"Expired"}""",
            """{"auth":1,"status":"Disabled"}""", """{"auth":1,"status":"Banned"}""")) {
            val failure = assertThrows(IptvFailure::class.java) {
                XtreamParser.authentication(JSONObject().put("user_info", JSONObject(value)), session)
            }
            assertEquals(FailureKind.AUTH, failure.kind)
        }
        val failure = assertThrows(IptvFailure::class.java) { XtreamParser.authentication(JSONObject(), session) }
        assertEquals(FailureKind.RESPONSE, failure.kind)
    }

    @Test fun categoriesSupportStringAndNumericIds() {
        val groups = XtreamParser.categories(JSONArray("""[{"category_id":"10","category_name":"News"},
            {"category_id":20,"category_name":"Sports"},null,{"category_name":"Invalid"}]"""))
        assertEquals(mapOf("10" to "News", "20" to "Sports"), groups)
    }

    @Test fun channelsRetainAllPlaybackMetadataWithoutLoadingLogos() {
        val array = JSONArray("""[{"stream_id":123,"name":"Test channel","category_id":"10",
            "stream_icon":"http://broken-logo.invalid/image","epg_channel_id":"test.epg",
            "stream_type":"live","container_extension":"m3u8"}]""")
        val item = XtreamParser.streams(array, mapOf("10" to "News"), session, MediaKind.LIVE).single()
        assertEquals("123", item.channelId)
        assertEquals("Test channel", item.name)
        assertEquals("10", item.categoryId)
        assertEquals("News", item.group)
        assertEquals("test.epg", item.epgChannelId)
        assertEquals("live", item.streamType)
        assertEquals("m3u8", item.containerExtension)
        assertTrue(item.streamUrl.endsWith("/123.m3u8"))
        assertEquals(session, item.playback?.session)
    }

    @Test fun missingLogoAndUnknownCategoryDoNotRemovePlayableChannels() {
        val array = JSONArray("""[{"stream_id":"1","name":"Test","stream_icon":null,"category_id":999},
            {"stream_id":"2"},null,{"stream_id":"bad/id"}]""")
        val items = XtreamParser.streams(array, emptyMap(), session, MediaKind.LIVE)
        assertEquals(2, items.size)
        assertNull(items.first().logoUrl)
        assertEquals("Other", items.first().group)
        assertTrue(items.all { it.streamUrl.endsWith(".ts") })
    }

    @Test fun emptyCatalogIsHandledWithoutInventingChannels() {
        assertTrue(XtreamParser.streams(JSONArray(), emptyMap(), session, MediaKind.LIVE).isEmpty())
    }

    @Test fun vodRetainsContainersAndReportsMissingContainerAtPlayback() {
        val array = JSONArray("""[{"stream_id":9,"name":"Test movie","container_extension":"mkv"},
            {"stream_id":10,"name":"Missing extension"}]""")
        val items = XtreamParser.streams(array, emptyMap(), session, MediaKind.MOVIE)
        assertEquals("mkv", items[0].containerExtension)
        assertTrue(items[0].streamUrl.endsWith("/movie/test-user/test-password/9.mkv"))
        assertEquals("", items[1].streamUrl)
        assertThrows(IptvFailure::class.java) { StreamUrlBuilder.candidates(items[1].playback!!) }
    }
}
