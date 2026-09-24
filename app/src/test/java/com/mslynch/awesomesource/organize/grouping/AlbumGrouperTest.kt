package com.mslynch.awesomesource.organize.grouping

import android.net.Uri
import com.mslynch.awesomesource.organize.model.LibraryPath
import com.mslynch.awesomesource.organize.model.TrackMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Ported from `legacy-desktop-tagger/tests/test_album_grouper.py` - same
 * regression cases and reasoning. Runs under Robolectric because [TrackMetadata]
 * has a non-null [Uri] field - the plain Android stub jar's `Uri.EMPTY`/
 * `Uri.parse()` are just null (not stubbed defaults), which threw a real NPE the
 * first time this test suite was actually run; Robolectric's shadow classes give
 * a real, working `Uri` implementation instead. */
@RunWith(RobolectricTestRunner::class)
class AlbumGrouperTest {

    private fun fakeUri(): Uri = Uri.EMPTY

    private fun track(
        path: String,
        artist: String? = null,
        album: String? = null,
        albumArtist: String? = null,
        title: String? = null,
        trackNumber: Int? = null,
    ) = TrackMetadata(
        uri = fakeUri(),
        path = LibraryPath(path),
        artist = artist,
        album = album,
        albumArtist = albumArtist,
        title = title,
        trackNumber = trackNumber,
    )

    @Test
    fun `tagged tracks in one folder form one group`() {
        val tracks = listOf(
            track("Music/Daft Punk/Discovery/01 One More Time.mp3", artist = "Daft Punk", album = "Discovery"),
            track("Music/Daft Punk/Discovery/02 Aerodynamic.mp3", artist = "Daft Punk", album = "Discovery"),
            track("Music/Daft Punk/Discovery/03 Digital Love.mp3", artist = "Daft Punk", album = "Discovery"),
        )
        val groups = AlbumGrouper.groupIntoAlbums(tracks)
        assertEquals(1, groups.size)
        assertEquals(3, groups[0].files.size)
        assertFalse(groups[0].isSingleton)
    }

    @Test
    fun `disagreeing album tags split into subgroups`() {
        val tracks = listOf(
            track("Music/Mixed/01.mp3", artist = "Artist A", album = "Album A"),
            track("Music/Mixed/02.mp3", artist = "Artist A", album = "Album A"),
            track("Music/Mixed/03.mp3", artist = "Artist B", album = "Album B"),
        )
        val groups = AlbumGrouper.groupIntoAlbums(tracks)
        assertEquals(2, groups.size)
        assertEquals(listOf(1, 2), groups.map { it.files.size }.sorted())
        val albumAGroup = groups.first { it.bestGuessAlbum == "Album A" }
        assertEquals(2, albumAGroup.files.size)
    }

    @Test
    fun `compilation with inconsistent album artist tagging still forms one group`() {
        val tracks = listOf(
            track("Music/EDM/01 Track1.mp3", artist = "Freddy Todd & NOTE", albumArtist = "Various Artists", album = "MEANWHILE..."),
            track("Music/EDM/02 Track2.mp3", artist = "ConRank", albumArtist = "Various Artists", album = "MEANWHILE..."),
            track("Music/EDM/04 Track4.mp3", artist = "Liquid Stranger & Space Jesus", album = "MEANWHILE..."),
            track("Music/EDM/06 Track6.mp3", artist = "Shlump", album = "MEANWHILE..."),
        )
        val groups = AlbumGrouper.groupIntoAlbums(tracks)
        assertEquals(1, groups.size)
        assertEquals(4, groups[0].files.size)
        assertEquals("Various Artists", groups[0].bestGuessArtist)
    }

    @Test
    fun `lone mistagged outlier in a real sequential gap reclaimed into the compilation`() {
        val tracks = (listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 17)).map { n ->
            track(
                "Music/EDM/${"%02d".format(n)} Track$n.mp3",
                artist = "Various", albumArtist = "Various Artists", album = "MEANWHILE...",
            )
        } + track(
            "Music/EDM/16 Trill Clinton.mp3", artist = "Mr. Bill & Tha Fruitbat",
            albumArtist = "Mr. Bill", album = "Corrective Scene Surgery", trackNumber = 16,
        )
        val groups = AlbumGrouper.groupIntoAlbums(tracks)
        val meanwhileGroup = groups.first { it.bestGuessAlbum == "MEANWHILE..." }
        assertEquals(17, meanwhileGroup.files.size)
        assertTrue(meanwhileGroup.files.any { it.path.name() == "16 Trill Clinton.mp3" })
        assertFalse(groups.any { it.bestGuessAlbum == "Corrective Scene Surgery" })
    }

    @Test
    fun `outlier group with multiple files is not reclaimed`() {
        val tracks = (listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 17)).map { n ->
            track(
                "Music/EDM/${"%02d".format(n)} Track$n.mp3",
                artist = "Various", albumArtist = "Various Artists", album = "MEANWHILE...",
            )
        } + listOf(
            track("Music/EDM/16 Other A.mp3", artist = "X", album = "Some Other EP", trackNumber = 16),
            track("Music/EDM/18 Other B.mp3", artist = "X", album = "Some Other EP", trackNumber = 18),
        )
        val groups = AlbumGrouper.groupIntoAlbums(tracks)
        val otherGroup = groups.first { it.bestGuessAlbum == "Some Other EP" }
        assertEquals(2, otherGroup.files.size)
    }

    @Test
    fun `single file folder is singleton`() {
        val tracks = listOf(track("Music/Loose/only_track.mp3", artist = "Solo Artist", title = "Solo Song"))
        val groups = AlbumGrouper.groupIntoAlbums(tracks)
        assertEquals(1, groups.size)
        assertTrue(groups[0].isSingleton)
    }

    @Test
    fun `untagged but consistent filenames group together`() {
        val tracks = listOf(
            track("Music/SomeRip/01 Muse - Uprising.mp3"),
            track("Music/SomeRip/02 Muse - Starlight.mp3"),
            track("Music/SomeRip/03 Muse - Supermassive Black Hole.mp3"),
        )
        val groups = AlbumGrouper.groupIntoAlbums(tracks)
        assertEquals(1, groups.size)
        assertEquals("muse", groups[0].bestGuessArtist)
        assertEquals(3, groups[0].files.size)
    }

    @Test
    fun `untagged same artist without track numbers stays ungrouped`() {
        val tracks = listOf(
            track("Music/SomeRip/Muse - Uprising.mp3"),
            track("Music/SomeRip/Muse - Starlight.mp3"),
            track("Music/SomeRip/Muse - Supermassive Black Hole.mp3"),
        )
        val groups = AlbumGrouper.groupIntoAlbums(tracks)
        assertEquals(3, groups.size)
        assertTrue(groups.all { it.isSingleton })
    }

    @Test
    fun `wildly inconsistent untagged folder splits to flagged singletons`() {
        val tracks = listOf(
            track("Music/Mess/Muse - Uprising.mp3"),
            track("Music/Mess/Coldplay - Yellow.mp3"),
            track("Music/Mess/Adele - Hello.mp3"),
        )
        val groups = AlbumGrouper.groupIntoAlbums(tracks)
        assertEquals(3, groups.size)
        assertTrue(groups.all { it.isSingleton && it.flaggedInconsistent })
    }

    @Test
    fun `same artist cluster survives a busy mixed folder`() {
        val tipperAlbumTracks = (1..13).map { n -> track("Music/EDM/$n Track$n.mp3", artist = "Tippermusic") }
        val tipperLooseSingles = (1..5).map { n -> track("Music/EDM/Tipper - Single$n.mp3", artist = "Tippermusic") }
        val otherLooseSingles = listOf(
            track("Music/EDM/Some Song.mp3", artist = "Liquid Stranger"),
            track("Music/EDM/Another Song.mp3", artist = "Space Laces"),
            track("Music/EDM/Yet Another.mp3", artist = "Dubloadz"),
            track("Music/EDM/no_tags_at_all.wav"),
        )
        val tracks = tipperAlbumTracks + tipperLooseSingles + otherLooseSingles
        val groups = AlbumGrouper.groupIntoAlbums(tracks)

        val tipperGroup = groups.first { it.bestGuessArtist == "Tippermusic" }
        assertEquals(13, tipperGroup.files.size)
        assertFalse(tipperGroup.isSingleton)
        assertTrue(tipperGroup.files.all { f -> (1..13).any { n -> f.path.name().startsWith("$n ") } })

        val otherGroups = groups.filter { it !== tipperGroup }
        assertTrue(otherGroups.all { it.isSingleton })
        assertEquals(9, otherGroups.size)
    }
}
