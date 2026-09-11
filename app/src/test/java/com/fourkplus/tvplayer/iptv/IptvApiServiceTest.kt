package com.fourkplus.tvplayer.iptv

import com.fourkplus.tvplayer.data.*
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** All requests are routed to localhost. No real account or provider is contacted. */
class IptvApiServiceTest {
    private lateinit var server: MockWebServer
    private lateinit var api: IptvApiService
    private val requested = Collections.synchronizedList(mutableListOf<HttpUrl>())
    private val input = PlaylistInput("Test account", PlaylistKind.PROVIDER_LOGIN,
        ApprovedServers.addresses.first(), "test-user", "test-password")
    private val auth = """{"user_info":{"auth":1,"status":"Active","allowed_output_formats":["ts","m3u8"]},
        "server_info":{"url":"untrusted.example"}}"""
    private val categories = """[{"category_id":"10","category_name":"News"}]"""
    private val streams = """[{"stream_id":42,"name":"Test channel","category_id":"10","stream_icon":null}]"""

    @Before fun setup() {
        server = MockWebServer()
        server.start()
        val client = IptvNetwork.client.newBuilder().addInterceptor { chain ->
            requested.add(chain.request().url)
            val local = chain.request().url.newBuilder().scheme("http")
                .host(server.hostName).port(server.port).build()
            chain.proceed(chain.request().newBuilder().url(local).build())
        }.build()
        api = IptvApiService(client)
    }
    @After fun teardown() { server.shutdown() }
    private fun enqueue(body: String) { server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body)) }

    @Test fun loginCategoriesChannelsAndPlaybackUrlFormOneSession() { runBlocking {
        enqueue(auth); enqueue(categories); enqueue(streams)
        val (session, playlist) = IptvCatalogLoader(api).load(input, true)
        assertEquals(StreamUrlBuilder.normalize(input.address), session.server)
        assertEquals(1, playlist.liveCount)
        val channel = playlist.items.single()
        assertEquals("News", channel.group)
        assertEquals(session, channel.playback!!.session)
        assertEquals(session.server + "/live/test-user/test-password/42.ts", channel.streamUrl)
        assertEquals(listOf(null, "get_live_categories", "get_live_streams"),
            requested.map { it.queryParameter("action") })
        assertTrue(requested.all { it.host == input.address.toHttpUrl().host })
        assertTrue(requested.all { it.queryParameter("username") == input.username && it.queryParameter("password") == input.password })
        repeat(3) {
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals(IptvNetwork.USER_AGENT, request.getHeader("User-Agent"))
            assertEquals("application/json", request.getHeader("Accept"))
        }
    } }

    @Test fun fallbackOnlyOccursBeforeAuthenticationSucceeds() { runBlocking {
        enqueue("""{"user_info":{"auth":0}}""")
        enqueue(auth); enqueue(categories); enqueue(streams)
        val (session, _) = IptvCatalogLoader(api).load(input, true)
        assertEquals(StreamUrlBuilder.normalize(ApprovedServers.addresses[1]), session.server)
        assertEquals(4, requested.size)
        assertEquals(input.address.toHttpUrl().host, requested.first().host)
        assertTrue(requested.drop(1).all { it.host == session.server.toHttpUrl().host })
    } }

    @Test fun catalog403DoesNotSwitchToAnotherServer() { runBlocking {
        enqueue(auth)
        server.enqueue(MockResponse().setResponseCode(403).setBody("Denied"))
        val failure = try { IptvCatalogLoader(api).load(input, true); error("Expected error") }
            catch (e: IptvFailure) { e }
        assertEquals(403, failure.httpCode)
        assertEquals(2, server.requestCount)
        assertEquals(1, requested.map { it.host }.distinct().size)
    } }

    @Test fun savedAccountRefreshDoesNotTryOtherServers() { runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        val failure = try { api.authenticate(input, false); error("Expected error") }
            catch (e: IptvFailure) { e }
        assertEquals(401, failure.httpCode)
        assertEquals(1, server.requestCount)
    } }

    @Test fun transientApiFailureIsRetriedOnce() { runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        enqueue(auth)
        assertEquals("Active", api.authenticate(input).status)
        assertEquals(2, server.requestCount)
    } }

    @Test fun repeatedTransientFailureStopsAfterBoundedRetry() { runBlocking {
        repeat(2) { server.enqueue(MockResponse().setResponseCode(503)) }
        val failure = try { api.authenticate(input); error("Expected error") }
            catch (e: IptvFailure) { e }
        assertEquals(503, failure.httpCode)
        assertEquals(2, server.requestCount)
    } }

    @Test fun htmlBodyIsNotTreatedAsAuthentication() { runBlocking {
        enqueue("<html>Not an API response</html>")
        val failure = try { api.authenticate(input); error("Expected error") }
            catch (e: IptvFailure) { e }
        assertEquals(FailureKind.RESPONSE, failure.kind)
        assertFalse(failure.message!!.contains(input.username))
        assertFalse(failure.message!!.contains(input.password))
    } }

    @Test fun bomPrefixedCatalogIsParsed() { runBlocking {
        enqueue("\uFEFF   " + categories)
        val session = XtreamSession(StreamUrlBuilder.normalize(input.address), input.username, input.password)
        assertEquals("News", XtreamParser.categories(api.array(session, "get_live_categories"))["10"])
    } }

    @Test fun cancelStopsPendingLoginWithoutFallbackOrLateSuccess() { runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val attempt = async { api.authenticate(input, true) }
        val started = withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) }
        assertNotNull(started)
        attempt.cancelAndJoin()
        assertTrue(attempt.isCancelled)
        assertEquals(1, server.requestCount)
    } }

    @Test fun redirectsAreFollowedWithoutReplacingTheSelectedServer() { runBlocking {
        server.enqueue(MockResponse().setResponseCode(302)
            .setHeader("Location", "/redirected?username=test-user&password=test-password"))
        enqueue(auth)
        val session = api.authenticate(input)
        assertEquals(StreamUrlBuilder.normalize(input.address), session.server)
        assertEquals(2, server.requestCount)
    } }

    @Test fun emptyLiveCatalogGivesSpecificError() { runBlocking {
        enqueue(auth); enqueue(categories); enqueue("[]")
        val failure = try { IptvCatalogLoader(api).load(input); error("Expected error") }
            catch (e: IptvFailure) { e }
        assertTrue(failure.message!!.contains("no live channels"))
    } }

    @Test fun vodRequestsUseTheSameAuthenticatedServerAndContainer() { runBlocking {
        enqueue(categories); enqueue("""[{"stream_id":87,"name":"Test movie","category_id":10,"container_extension":"mkv"}]""")
        val session = XtreamSession(StreamUrlBuilder.normalize(input.address), input.username, input.password)
        val movie = IptvCatalogLoader(api).loadVod(session).single()
        assertEquals(session.server + "/movie/test-user/test-password/87.mkv", movie.streamUrl)
        assertEquals(listOf("get_vod_categories", "get_vod_streams"), requested.map { it.queryParameter("action") })
        assertTrue(requested.all { it.host == session.server.toHttpUrl().host })
    } }

    @Test fun streamingClientHasTimeoutsButNoWholeVideoDeadline() {
        val client = IptvNetwork.client
        assertEquals(0, client.callTimeoutMillis)
        assertEquals(15_000, client.connectTimeoutMillis)
        assertEquals(25_000, client.readTimeoutMillis)
        assertTrue(client.followRedirects)
        assertTrue(client.followSslRedirects)
    }
}
