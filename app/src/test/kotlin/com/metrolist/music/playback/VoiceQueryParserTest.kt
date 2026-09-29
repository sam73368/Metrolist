/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import com.metrolist.music.playback.VoiceQueryParser.Request
import com.metrolist.music.playback.VoiceQueryParser.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceQueryParserTest {
    private fun parse(query: String?) = VoiceQueryParser.parse(query)

    @Test
    fun `empty or generic query plays anything`() {
        assertEquals(Request(Type.ANY), parse(null))
        assertEquals(Request(Type.ANY), parse(""))
        assertEquals(Request(Type.ANY), parse("de la musique"))
        assertEquals(Request(Type.ANY), parse("Music"))
        assertEquals(Request(Type.ANY), parse("joue de la musique sur Metrolist"))
    }

    @Test
    fun `liked and downloaded keywords`() {
        assertEquals(Request(Type.LIKED), parse("mes j'aime"))
        assertEquals(Request(Type.LIKED), parse("Mes J’aime"))
        assertEquals(Request(Type.LIKED), parse("my liked songs"))
        assertEquals(Request(Type.LIKED), parse("mes coups de cœur"))
        assertEquals(Request(Type.LIKED), parse("ma playlist j'aime"))
        assertEquals(Request(Type.DOWNLOADED), parse("mes téléchargements"))
        assertEquals(Request(Type.DOWNLOADED), parse("my downloads"))
        assertEquals(Request(Type.DOWNLOADED), parse("ma musique hors ligne"))
    }

    @Test
    fun `playlist album and artist keywords`() {
        assertEquals(Request(Type.PLAYLIST, "Sport"), parse("ma playlist Sport"))
        assertEquals(Request(Type.PLAYLIST, "Chill"), parse("la playlist Chill sur Metrolist"))
        assertEquals(Request(Type.PLAYLIST, "workout"), parse("my workout playlist"))
        assertEquals(Request(Type.ALBUM, "Random Access Memories"), parse("l'album Random Access Memories"))
        assertEquals(Request(Type.ALBUM, "Thriller"), parse("the album Thriller"))
        assertEquals(Request(Type.ARTIST, "Angèle"), parse("de la musique de Angèle"))
        assertEquals(Request(Type.ARTIST, "Daft Punk"), parse("music by Daft Punk"))
        assertEquals(Request(Type.ARTIST, "Stromae"), parse("l’artiste Stromae"))
    }

    @Test
    fun `anything else is a song`() {
        assertEquals(Request(Type.SONG, "Bohemian Rhapsody"), parse("Bohemian Rhapsody"))
        assertEquals(Request(Type.SONG, "Hello de Adele"), parse("joue Hello de Adele"))
        assertEquals(Request(Type.SONG, "AC/DC"), parse("AC/DC"))
    }

    @Test
    fun `structured extras win over the text`() {
        assertEquals(
            Request(Type.ALBUM, "Discovery"),
            VoiceQueryParser.parse("Discovery Daft Punk", focus = VoiceQueryParser.FOCUS_ALBUM, album = "Discovery"),
        )
        assertEquals(
            Request(Type.ARTIST, "Daft Punk"),
            VoiceQueryParser.parse("Daft Punk", focus = VoiceQueryParser.FOCUS_ARTIST),
        )
        assertEquals(
            Request(Type.SONG, "One More Time Daft Punk"),
            VoiceQueryParser.parse(
                "One More Time",
                focus = VoiceQueryParser.FOCUS_SONG,
                title = "One More Time",
                artist = "Daft Punk",
            ),
        )
        assertEquals(
            Request(Type.PLAYLIST, "Sport"),
            VoiceQueryParser.parse("Sport", focus = VoiceQueryParser.FOCUS_PLAYLIST, playlist = "Sport"),
        )
    }

    @Test
    fun `playlist names match loosely`() {
        assertEquals(1.0, VoiceSearchMatcher.nameScore("sport", "Sport 💪"), 0.0)
        assertEquals(1.0, VoiceSearchMatcher.nameScore("soiree", "Soirée"), 0.0)
        assertTrue(VoiceSearchMatcher.nameScore("musique chill", "Chill") >= VoiceSearchMatcher.STRONG_MATCH_THRESHOLD)
        assertEquals(
            "Sport 💪",
            VoiceSearchMatcher.bestByName("sport", listOf("Rap FR", "Sport 💪", "Chill")) { it },
        )
        assertNull(VoiceSearchMatcher.bestByName("jazz", listOf("Rap FR", "Sport 💪")) { it })
    }
}
