/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

/**
 * Turns a voice request ("Hey Google, play ... on Metrolist") into something Metrolist can play.
 *
 * Two sources of information are combined:
 * - the structured extras Google Assistant / Gemini sometimes sends (MediaStore.EXTRA_MEDIA_FOCUS,
 *   EXTRA_MEDIA_ARTIST, EXTRA_MEDIA_ALBUM, ...), which are the most reliable;
 * - the free text query, where French and English keywords are recognised
 *   ("l'album ...", "ma playlist ...", "mes j'aime", "my downloads", ...).
 *
 * Pure Kotlin (no Android types) so it can be unit tested.
 */
object VoiceQueryParser {
    enum class Type { ANY, LIKED, DOWNLOADED, PLAYLIST, ALBUM, ARTIST, SONG }

    data class Request(
        val type: Type,
        val query: String = "",
    )

    // Values of MediaStore.EXTRA_MEDIA_FOCUS
    const val FOCUS_ANY = "vnd.android.cursor.item/*"
    const val FOCUS_SONG = "vnd.android.cursor.item/audio"
    const val FOCUS_ALBUM = "vnd.android.cursor.item/album"
    const val FOCUS_ARTIST = "vnd.android.cursor.item/artist"
    const val FOCUS_PLAYLIST = "vnd.android.cursor.item/playlist"

    private val ANY_PHRASES = setOf(
        "", "music", "some music", "musique", "de la musique", "la musique", "ma musique", "my music",
        "something", "quelque chose", "des chansons", "songs",
    )

    private val LIKED_PHRASES = setOf(
        "liked songs", "my liked songs", "my likes", "likes", "liked", "my favorites", "my favourites",
        "favorites", "favourites", "my favorite songs", "my favourite songs",
        "j'aime", "mes j'aime", "jaime", "mes jaime", "les j'aime", "titres aimes", "mes titres aimes",
        "chansons aimees", "mes chansons aimees", "mes favoris", "favoris", "mes chansons preferees",
        "mes coups de coeur", "coups de coeur",
    )

    private val DOWNLOADED_PHRASES = setOf(
        "downloads", "my downloads", "downloaded songs", "my downloaded songs", "downloaded music",
        "my downloaded music", "offline music", "my offline music",
        "telechargements", "mes telechargements", "musique telechargee", "ma musique telechargee",
        "chansons telechargees", "mes chansons telechargees", "titres telecharges", "mes titres telecharges",
        "musique hors ligne", "ma musique hors ligne",
    )

    private val PLAYLIST_PREFIXES = listOf(
        "ma playlist ", "la playlist ", "une playlist ", "playlist ", "ma liste de lecture ", "la liste de lecture ",
        "liste de lecture ", "my playlist ", "the playlist ", "playlist called ", "my playlist called ",
    )
    private val PLAYLIST_SUFFIXES = listOf(" playlist")

    private val ALBUM_PREFIXES = listOf(
        "l'album ", "album ", "the album ", "album called ",
    )
    private val ALBUM_SUFFIXES = listOf(" album")

    private val ARTIST_PREFIXES = listOf(
        "l'artiste ", "artiste ", "the artist ", "artist ", "de la musique de ", "des chansons de ",
        "la musique de ", "les chansons de ", "les titres de ", "des titres de ", "music by ",
        "songs by ", "music from ", "songs from ", "some music by ", "something by ",
    )

    private val LEADING_VERBS = listOf("joue ", "jouer ", "mets ", "mettre ", "lance ", "play ", "put on ")
    private val TRAILING_APP = listOf(" sur metrolist", " on metrolist", " avec metrolist", " with metrolist")

    fun parse(
        query: String?,
        focus: String? = null,
        artist: String? = null,
        album: String? = null,
        playlist: String? = null,
        title: String? = null,
    ): Request {
        val text = cleanup(query.orEmpty())

        // 1. Structured extras win when present.
        when (focus) {
            FOCUS_ALBUM -> firstNonBlank(album, text)?.let { return Request(Type.ALBUM, it) }
            FOCUS_ARTIST -> firstNonBlank(artist, text)?.let { return Request(Type.ARTIST, it) }
            FOCUS_PLAYLIST -> firstNonBlank(playlist, text)?.let { return parsePlaylistName(it) }
            FOCUS_SONG -> {
                val songTitle = title?.trim().orEmpty()
                if (songTitle.isNotEmpty()) {
                    val songArtist = artist?.trim().orEmpty()
                    return Request(Type.SONG, if (songArtist.isEmpty()) songTitle else "$songTitle $songArtist")
                }
            }
        }

        // 2. Free text keywords.
        val key = normalize(text)
        if (key in ANY_PHRASES) return Request(Type.ANY)
        if (key in LIKED_PHRASES) return Request(Type.LIKED)
        if (key in DOWNLOADED_PHRASES) return Request(Type.DOWNLOADED)

        stripAffix(text, PLAYLIST_PREFIXES, PLAYLIST_SUFFIXES)?.let { return parsePlaylistName(it) }
        stripAffix(text, ALBUM_PREFIXES, ALBUM_SUFFIXES)?.let { return Request(Type.ALBUM, it) }
        stripAffix(text, ARTIST_PREFIXES, emptyList())?.let { return Request(Type.ARTIST, it) }

        return Request(Type.SONG, text)
    }

    /** "ma playlist j'aime" is the liked songs, not a playlist called "j'aime". */
    private fun parsePlaylistName(name: String): Request {
        val key = normalize(name)
        return when (key) {
            in LIKED_PHRASES -> Request(Type.LIKED)
            in DOWNLOADED_PHRASES -> Request(Type.DOWNLOADED)
            else -> Request(Type.PLAYLIST, name)
        }
    }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }?.trim()

    /** Removes "joue" / "play" at the start and "sur Metrolist" at the end, keeping the original casing. */
    private fun cleanup(query: String): String {
        var text = query.replace('’', '\'').replace(Regex("\\s+"), " ").trim()
        LEADING_VERBS.firstOrNull { text.startsWith(it, ignoreCase = true) }?.let {
            text = text.substring(it.length).trim()
        }
        TRAILING_APP.firstOrNull { text.endsWith(it, ignoreCase = true) }?.let {
            text = text.substring(0, text.length - it.length).trim()
        }
        return text
    }

    private fun stripAffix(text: String, prefixes: List<String>, suffixes: List<String>): String? {
        prefixes.firstOrNull { text.startsWith(it, ignoreCase = true) }?.let {
            return text.substring(it.length).trim().ifEmpty { null }
        }
        suffixes.firstOrNull { text.endsWith(it, ignoreCase = true) }?.let {
            val rest = text.substring(0, text.length - it.length).trim()
            // "play my workout playlist" -> "workout"
            val withoutOwner = listOf("my ", "the ").firstOrNull { p -> rest.startsWith(p, ignoreCase = true) }
                ?.let { p -> rest.substring(p.length).trim() } ?: rest
            return withoutOwner.ifEmpty { null }
        }
        return null
    }

    internal fun normalize(text: String): String =
        VoiceSearchMatcher.stripAccents(text.lowercase())
            .replace('’', '\'')
            .replace(Regex("[^\\p{L}\\p{N}' ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
