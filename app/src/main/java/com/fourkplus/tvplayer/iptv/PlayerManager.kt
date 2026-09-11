package com.fourkplus.tvplayer.iptv

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.fourkplus.tvplayer.BuildConfig
import com.fourkplus.tvplayer.data.MediaKind
import com.fourkplus.tvplayer.data.PlaylistItem
import okhttp3.HttpUrl.Companion.toHttpUrl

/** A single owner for source selection, retries, errors and Media3 lifecycle. */
class PlayerManager(
    private val context: Context,
    skipSeconds: Int,
    private val onError: (String?) -> Unit
) : Player.Listener {
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var selected: PlaybackSpec? = null
    private var sources: List<String> = emptyList()
    private var sourceIndex = 0
    private var reconnects = 0
    private var subtitle: Uri? = null
    private var closed = false
    private var initialPosition = 0L
    val player: ExoPlayer

    init {
        // Media3's default exception logs may contain unredacted DataSpec URLs.
        androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_OFF)
        val client = IptvNetwork.client.newBuilder().addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            selected?.let { spec ->
                val type = response.header("Content-Type")?.substringBefore(';')
                    ?.takeIf { it.matches(Regex("[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+")) } ?: "unknown"
                debug("response url=${StreamUrlBuilder.redactedPlayback(spec, response.request.url.toString())} http=${response.code} type=$type")
            }
            response
        }.build()
        val network = OkHttpDataSource.Factory(client).setUserAgent(IptvNetwork.USER_AGENT)
        val sources = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, network))
            .setLoadErrorHandlingPolicy(object : DefaultLoadErrorHandlingPolicy(2) {
                override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
                    val code = httpCode(info.exception)
                    if (code in setOf(401, 403, 404, 415) || info.errorCount > 2) return C.TIME_UNSET
                    return super.getRetryDelayMsFor(info)
                }
            })
        player = ExoPlayer.Builder(context, DefaultRenderersFactory(context).setEnableDecoderFallback(true))
            .setMediaSourceFactory(sources)
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 50_000, 1_500, 3_000).build())
            .setSeekBackIncrementMs(skipSeconds * 1000L)
            .setSeekForwardIncrementMs(skipSeconds * 1000L)
            .build()
        player.setHandleAudioBecomingNoisy(true)
        player.addListener(this)
    }

    fun open(item: PlaylistItem?, subtitle: Uri? = null, startPosition: Long = 0) {
        check(!closed)
        stop()
        if (item == null) return
        val spec = item.playback
        if (spec == null) {
            onError("This saved item must be refreshed before playback.")
            return
        }
        if (spec.kind != MediaKind.LIVE) {
            onError("VOD playback is deferred until Live TV is verified.")
            return
        }
        val urls = try { StreamUrlBuilder.candidates(spec) } catch (e: Exception) {
            onError("The provider supplied invalid stream information."); return
        }
        active?.takeIf { it !== this }?.stop()
        active = this
        selected = spec
        sources = urls
        this.subtitle = subtitle
        initialPosition = startPosition.coerceAtLeast(0)
        prepareSource()
    }

    private fun prepareSource() {
        if (closed || selected == null || sources.isEmpty()) return
        val spec = selected!!
        val url = sources[sourceIndex]
        val ext = url.toHttpUrl().pathSegments.last().substringAfterLast('.', "").lowercase()
        val mime = when (ext) {
            "m3u8" -> MimeTypes.APPLICATION_M3U8
            "ts" -> MimeTypes.VIDEO_MP2T
            "mp4", "m4v" -> MimeTypes.VIDEO_MP4
            "mkv" -> MimeTypes.VIDEO_MATROSKA
            else -> null
        }
        val builder = MediaItem.Builder().setUri(url).setMediaId(spec.streamId)
        if (mime != null) builder.setMimeType(mime)
        subtitle?.let {
            val type = runCatching { context.contentResolver.getType(it) }.getOrNull().orEmpty()
            builder.setSubtitleConfigurations(listOf(MediaItem.SubtitleConfiguration.Builder(it)
                .setMimeType(if (type.contains("vtt", true)) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()))
        }
        debug("start server=${spec.session.server.toHttpUrl().host} id=${spec.streamId} url=${StreamUrlBuilder.redactedPlayback(spec, url)} mime=${mime ?: "auto"}")
        player.setMediaItem(builder.build(), initialPosition)
        player.prepare()
        player.play()
    }

    override fun onPlayerError(error: PlaybackException) {
        val spec = selected ?: return
        val http = httpCode(error)
        debug("error id=${spec.streamId} http=${http ?: "none"} player=${error.errorCode} exo=${error.errorCodeName}")
        if (PlaybackPolicy.tryAlternate(http, error.errorCode) && sourceIndex < sources.lastIndex) {
            sourceIndex++
            onError("Trying the other supported live format…")
            handler.postDelayed({ prepareSource() }, 400)
        } else if (PlaybackPolicy.reconnect(http, error.errorCode) && reconnects < 2) {
            reconnects++
            onError("Reconnecting to the stream ($reconnects/2)…")
            handler.postDelayed({ prepareSource() }, reconnects * 1000L)
        } else {
            onError(PlaybackPolicy.message(http, error.errorCode))
        }
    }

    override fun onPlaybackStateChanged(state: Int) {
        if (state == Player.STATE_READY) onError(null)
        if (state == Player.STATE_ENDED && selected?.kind == MediaKind.LIVE) {
            if (reconnects < 2) {
                reconnects++
                handler.postDelayed({ prepareSource() }, 1000)
            } else onError("The live channel ended. Select it again to reconnect.")
        }
    }

    fun stop() {
        if (closed) return
        handler.removeCallbacksAndMessages(null)
        selected = null
        sources = emptyList()
        sourceIndex = 0
        reconnects = 0
        player.stop()
        player.clearMediaItems()
        onError(null)
        if (active === this) active = null
    }
    fun close() {
        if (closed) return
        stop()
        player.removeListener(this)
        player.release()
        closed = true
    }
    private fun debug(message: String) { if (BuildConfig.DEBUG) Log.d("IptvPlayback", message) }

    companion object {
        private var active: PlayerManager? = null
        private fun httpCode(error: Throwable): Int? {
            var cause: Throwable? = error
            repeat(12) {
                val current = cause ?: return null
                if (current is HttpDataSource.InvalidResponseCodeException) return current.responseCode
                cause = current.cause
            }
            return null
        }
    }
}
