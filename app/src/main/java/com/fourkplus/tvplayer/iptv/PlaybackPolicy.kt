package com.fourkplus.tvplayer.iptv

/** Pure policy; Media3 codes are passed in so regression tests don't need an Android device. */
object PlaybackPolicy {
    fun tryAlternate(httpCode: Int?, playerCode: Int): Boolean =
        httpCode !in setOf(401, 403) && (httpCode in setOf(404, 415) || playerCode in setOf(2003, 3001, 3003))
    fun reconnect(httpCode: Int?, playerCode: Int): Boolean =
        httpCode !in setOf(401, 403, 404) &&
            ((httpCode ?: 0) in 500..599 || playerCode in setOf(1002, 2001, 2002))
    fun message(httpCode: Int?, playerCode: Int): String = when {
        httpCode == 401 || httpCode == 403 -> "The stream server denied access (HTTP $httpCode)."
        httpCode == 404 -> "This channel or video is unavailable (HTTP 404)."
        httpCode != null -> "The stream request failed (HTTP $httpCode)."
        playerCode == 2002 || playerCode == 1002 -> "The stream connection timed out."
        playerCode == 2001 -> "Unable to connect to the stream server."
        playerCode == 2007 -> "HTTP is blocked for the stream destination. Its host needs an approved network configuration."
        playerCode in setOf(2003, 3001, 3003, 4005) -> "The stream format is unsupported or the server returned non-video data."
        playerCode in 4001..4999 -> "The device decoder could not play this stream."
        else -> "A player error interrupted playback."
    } + " (player code $playerCode)"
}
