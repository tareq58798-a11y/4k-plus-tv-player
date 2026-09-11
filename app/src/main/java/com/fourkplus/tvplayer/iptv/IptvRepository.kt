package com.fourkplus.tvplayer.iptv

import android.content.Context
import com.fourkplus.tvplayer.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class IptvRepository(context: Context) {
    private val store = IptvAccountStore(context)
    private val loader = IptvCatalogLoader()
    private val mutex = Mutex()

    fun savedSources() = store.sources()
    fun savedSource() = store.active()
    fun selectSavedSource(source: PlaylistInput) = store.select(source)
    fun renameSavedSource(name: String) = store.rename(name)
    fun clearSavedSource() = store.removeActive()
    suspend fun loadCached(source: PlaylistInput? = savedSource()): LoadedPlaylist? = withContext(Dispatchers.IO) {
        source?.takeIf(ApprovedServers::allows)?.let(store::readCache)
    }
    suspend fun connect(input: PlaylistInput): Result<LoadedPlaylist> = loadInternal(input, true, true)
    suspend fun load(input: PlaylistInput): Result<LoadedPlaylist> = loadInternal(input, false, true)
    suspend fun loadWithoutSelecting(input: PlaylistInput): Result<LoadedPlaylist> = loadInternal(input, false, false)

    private suspend fun loadInternal(input: PlaylistInput, fallback: Boolean, activate: Boolean): Result<LoadedPlaylist> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    require(ApprovedServers.allows(input)) { "Choose Server 1 or Server 2." }
                    val (session, playlist) = loader.load(input, fallback)
                    currentCoroutineContext().ensureActive()
                    val authenticatedSource = input.copy(address = session.server)
                    store.writeCache(authenticatedSource, session, playlist)
                    currentCoroutineContext().ensureActive()
                    store.save(authenticatedSource, activate)
                    Result.success(playlist)
                } catch (e: CancellationException) { throw e }
                catch (e: IptvFailure) { Result.failure(e) }
                catch (e: Exception) { Result.failure(IllegalArgumentException("The playlist could not be loaded. Check your account.")) }
            }
        }

    // Existing navigation/detail signatures retained; no legacy network implementation remains.
    suspend fun movieDetails(movie: PlaylistItem): Result<MovieDetailsInfo> =
        Result.failure(IllegalStateException("VOD will be enabled after Live TV device verification."))
    suspend fun seriesDetails(series: PlaylistItem): Result<SeriesDetailsInfo> =
        Result.failure(IllegalStateException("Series will be enabled after Live TV device verification."))
}
