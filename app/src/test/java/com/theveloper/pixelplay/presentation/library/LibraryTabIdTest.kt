package com.theveloper.pixelplay.presentation.library

import com.theveloper.pixelplay.data.model.SortOption
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertIterableEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LibraryTabIdTest {

    @Test
    fun `decodeLibraryTabOrder returns default order when stored value is null`() {
        val order = decodeLibraryTabOrder(null)
        assertIterableEquals(LibraryTabId.defaultOrder, order)
    }

    @Test
    fun `decodeLibraryTabOrder preserves known order and restores missing tabs`() {
        val storedKeys = listOf(
            LibraryTabId.Liked.stableKey,
            "UNKNOWN",
            LibraryTabId.Playlists.stableKey,
            LibraryTabId.Liked.stableKey // duplicate should be ignored
        )
        val order = decodeLibraryTabOrder(Json.encodeToString(storedKeys))

        // Unknown keys are dropped and duplicates collapsed.
        assertTrue(order.containsAll(LibraryTabId.defaultOrder), "All default tabs should be present exactly once")
        assertEquals(LibraryTabId.defaultOrder.size, order.size)
        // Relative order of the tabs present in storage is preserved (Liked before Playlists),
        // while missing defaults are merged in at their default relative positions.
        assertTrue(
            order.indexOf(LibraryTabId.Liked) < order.indexOf(LibraryTabId.Playlists),
            "Stored relative order should be preserved"
        )
        assertEquals(
            listOf(
                LibraryTabId.Songs,
                LibraryTabId.Albums,
                LibraryTabId.Years,
                LibraryTabId.Artists,
                LibraryTabId.Folders,
                LibraryTabId.Liked,
                LibraryTabId.Playlists
            ),
            order
        )
    }

    @Test
    fun `sort associations remain tied to tab ids after reordering`() {
        val persistedSorts = LibraryTabId.defaultOrder.associateWith { tab ->
            tab.sortOptions.firstOrNull() ?: SortOption.SongTitleAZ
        }

        val shuffledOrder = decodeLibraryTabOrder(
            Json.encodeToString(
                listOf(
                    LibraryTabId.Folders.stableKey,
                    LibraryTabId.Songs.stableKey,
                    LibraryTabId.Playlists.stableKey
                )
            )
        )

        shuffledOrder.forEach { tab ->
            assertEquals(persistedSorts[tab], persistedSorts.getValue(tab))
        }
    }

    @Test
    fun `years tab defaults to directly after albums`() {
        val defaultOrder = LibraryTabId.defaultOrder
        val albumsIdx = defaultOrder.indexOf(LibraryTabId.Albums)
        val yearsIdx = defaultOrder.indexOf(LibraryTabId.Years)
        assertEquals(albumsIdx + 1, yearsIdx)
    }

    @Test
    fun `legacy stored order without years gets years inserted right after albums`() {
        // Stored order persisted by a build that predates the Years tab.
        val legacyKeys = listOf(
            LibraryTabId.Songs.stableKey,
            LibraryTabId.Albums.stableKey,
            LibraryTabId.Artists.stableKey,
            LibraryTabId.Playlists.stableKey,
            LibraryTabId.Folders.stableKey,
            LibraryTabId.Liked.stableKey
        )
        val order = decodeLibraryTabOrder(Json.encodeToString(legacyKeys))

        assertIterableEquals(
            listOf(
                LibraryTabId.Songs,
                LibraryTabId.Albums,
                LibraryTabId.Years,
                LibraryTabId.Artists,
                LibraryTabId.Playlists,
                LibraryTabId.Folders,
                LibraryTabId.Liked
            ),
            order
        )
    }

    @Test
    fun `years tab exposes newest and oldest sort options`() {
        assertEquals(
            listOf(SortOption.YearBucketNewest, SortOption.YearBucketOldest),
            LibraryTabId.Years.sortOptions
        )
    }
}
