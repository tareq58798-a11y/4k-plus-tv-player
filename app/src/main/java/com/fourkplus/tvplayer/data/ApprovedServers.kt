package com.fourkplus.tvplayer.data

import java.net.URI

object ApprovedServers {
    val addresses = listOf(
        "http://bag41135.wd.4kplus-tv-za.xyz/",
        "http://dtamadeus.com:80"
    )

    fun allows(input: PlaylistInput): Boolean =
        input.kind == PlaylistKind.PROVIDER_LOGIN && runCatching {
            val uri = URI(input.address.trim())
            uri.scheme.equals("http", true) &&
                uri.host?.lowercase() in setOf("bag41135.wd.4kplus-tv-za.xyz", "dtamadeus.com") &&
                uri.port in setOf(-1, 80) &&
                uri.path.orEmpty() in setOf("", "/") &&
                uri.rawQuery == null && uri.rawFragment == null && uri.rawUserInfo == null
        }.getOrDefault(false)
}
