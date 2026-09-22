package com.mslynch.awesomesource.organize.grouping

import com.mslynch.awesomesource.organize.model.AlbumGroup
import com.mslynch.awesomesource.organize.model.LibraryPath
import com.mslynch.awesomesource.organize.model.TrackMetadata
import com.mslynch.awesomesource.organize.tags.FilenameParser

/**
 * Ported from `legacy-desktop-tagger/musictagger/grouping/album_grouper.py`.
 * Clusters a flat list of [TrackMetadata] into [AlbumGroup]s before any online
 * query. See that file's module doc comment for the full strategy (folder-first,
 * tag-confirmed, track-number-clustered fallback for untagged files, singleton
 * queries at track level) - every threshold and real-world edge case fix documented
 * there is preserved here.
 */
object AlbumGrouper {

    private val WHITESPACE_RE = Regex("\\s+")

    // An artist cluster within a mixed, untagged folder needs at least this many
    // members to be treated as "probably one release" rather than a loose file that
    // happens to share the folder with unrelated tracks.
    private const val MIN_CLUSTER_SIZE = 2

    // A dominant tagged group within a folder needs at least this many members,
    // sharing resolvable track numbers, before a lone same-folder outlier is
    // trusted enough to reclaim into it.
    private const val MIN_DOMINANT_GROUP_SIZE = 3

    private fun normalize(text: String?): String = WHITESPACE_RE.replace((text ?: "").trim(), " ").lowercase()

    private fun artistGuess(track: TrackMetadata): String {
        track.artist?.let { return normalize(it) }
        val guess = FilenameParser.parseFilename(track.path)
        return normalize(guess.artist)
    }

    private fun hasTrackNumber(track: TrackMetadata): Boolean = FilenameParser.resolveTrackNumber(track) != null

    private fun singleton(track: TrackMetadata, flagged: Boolean = false): AlbumGroup {
        val guess = FilenameParser.parseFilename(track.path)
        return AlbumGroup(
            groupKey = "singleton::${track.path}",
            files = listOf(track),
            bestGuessArtist = track.artist ?: guess.artist,
            bestGuessAlbum = track.album ?: guess.album,
            isSingleton = true,
            flaggedInconsistent = flagged,
        )
    }

    /**
     * Clusters untagged files within one folder by matching artist AND a track
     * number (tag or filename-derived) - see album_grouper.py's `_group_untagged`
     * doc comment for why both signals together are needed (a folder can hold
     * either several artists' loose singles, or one artist's assorted singles
     * collected over years - only shared track numbering tells "one real album"
     * apart from either).
     */
    private fun groupUntagged(folder: LibraryPath?, untagged: List<TrackMetadata>): List<AlbumGroup> {
        val byArtist = LinkedHashMap<String, MutableList<TrackMetadata>>()
        val ungrouped = mutableListOf<TrackMetadata>()

        for (track in untagged) {
            val guess = artistGuess(track)
            if (guess.isNotEmpty() && hasTrackNumber(track)) {
                byArtist.getOrPut(guess) { mutableListOf() }.add(track)
            } else {
                ungrouped.add(track)
            }
        }

        val groups = mutableListOf<AlbumGroup>()
        for ((artistKey, members) in byArtist) {
            if (members.size >= MIN_CLUSTER_SIZE) {
                groups.add(
                    AlbumGroup(
                        groupKey = "$folder::$artistKey",
                        files = members,
                        bestGuessArtist = members.first().artist ?: artistKey,
                    )
                )
            } else {
                groups.addAll(members.map { singleton(it, flagged = true) })
            }
        }
        groups.addAll(ungrouped.map { singleton(it, flagged = true) })
        return groups
    }

    /**
     * A single mistagged file can sit in the same folder as a large, otherwise
     * sequentially-numbered compilation and still get split into its own group
     * purely because it carries a different (wrong) album tag - see
     * album_grouper.py's `_reclaim_sequential_outliers` doc comment for the real
     * observed case (a various-artists compilation ripped as tracks 1-17, with
     * track 16 alone mistagged, sitting exactly at the one gap in an otherwise
     * unbroken 1-15,17 sequence).
     */
    private fun reclaimSequentialOutliers(
        tagGroups: Map<String, List<TrackMetadata>>,
    ): Map<String, List<TrackMetadata>> {
        val candidates = tagGroups.filter { it.value.size >= MIN_DOMINANT_GROUP_SIZE }
        if (candidates.isEmpty()) return tagGroups

        val reclaimed = tagGroups.mapValues { it.value.toMutableList() }.toMutableMap()
        for ((outlierKey, outlierMembers) in tagGroups) {
            if (outlierMembers.size != 1) continue
            val track = outlierMembers[0]
            // The filename's own position - not resolveTrackNumber()'s tag-first
            // result - is checked here: a mistagged file's own track_number TAG is
            // exactly what can't be trusted (real example: tagged track_number=7
            // matching the WRONG album, while the filename "16 Trill Clinton.mp3"
            // still correctly encodes its real position in the original rip batch).
            val number = FilenameParser.parseFilename(track.path).trackNumber ?: continue
            for ((dominantKey, dominantMembers) in candidates) {
                if (dominantKey == outlierKey) continue
                val dominantNumbers = dominantMembers.mapNotNull { FilenameParser.resolveTrackNumber(it) }.toSet()
                if (dominantNumbers.isEmpty()) continue
                val lo = dominantNumbers.min()
                val hi = dominantNumbers.max()
                if (number in lo..hi && number !in dominantNumbers) {
                    reclaimed.getValue(dominantKey).add(track)
                    reclaimed.getValue(outlierKey).remove(track)
                    break
                }
            }
        }
        return reclaimed.filterValues { it.isNotEmpty() }
    }

    fun groupIntoAlbums(tracks: List<TrackMetadata>): List<AlbumGroup> {
        val byFolder = LinkedHashMap<LibraryPath?, MutableList<TrackMetadata>>()
        for (track in tracks) byFolder.getOrPut(track.path.parent()) { mutableListOf() }.add(track)

        val groups = mutableListOf<AlbumGroup>()
        for ((folder, folderTracks) in byFolder) {
            if (folderTracks.size == 1) {
                groups.add(singleton(folderTracks[0]))
                continue
            }

            val tagged = folderTracks.filter { !it.album.isNullOrEmpty() }
            val untagged = folderTracks.filter { it.album.isNullOrEmpty() }

            // Grouped by album name alone, not also by (album_artist or artist) -
            // see album_grouper.py's identical comment: a various-artists
            // compilation very commonly has album_artist set on only SOME tracks,
            // and requiring agreement there fragments one real album purely
            // because of a tagging gap.
            val tagGroups = LinkedHashMap<String, MutableList<TrackMetadata>>()
            for (track in tagged) tagGroups.getOrPut(normalize(track.album)) { mutableListOf() }.add(track)

            val reclaimedGroups = reclaimSequentialOutliers(tagGroups)

            for (members in reclaimedGroups.values) {
                // Prefers a member that actually carries an explicit album_artist
                // tag ("Various Artists") over an arbitrary first file - see
                // album_grouper.py's identical comment.
                val representative = members.firstOrNull { !it.albumArtist.isNullOrEmpty() } ?: members.first()
                groups.add(
                    AlbumGroup(
                        groupKey = "$folder::${normalize(representative.album)}",
                        files = members,
                        bestGuessArtist = representative.albumArtist ?: representative.artist,
                        bestGuessAlbum = representative.album,
                    )
                )
            }

            if (untagged.isNotEmpty()) groups.addAll(groupUntagged(folder, untagged))
        }

        return groups
    }
}
