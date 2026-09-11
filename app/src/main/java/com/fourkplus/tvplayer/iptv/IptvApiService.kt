package com.fourkplus.tvplayer.iptv

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

enum class FailureKind { AUTH, HTTP, TIMEOUT, NETWORK, FORMAT, RESPONSE }
class IptvFailure(message: String, val kind: FailureKind, val httpCode: Int? = null) : IOException(message)

object IptvNetwork {
    const val USER_AGENT = "4KPlusTVPlayer/0.15.0"
    // A long video stream must not have a whole-call deadline.
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true).followSslRedirects(true).build()
}

class IptvApiService(client: OkHttpClient = IptvNetwork.client) {
    private val apiClient = client.newBuilder().callTimeout(45, TimeUnit.SECONDS).build()

    suspend fun authenticate(input: com.fourkplus.tvplayer.data.PlaylistInput, allowFallback: Boolean = false): XtreamSession {
        var last: IptvFailure? = null
        val servers = ApprovedServers.candidates(input).let { if (allowFallback) it else it.take(1) }
        for (server in servers) {
            try {
                val session = XtreamSession(server, input.username, input.password)
                return XtreamParser.authentication(jsonObject(session), session)
            } catch (e: CancellationException) { throw e }
            catch (e: IptvFailure) { last = e }
        }
        throw last ?: IptvFailure("Unable to authenticate.", FailureKind.AUTH)
    }

    suspend fun jsonObject(session: XtreamSession, action: String? = null): JSONObject =
        try { JSONObject(get(StreamUrlBuilder.api(session, action)).trimStart('\uFEFF', ' ', '\n', '\r')) }
        catch (e: org.json.JSONException) { throw IptvFailure("The login API returned an invalid response.", FailureKind.RESPONSE) }

    suspend fun array(session: XtreamSession, action: String): JSONArray =
        try { JSONArray(get(StreamUrlBuilder.api(session, action)).trimStart('\uFEFF', ' ', '\n', '\r')) }
        catch (e: org.json.JSONException) { throw IptvFailure("The catalog API returned an invalid response.", FailureKind.RESPONSE) }

    // One retry for transient API failures. 401/403/404 never retried here.
    private suspend fun get(url: String): String {
        repeat(2) { attempt ->
            try { return execute(url) }
            catch (e: IptvFailure) {
                val transient = e.kind in setOf(FailureKind.TIMEOUT, FailureKind.NETWORK) || (e.httpCode ?: 0) in 500..599
                if (attempt == 1 || !transient) throw e
                delay(500)
            }
        }
        error("Unreachable")
    }

    private suspend fun execute(url: String): String = suspendCancellableCoroutine { continuation ->
        val call = apiClient.newCall(Request.Builder().url(url)
            .header("User-Agent", IptvNetwork.USER_AGENT).header("Accept", "application/json").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(
                    IptvFailure(if (e is SocketTimeoutException) "The API request timed out." else "The API could not be reached.",
                        if (e is SocketTimeoutException) FailureKind.TIMEOUT else FailureKind.NETWORK))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val text = response.use {
                        if (!it.isSuccessful) throw IptvFailure(
                            when (it.code) {
                                401, 403 -> "The API denied access (HTTP ${it.code})."
                                404 -> "The API endpoint was not found (HTTP 404)."
                                else -> "The API request failed (HTTP ${it.code})."
                            }, FailureKind.HTTP, it.code)
                        val body = it.body ?: throw IptvFailure("The API returned no data.", FailureKind.RESPONSE)
                        body.charStream().use { reader ->
                            buildString {
                                val buffer = CharArray(8192)
                                while (continuation.isActive) {
                                    val n = reader.read(buffer)
                                    if (n < 0) break
                                    if (length.toLong() + n > 80_000_000) throw IptvFailure("The catalog is too large.", FailureKind.RESPONSE)
                                    append(buffer, 0, n)
                                }
                            }
                        }
                    }
                    if (continuation.isActive) continuation.resume(text)
                } catch (e: Exception) {
                    val safe = e as? IptvFailure ?: if (e is SocketTimeoutException) {
                        IptvFailure("The API response timed out.", FailureKind.TIMEOUT)
                    } else IptvFailure("Unable to read the API response.", FailureKind.NETWORK)
                    if (continuation.isActive) continuation.resumeWithException(safe)
                }
            }
        })
    }
}
