package com.theveloper.pixelplay.presentation.components

import androidx.annotation.StringRes
import com.theveloper.pixelplay.R

/**
 * Parses the repository's CHANGELOG.md (Keep a Changelog format) into the UI model
 * used by [ChangelogBottomSheet], so the in-app changelog stays in sync with the
 * single source of truth instead of hardcoded localized entries.
 *
 * Only the section *titles* remain localized (via [sectionTitleRes]); the bullet
 * text is taken verbatim from CHANGELOG.md, which is English-only.
 */
object ChangelogParser {

    private val versionRegex = Regex("""^## \[(.+?)] - (.+)$""")
    private val sectionRegex = Regex("""^### (.+)$""")
    private val bulletRegex = Regex("""^-\s+(?:\*\*(.+?)\*\*:\s*)?(.*)$""")

    fun parse(markdown: String): List<ChangelogVersion> {
        val result = mutableListOf<ChangelogVersion>()

        var currentVersion: String? = null
        var currentDate: String? = null
        val currentSections = mutableListOf<ChangelogSection>()
        var currentSectionTitleRes: Int? = null
        val currentItems = mutableListOf<String>()

        fun closeSection() {
            val titleRes = currentSectionTitleRes ?: return
            if (currentItems.isNotEmpty()) {
                currentSections.add(ChangelogSection(titleRes, currentItems.toList()))
            }
            currentItems.clear()
            currentSectionTitleRes = null
        }

        fun closeVersion() {
            closeSection()
            val version = currentVersion ?: return
            val date = currentDate ?: return
            if (currentSections.isNotEmpty()) {
                result.add(ChangelogVersion(version, date, currentSections.toList()))
            }
            currentVersion = null
            currentDate = null
            currentSections.clear()
        }

        for (line in markdown.lineSequence()) {
            val trimmed = line.trimEnd()
            versionRegex.matchEntire(trimmed)?.let { match ->
                closeVersion()
                currentVersion = match.groupValues[1]
                currentDate = match.groupValues[2].trim()
                continue
            }
            if (trimmed.startsWith("## [")) {
                // "## [Unreleased]" or a header without a date — skip this block.
                closeVersion()
                continue
            }
            sectionRegex.matchEntire(trimmed)?.let { match ->
                closeSection()
                currentSectionTitleRes = sectionTitleRes(match.groupValues[1])
                continue
            }
            bulletRegex.matchEntire(trimmed)?.let { match ->
                if (currentSectionTitleRes != null) {
                    currentItems.add(match.groupValues[2].trim())
                }
            }
        }
        closeVersion()
        return result
    }

    /**
     * Maps a Keep a Changelog section heading to the localized category label.
     * Returns null for headings that should not be shown (e.g. "New Contributors").
     */
    @StringRes
    private fun sectionTitleRes(title: String): Int? = when (title.trim().lowercase()) {
        "added", "what's new", "whats new", "localization" -> R.string.changelog_sec_added
        "changed", "improvements", "performance" -> R.string.changelog_sec_improvements
        "fixed", "fixes" -> R.string.changelog_sec_fixes
        else -> null
    }
}
