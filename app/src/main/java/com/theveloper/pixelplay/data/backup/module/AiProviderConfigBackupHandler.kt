package com.theveloper.pixelplay.data.backup.module

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.theveloper.pixelplay.data.backup.model.BackupSection
import com.theveloper.pixelplay.data.preferences.AiPreferencesRepository
import com.theveloper.pixelplay.data.preferences.PreferenceBackupEntry
import com.theveloper.pixelplay.di.BackupGson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiProviderConfigBackupHandler @Inject constructor(
    private val aiPreferencesRepository: AiPreferencesRepository,
    @BackupGson private val gson: Gson
) : BackupModuleHandler {
    override val section: BackupSection = BackupSection.AI_PROVIDER_CONFIG

    override suspend fun export(): String = withContext(Dispatchers.IO) {
        gson.toJson(aiPreferencesRepository.exportAiPreferencesForBackup())
    }

    override suspend fun countEntries(): Int = withContext(Dispatchers.IO) {
        aiPreferencesRepository.exportAiPreferencesForBackup().size
    }

    override suspend fun snapshot(): String = export()

    override suspend fun restore(payload: String) = withContext(Dispatchers.IO) {
        val type = TypeToken.getParameterized(List::class.java, PreferenceBackupEntry::class.java).type
        val entries: List<PreferenceBackupEntry> = gson.fromJson(payload, type) ?: emptyList()
        // AI preferences live in the shared settings DataStore; clear only AI keys and
        // import within a single edit transaction to avoid leaving a partially empty state.
        aiPreferencesRepository.importAiPreferencesFromBackup(entries, clearExisting = true)
    }

    override suspend fun rollback(snapshot: String) = restore(snapshot)
}
