package com.theveloper.pixelplay.presentation.viewmodel

import org.junit.jupiter.api.Assertions.assertIterableEquals
import org.junit.jupiter.api.Test

class LibraryTabOrderMergeTest {

    @Test
    fun `null or empty stored order falls back to default`() {
        assertIterableEquals(DEFAULT_LIBRARY_TAB_ORDER, mergeWithDefaultTabOrder(null))
        assertIterableEquals(DEFAULT_LIBRARY_TAB_ORDER, mergeWithDefaultTabOrder(emptyList()))
    }

    @Test
    fun `default order places years directly after albums`() {
        assertIterableEquals(
            listOf("SONGS", "ALBUMS", "YEARS", "ARTIST", "PLAYLISTS", "FOLDERS", "LIKED"),
            DEFAULT_LIBRARY_TAB_ORDER
        )
    }

    @Test
    fun `legacy order without years gets years inserted after albums rather than appended`() {
        val legacy = listOf("SONGS", "ALBUMS", "ARTIST", "PLAYLISTS", "FOLDERS", "LIKED")
        assertIterableEquals(
            listOf("SONGS", "ALBUMS", "YEARS", "ARTIST", "PLAYLISTS", "FOLDERS", "LIKED"),
            mergeWithDefaultTabOrder(legacy)
        )
    }

    @Test
    fun `custom user order is preserved and years stays where user put it`() {
        val custom = listOf("LIKED", "SONGS", "ALBUMS", "YEARS", "ARTIST", "PLAYLISTS", "FOLDERS")
        assertIterableEquals(custom, mergeWithDefaultTabOrder(custom))
    }

    @Test
    fun `unknown legacy keys are kept and no duplicates are introduced`() {
        val withLegacyKey = listOf("SONGS", "ALBUMS", "ARTIST", "PLAYLISTS", "FOLDERS", "LIKED", "GENRES")
        val merged = mergeWithDefaultTabOrder(withLegacyKey)
        assertIterableEquals(
            listOf("SONGS", "ALBUMS", "YEARS", "ARTIST", "PLAYLISTS", "FOLDERS", "LIKED", "GENRES"),
            merged
        )
        assertIterableEquals(merged.distinct(), merged)
    }
}
