package com.theveloper.pixelplay.data.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SortOptionTest {

    @Test
    fun `method option keeps a single representative per sort method`() {
        assertEquals(SortOption.SongTitleAZ, SortOption.SongTitleZA.methodOption())
        assertEquals(SortOption.SongArtist, SortOption.SongArtistDesc.methodOption())
        assertEquals(SortOption.SongDateAdded, SortOption.SongDateAddedAsc.methodOption())
        assertEquals(SortOption.SongDefaultOrder, SortOption.SongDefaultOrder.methodOption())
    }

    @Test
    fun `resolve for direction keeps method while switching order`() {
        assertEquals(
            SortOption.SongArtistDesc,
            SortOption.SongArtist.resolveForDirection(SortDirection.Descending)
        )
        assertEquals(
            SortOption.SongArtist,
            SortOption.SongArtistDesc.resolveForDirection(SortDirection.Ascending)
        )
        assertEquals(
            SortOption.SongDateAddedAsc,
            SortOption.SongDateAdded.resolveForDirection(SortDirection.Ascending)
        )
        assertEquals(
            SortOption.PlaylistDateCreated,
            SortOption.PlaylistDateCreatedAsc.resolveForDirection(SortDirection.Descending)
        )
    }

    @Test
    fun `flip direction swaps paired sort options`() {
        assertEquals(SortOption.SongTitleZA, SortOption.SongTitleAZ.flipDirection())
        assertEquals(SortOption.SongTitleAZ, SortOption.SongTitleZA.flipDirection())
        assertEquals(SortOption.LikedSongDateLikedAsc, SortOption.LikedSongDateLiked.flipDirection())
        assertEquals(SortOption.FolderSongCountAsc, SortOption.FolderSongCountDesc.flipDirection())
        assertEquals(SortOption.SongDefaultOrder, SortOption.SongDefaultOrder.flipDirection())
    }

    @Test
    fun `from storage key still resolves legacy display names`() {
        val resolved = SortOption.fromStorageKey(
            rawValue = SortOption.SongArtist.displayName,
            allowed = SortOption.SONGS,
            fallback = SortOption.SongTitleAZ
        )

        assertEquals(SortOption.SongArtist, resolved)
    }

    // --- Years smart playlist ---

    @Test
    fun `year bucket default sort is newest first and pair flips to oldest`() {
        assertEquals(SortDirection.Descending, SortOption.YearBucketNewest.direction)
        assertEquals(SortOption.YearBucketOldest, SortOption.YearBucketNewest.flipDirection())
        assertEquals(SortOption.YearBucketNewest, SortOption.YearBucketOldest.flipDirection())
        assertEquals(
            SortOption.YearBucketNewest.methodKey,
            SortOption.YearBucketOldest.methodKey
        )
    }

    @Test
    fun `year detail default sort is most played descending`() {
        assertEquals(SortOption.YearSongPlayCount, SortOption.YEAR_SONGS.first())
        assertEquals(SortDirection.Descending, SortOption.YearSongPlayCount.direction)
    }

    @Test
    fun `every year detail sort method forms an asc desc flip pair`() {
        val pairs = listOf(
            SortOption.YearSongPlayCount to SortOption.YearSongPlayCountAsc,
            SortOption.YearSongRelease to SortOption.YearSongReleaseDesc,
            SortOption.YearSongTitleAZ to SortOption.YearSongTitleZA,
            SortOption.YearSongArtist to SortOption.YearSongArtistDesc,
            SortOption.YearSongAlbum to SortOption.YearSongAlbumDesc,
            SortOption.YearSongDateAdded to SortOption.YearSongDateAddedAsc,
            SortOption.YearSongLastPlayed to SortOption.YearSongLastPlayedAsc,
            SortOption.YearSongRatingHigh to SortOption.YearSongRatingLow,
            SortOption.YearSongDuration to SortOption.YearSongDurationAsc
        )
        assertEquals(9, pairs.size)
        pairs.forEach { (ascOrDefault, counterpart) ->
            assertEquals(counterpart, ascOrDefault.flipDirection())
            assertEquals(ascOrDefault, counterpart.flipDirection())
            assertEquals(ascOrDefault.methodKey, counterpart.methodKey)
        }
    }

    @Test
    fun `year detail sort sheet exposes exactly nine distinct methods`() {
        val distinctMethods = SortOption.YEAR_SONGS.map { it.methodKey }.distinct()
        assertEquals(9, distinctMethods.size)
        assertEquals(18, SortOption.YEAR_SONGS.size)
    }

    @Test
    fun `unknown year detail storage key falls back to default`() {
        val resolved = SortOption.fromStorageKey(
            rawValue = "year_song_does_not_exist",
            allowed = SortOption.YEAR_SONGS,
            fallback = SortOption.YearSongPlayCount
        )
        assertEquals(SortOption.YearSongPlayCount, resolved)
    }

    @Test
    fun `year bucket model flags unknown year`() {
        assertEquals(0, YearBucket.UNKNOWN_YEAR)
        assertEquals(true, YearBucket(YearBucket.UNKNOWN_YEAR, 3).isUnknown)
        assertEquals(false, YearBucket(2012, 10).isUnknown)
    }
}
