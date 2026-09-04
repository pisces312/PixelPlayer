package com.theveloper.pixelplay.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.theveloper.pixelplay.data.ai.provider.AiProvider
import com.theveloper.pixelplay.data.backup.module.AiProviderConfigBackupHandler
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiPreferencesBackupTest {

    /**
     * In-memory DataStore double. The project's JVM unit tests can't reliably drive the real
     * DataStore (background IO scope / StateFlow replay), so we implement updateData directly to
     * exercise the REAL AiPreferencesRepository export/import mapping deterministically.
     */
    private fun fakeDataStore(): DataStore<Preferences> {
        val flow = MutableStateFlow<Preferences>(emptyPreferences())
        return object : DataStore<Preferences> {
            override val data: Flow<Preferences> get() = flow
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                val newValue = transform(flow.value)
                flow.value = newValue
                return newValue
            }
        }
    }

    @Test
    fun exportImportRoundTripPreservesProviderConfig() = runTest {
        val repo = AiPreferencesRepository(fakeDataStore())
        repo.setAiProvider("VOLCANO")
        repo.setApiKey(AiProvider.VOLCANO, "ark-secret-key")
        repo.setModel(AiProvider.VOLCANO, "ep-2024-01")
        repo.setBaseUrl(AiProvider.CUSTOM, "https://my.proxy/v1")

        val entries = repo.exportAiPreferencesForBackup()
        assertTrue(entries.isNotEmpty(), "export should produce at least one entry")

        repo.clearAllAiPreferences()
        // aiProvider falls back to its default ("GEMINI") on empty prefs; api key defaults to "".
        assertEquals("GEMINI", repo.aiProvider.first())
        assertEquals("", repo.volcanoApiKey.first())

        repo.importAiPreferencesFromBackup(entries)

        assertEquals("VOLCANO", repo.aiProvider.first())
        assertEquals("ark-secret-key", repo.volcanoApiKey.first())
        assertEquals("ep-2024-01", repo.volcanoModel.first())
        assertEquals("https://my.proxy/v1", repo.customBaseUrl.first())
    }

    @Test
    fun handlerExportProducesPreferenceEntryArray() = runTest {
        val repo = AiPreferencesRepository(fakeDataStore())
        repo.setApiKey(AiProvider.VOLCANO, "ark-secret-key")
        repo.setModel(AiProvider.VOLCANO, "ep-2024-01")

        val handler = AiProviderConfigBackupHandler(repo, Gson())
        val json = handler.export()

        val type = TypeToken.getParameterized(List::class.java, PreferenceBackupEntry::class.java).type
        val entries: List<PreferenceBackupEntry> = Gson().fromJson(json, type)
        assertTrue(entries.any { it.key == "volcano_api_key" && it.stringValue == "ark-secret-key" })
        assertTrue(entries.any { it.key == "volcano_model" && it.stringValue == "ep-2024-01" })
    }

    @Test
    fun handlerRestoreRebuildsConfigFromJson() = runTest {
        val repo = AiPreferencesRepository(fakeDataStore())
        repo.setAiProvider("GEMINI")
        repo.setApiKey(AiProvider.VOLCANO, "ark-secret-key")

        val handler = AiProviderConfigBackupHandler(repo, Gson())
        val json = handler.export()

        // Simulate a fresh install: clear then restore from the exported payload.
        repo.clearAllAiPreferences()
        handler.restore(json)

        assertEquals("GEMINI", repo.aiProvider.first())
        assertEquals("ark-secret-key", repo.volcanoApiKey.first())
    }
}
