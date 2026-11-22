package app.tvlauncher

/** De donde salen las sugerencias de una app. */
enum class Kind { PROVIDER, TRENDING, NONE }

data class Source(val kind: Kind, val providerId: Int = 0, val label: String = "")

/**
 * Agrupa las apps por tipo y define que catalogo (si lo hay) corresponde a cada una.
 * Una app sin catalogo propio NO muestra sugerencias de otra plataforma.
 */
object AppGroups {
    val tabs = listOf("Streaming", "TV", "Mis medios", "Música y video", "Más")

    private val tabOf = mapOf(
        // Streaming
        "com.netflix.ninja" to 0, "com.disney.disneyplus" to 0, "com.wbd.stream" to 0,
        "com.amazon.amazonvideo.livingroom" to 0, "com.apple.atve.androidtv.appletv" to 0,
        "com.crunchyroll.crunchyroid" to 0,
        // TV y operadores
        "co.movistar.go" to 1, "com.directv.dtvlatam" to 1, "com.caracol.streaming" to 1,
        "com.sony.dtv.tvx" to 1, "com.sony.dtv.tvxlauncher.programguide" to 1,
        // Mis medios (bibliotecas y reproductores propios)
        "com.stremio.one" to 2, "org.xbmc.kodi" to 2, "org.jellyfin.androidtv" to 2,
        "com.plexapp.android" to 2, "org.videolan.vlc" to 2, "app.tvtorrent" to 2,
        // Musica y video en linea
        "com.google.android.youtube.tv" to 3, "com.google.android.youtube.tvmusic" to 3,
        "com.spotify.tv.android" to 3
    )

    fun tabOf(pkg: String): Int = tabOf[pkg] ?: 4

    private val providers = mapOf(
        "com.netflix.ninja" to Pair(8, "Popular en Netflix"),
        "com.disney.disneyplus" to Pair(337, "Popular en Disney+"),
        "com.wbd.stream" to Pair(1899, "Popular en HBO Max"),
        "com.amazon.amazonvideo.livingroom" to Pair(119, "Popular en Prime Video"),
        "com.apple.atve.androidtv.appletv" to Pair(350, "Popular en Apple TV"),
        "com.crunchyroll.crunchyroid" to Pair(283, "Popular en Crunchyroll"),
        "com.plexapp.android" to Pair(538, "Popular en Plex (gratis)"),
        "com.directv.dtvlatam" to Pair(467, "Popular en DirecTV Go"),
        "co.movistar.go" to Pair(339, "Popular en Movistar TV")
    )

    fun sourceOf(pkg: String): Source {
        providers[pkg]?.let { return Source(Kind.PROVIDER, it.first, it.second + " (Colombia)") }
        if (pkg == "com.stremio.one") return Source(Kind.TRENDING, 0, "Tendencias de la semana (cualquier plataforma)")
        return Source(Kind.NONE)
    }
}
