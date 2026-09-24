package com.mslynch.awesomesource.organize.tags

import com.mslynch.awesomesource.organize.model.LibraryPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from `legacy-desktop-tagger/tests/test_filename_parser.py` - same cases,
 * adapted from `pathlib.Path` to [LibraryPath] (see that type's doc comment for why).
 * `resolve_track_number`'s TrackMetadata-based test isn't ported here since it needs
 * a real `android.net.Uri` for `TrackMetadata.uri`, which plain JUnit stubs out to
 * "not mocked" unless `testOptions.unitTests.isReturnDefaultValues` is set (see
 * `app/build.gradle.kts`) - `stripLeadingTrackNumber`, which `resolveTrackNumber`
 * falls back to, is already covered directly below. */
class FilenameParserTest {

    @Test
    fun `structured artist album title`() {
        val guess = FilenameParser.parseFilename(LibraryPath("Music/Pink Floyd/The Wall/03 - Another Brick in the Wall.mp3"))
        assertEquals(FilenameParser.Confidence.STRUCTURED, guess.confidence)
        assertEquals("Pink Floyd", guess.artist)
        assertEquals("The Wall", guess.album)
        assertEquals("Another Brick in the Wall", guess.title)
        assertEquals(3, guess.trackNumber)
    }

    @Test
    fun `structured artist dash album folder`() {
        val guess = FilenameParser.parseFilename(LibraryPath("Music/Daft Punk - Discovery/02 One More Time.mp3"))
        assertEquals(FilenameParser.Confidence.STRUCTURED, guess.confidence)
        assertEquals("Daft Punk", guess.artist)
        assertEquals("Discovery", guess.album)
        assertEquals("One More Time", guess.title)
    }

    @Test
    fun `structured artist title loose file`() {
        val guess = FilenameParser.parseFilename(LibraryPath("Downloads/Coldplay - Yellow.mp3"))
        assertEquals(FilenameParser.Confidence.STRUCTURED, guess.confidence)
        assertEquals("Coldplay", guess.artist)
        assertEquals("Yellow", guess.title)
    }

    @Test
    fun `strips noise tokens`() {
        val guess = FilenameParser.parseFilename(LibraryPath("Downloads/Coldplay - Yellow [Explicit] (Remastered 2011).mp3"))
        assertEquals("Coldplay", guess.artist)
        assertEquals("Yellow", guess.title)
    }

    @Test
    fun `gibberish filename falls back to loose search`() {
        val guess = FilenameParser.parseFilename(LibraryPath("Downloads/xY7_2gK9-titlefragment_final(2).mp3"))
        assertEquals(FilenameParser.Confidence.LOOSE, guess.confidence)
        assertTrue(guess.searchText != null)
        assertTrue(guess.searchText!!.contains("titlefragment"))
        assertFalse(guess.searchText!!.contains("xY7"))
        assertFalse(guess.searchText!!.lowercase().contains("final"))
    }

    @Test
    fun `pure random id filename yields no search text`() {
        val guess = FilenameParser.parseFilename(LibraryPath("Downloads/8f3a91cd_004.mp3"))
        assertEquals(FilenameParser.Confidence.LOOSE, guess.confidence)
        assertTrue(guess.searchText.isNullOrEmpty())
    }

    @Test
    fun `track number survives loose fallback`() {
        val guess = FilenameParser.parseFilename(LibraryPath("Downloads/10 Track10.mp3"))
        assertEquals(FilenameParser.Confidence.LOOSE, guess.confidence)
        assertEquals(10, guess.trackNumber)
    }

    @Test
    fun `split artist title text recovers embedded title`() {
        val (artist, title) = FilenameParser.splitArtistTitleText("Tipper - Baleen")
        assertEquals("Tipper", artist)
        assertEquals("Baleen", title)
    }

    @Test
    fun `split artist title text strips visualizer suffix first`() {
        val (artist, title) = FilenameParser.splitArtistTitleText("Tipper - Air Biscuits | Insolito (4K music visualizer)")
        assertEquals("Tipper", artist)
        assertEquals("Air Biscuits", title)
    }

    @Test
    fun `split artist title text rejects implausible split`() {
        val (artist, title) = FilenameParser.splitArtistTitleText("just one plain title with no dash")
        assertNull(artist)
        assertNull(title)
    }

    @Test
    fun `strip leading track number recovers number and clean title`() {
        val (number, title) = FilenameParser.stripLeadingTrackNumber("1 Goldilocks Zone")
        assertEquals(1, number)
        assertEquals("Goldilocks Zone", title)
    }

    @Test
    fun `strip leading track number leaves plain titles untouched`() {
        val (number, title) = FilenameParser.stripLeadingTrackNumber("Goldilocks Zone")
        assertNull(number)
        assertEquals("Goldilocks Zone", title)
    }

    @Test
    fun `strip leading track number does not consume a purely numeric title`() {
        val (number, title) = FilenameParser.stripLeadingTrackNumber("13")
        assertNull(number)
        assertEquals("13", title)
    }

    @Test
    fun `disc subfolder is not mistaken for an artist folder`() {
        val guess = FilenameParser.parseFilename(
            LibraryPath("Music/Jeff Wayne's War of the Worlds/Act 1/Jeff Wayne - Horsell Common and the Heatray.mp3")
        )
        assertEquals("Jeff Wayne", guess.artist)
        assertEquals("Jeff Wayne's War of the Worlds", guess.album)
        assertEquals("Horsell Common and the Heatray", guess.title)
    }

    @Test
    fun `disc subfolder pattern matches common variants`() {
        for (subfolder in listOf("Disc 2", "CD1", "Part 3", "Volume 1", "Vol. 4")) {
            val guess = FilenameParser.parseFilename(LibraryPath("My Album/$subfolder/Some Artist - Some Title.mp3"))
            assertEquals("failed for subfolder $subfolder", "My Album", guess.album)
            assertEquals("failed for subfolder $subfolder", "Some Artist", guess.artist)
        }
    }

    @Test
    fun `clean noise text strips newgrounds id suffix`() {
        assertEquals("Dr. Finkelfracken's Cure", FilenameParser.cleanNoiseText("Dr. Finkelfracken's Cure (ID: 383158)"))
    }

    @Test
    fun `clean noise text strips curly brace tags`() {
        assertEquals("Rain Full", FilenameParser.cleanNoiseText("{dj-N} Rain Full"))
    }

    @Test
    fun `clean noise text strips free and original mix tags`() {
        assertEquals("Song Title", FilenameParser.cleanNoiseText("Song Title (free)"))
        assertEquals("Song Title", FilenameParser.cleanNoiseText("Song Title (Original Mix)"))
    }

    @Test
    fun `clean noise text strips wrapping dashes`() {
        assertEquals("Clownparty remix", FilenameParser.cleanNoiseText("-Clownparty remix- (ID: 286138)"))
    }
}
