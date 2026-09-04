package com.theveloper.pixelplay.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import com.theveloper.pixelplay.data.ai.provider.AiProvider
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiPreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    companion object {
        val DEFAULT_SYSTEM_PROMPT = """
            You are 'Vibe-Engine', a professional music curator.
            Analyze the user's request and listening profile to provide perfect music recommendations.
            Always prioritize flow, emotional resonance, and discovery.
        """.trimIndent()

        val DEFAULT_DEEPSEEK_SYSTEM_PROMPT = DEFAULT_SYSTEM_PROMPT
        val DEFAULT_GROQ_SYSTEM_PROMPT = DEFAULT_SYSTEM_PROMPT
        val DEFAULT_MISTRAL_SYSTEM_PROMPT = DEFAULT_SYSTEM_PROMPT
        val DEFAULT_NVIDIA_SYSTEM_PROMPT = DEFAULT_SYSTEM_PROMPT
        val DEFAULT_KIMI_SYSTEM_PROMPT = DEFAULT_SYSTEM_PROMPT
        val DEFAULT_GLM_SYSTEM_PROMPT = DEFAULT_SYSTEM_PROMPT
        val DEFAULT_OPENAI_SYSTEM_PROMPT = DEFAULT_SYSTEM_PROMPT
        val DEFAULT_OPENROUTER_SYSTEM_PROMPT = DEFAULT_SYSTEM_PROMPT
        val DEFAULT_VOLCANO_SYSTEM_PROMPT = DEFAULT_SYSTEM_PROMPT

        /**
         * 返回 AI 模块在共享 DataStore 中占用的全部键名。
         * 用于备份导出/清除时精确只处理自己的键，避免误碰用户其他设置。
         */
        fun allAiPreferenceKeyNames(): Set<String> = Keys.STATIC_KEY_NAMES +
            AiProvider.entries.flatMap { provider ->
                listOfNotNull(
                    Keys.getApiKey(provider).name,
                    Keys.getModel(provider).name,
                    Keys.getSystemPrompt(provider).name,
                    Keys.getBaseUrl(provider).name.takeIf { provider.hasConfigurableUrl }
                )
            }
    }

    private object Keys {
        val AI_PROVIDER = stringPreferencesKey("ai_provider")
        val SAFE_TOKEN_LIMIT = booleanPreferencesKey("safe_token_limit")
        val AI_TEMPERATURE = floatPreferencesKey("ai_temperature")
        val AI_TOP_P = floatPreferencesKey("ai_top_p")
        val AI_TOP_K = intPreferencesKey("ai_top_k")
        val AI_MAX_TOKENS = intPreferencesKey("ai_max_tokens")
        val AI_PRESENCE_PENALTY = floatPreferencesKey("ai_presence_penalty")
        val AI_FREQUENCY_PENALTY = floatPreferencesKey("ai_frequency_penalty")
        val AI_SAMPLE_SIZE = intPreferencesKey("ai_sample_size")
        val AI_DIGEST_MODE = stringPreferencesKey("ai_digest_mode")
        val AI_INCLUDE_EXTENDED_FIELDS = booleanPreferencesKey("ai_include_extended_fields")

        fun getApiKey(provider: AiProvider) = stringPreferencesKey("${provider.name.lowercase()}_api_key")
        fun getModel(provider: AiProvider) = stringPreferencesKey("${provider.name.lowercase()}_model")
        fun getSystemPrompt(provider: AiProvider) = stringPreferencesKey("${provider.name.lowercase()}_system_prompt")
        fun getBaseUrl(provider: AiProvider) = stringPreferencesKey("${provider.name.lowercase()}_base_url")

        /** 所有固定 AI 配置键名（不含各 provider 的动态键）。 */
        val STATIC_KEY_NAMES = setOf(
            AI_PROVIDER.name,
            SAFE_TOKEN_LIMIT.name,
            AI_TEMPERATURE.name,
            AI_TOP_P.name,
            AI_TOP_K.name,
            AI_MAX_TOKENS.name,
            AI_PRESENCE_PENALTY.name,
            AI_FREQUENCY_PENALTY.name,
            AI_SAMPLE_SIZE.name,
            AI_DIGEST_MODE.name,
            AI_INCLUDE_EXTENDED_FIELDS.name
        )
    }

    // Generic accessors for AiHandler
    fun getApiKey(provider: AiProvider): Flow<String> =
        dataStore.data.map { preferences -> preferences[Keys.getApiKey(provider)]?.trim() ?: "" }

    fun getModel(provider: AiProvider): Flow<String> =
        dataStore.data.map { preferences -> preferences[Keys.getModel(provider)] ?: "" }

    fun getSystemPrompt(provider: AiProvider): Flow<String> =
        dataStore.data.map { preferences ->
            preferences[Keys.getSystemPrompt(provider)] ?: DEFAULT_SYSTEM_PROMPT
        }

    fun getBaseUrl(provider: AiProvider): Flow<String> =
        dataStore.data.map { preferences -> preferences[Keys.getBaseUrl(provider)] ?: "" }

    suspend fun setApiKey(provider: AiProvider, apiKey: String) {
        dataStore.edit { preferences -> preferences[Keys.getApiKey(provider)] = apiKey.trim() }
    }

    suspend fun setModel(provider: AiProvider, model: String) {
        dataStore.edit { preferences -> preferences[Keys.getModel(provider)] = model }
    }

    suspend fun setSystemPrompt(provider: AiProvider, prompt: String) {
        dataStore.edit { preferences -> preferences[Keys.getSystemPrompt(provider)] = prompt }
    }

    suspend fun resetSystemPrompt(provider: AiProvider) {
        dataStore.edit { preferences ->
            preferences[Keys.getSystemPrompt(provider)] = DEFAULT_SYSTEM_PROMPT
        }
    }

    suspend fun setBaseUrl(provider: AiProvider, url: String) {
        dataStore.edit { preferences -> preferences[Keys.getBaseUrl(provider)] = url.trim() }
    }

    // Convenience properties for legacy compatibility (e.g. PlayerViewModel)
    val geminiApiKey: Flow<String> = getApiKey(AiProvider.GEMINI)
    val geminiModel: Flow<String> = getModel(AiProvider.GEMINI)
    val geminiSystemPrompt: Flow<String> = getSystemPrompt(AiProvider.GEMINI)

    val deepseekApiKey: Flow<String> = getApiKey(AiProvider.DEEPSEEK)
    val deepseekModel: Flow<String> = getModel(AiProvider.DEEPSEEK)
    val deepseekSystemPrompt: Flow<String> = getSystemPrompt(AiProvider.DEEPSEEK)

    val groqApiKey: Flow<String> = getApiKey(AiProvider.GROQ)
    val groqModel: Flow<String> = getModel(AiProvider.GROQ)
    val groqSystemPrompt: Flow<String> = getSystemPrompt(AiProvider.GROQ)

    val mistralApiKey: Flow<String> = getApiKey(AiProvider.MISTRAL)
    val mistralModel: Flow<String> = getModel(AiProvider.MISTRAL)
    val mistralSystemPrompt: Flow<String> = getSystemPrompt(AiProvider.MISTRAL)

    val nvidiaApiKey: Flow<String> = getApiKey(AiProvider.NVIDIA)
    val nvidiaModel: Flow<String> = getModel(AiProvider.NVIDIA)
    val nvidiaSystemPrompt: Flow<String> = getSystemPrompt(AiProvider.NVIDIA)

    val kimiApiKey: Flow<String> = getApiKey(AiProvider.KIMI)
    val kimiModel: Flow<String> = getModel(AiProvider.KIMI)
    val kimiSystemPrompt: Flow<String> = getSystemPrompt(AiProvider.KIMI)

    val glmApiKey: Flow<String> = getApiKey(AiProvider.GLM)
    val glmModel: Flow<String> = getModel(AiProvider.GLM)
    val glmSystemPrompt: Flow<String> = getSystemPrompt(AiProvider.GLM)

    val openaiApiKey: Flow<String> = getApiKey(AiProvider.OPENAI)
    val openaiModel: Flow<String> = getModel(AiProvider.OPENAI)
    val openaiSystemPrompt: Flow<String> = getSystemPrompt(AiProvider.OPENAI)

    val openrouterApiKey: Flow<String> = getApiKey(AiProvider.OPENROUTER)
    val openrouterModel: Flow<String> = getModel(AiProvider.OPENROUTER)
    val openrouterSystemPrompt: Flow<String> = getSystemPrompt(AiProvider.OPENROUTER)

    val ollamaApiKey: Flow<String> = getApiKey(AiProvider.OLLAMA)
    val ollamaModel: Flow<String> = getModel(AiProvider.OLLAMA)
    val ollamaSystemPrompt: Flow<String> = getSystemPrompt(AiProvider.OLLAMA)

    val volcanoApiKey: Flow<String> = getApiKey(AiProvider.VOLCANO)
    val volcanoModel: Flow<String> = getModel(AiProvider.VOLCANO)
    val volcanoSystemPrompt: Flow<String> = getSystemPrompt(AiProvider.VOLCANO)

    val customApiKey: Flow<String> = getApiKey(AiProvider.CUSTOM)
    val customModel: Flow<String> = getModel(AiProvider.CUSTOM)
    val customSystemPrompt: Flow<String> = getSystemPrompt(AiProvider.CUSTOM)
    val customBaseUrl: Flow<String> = getBaseUrl(AiProvider.CUSTOM)

    val aiProvider: Flow<String> =
        dataStore.data.map { preferences -> preferences[Keys.AI_PROVIDER] ?: "GEMINI" }

    val isSafeTokenLimitEnabled: Flow<Boolean> =
        dataStore.data.map { preferences -> preferences[Keys.SAFE_TOKEN_LIMIT] ?: true }

    val aiTemperature: Flow<Float> =
        dataStore.data.map { preferences -> preferences[Keys.AI_TEMPERATURE] ?: 0.7f }

    val aiTopP: Flow<Float> =
        dataStore.data.map { preferences -> preferences[Keys.AI_TOP_P] ?: 0.95f }

    val aiTopK: Flow<Int> =
        dataStore.data.map { preferences -> preferences[Keys.AI_TOP_K] ?: 64 }

    val aiMaxTokens: Flow<Int> =
        dataStore.data.map { preferences -> preferences[Keys.AI_MAX_TOKENS] ?: 4096 }

    val aiPresencePenalty: Flow<Float> =
        dataStore.data.map { preferences -> preferences[Keys.AI_PRESENCE_PENALTY] ?: 0.0f }

    val aiFrequencyPenalty: Flow<Float> =
        dataStore.data.map { preferences -> preferences[Keys.AI_FREQUENCY_PENALTY] ?: 0.0f }

    val aiSampleSize: Flow<Int> =
        dataStore.data.map { preferences -> preferences[Keys.AI_SAMPLE_SIZE] ?: 40 }

    val aiDigestMode: Flow<String> =
        dataStore.data.map { preferences -> preferences[Keys.AI_DIGEST_MODE] ?: "safe" }

    val aiIncludeExtendedFields: Flow<Boolean> =
        dataStore.data.map { preferences -> preferences[Keys.AI_INCLUDE_EXTENDED_FIELDS] ?: false }

    suspend fun setAiProvider(provider: String) {
        dataStore.edit { preferences -> preferences[Keys.AI_PROVIDER] = provider }
    }

    suspend fun setSafeTokenLimitEnabled(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[Keys.SAFE_TOKEN_LIMIT] = enabled }
    }

    suspend fun setAiTemperature(value: Float) {
        dataStore.edit { preferences -> preferences[Keys.AI_TEMPERATURE] = value }
    }

    suspend fun setAiTopP(value: Float) {
        dataStore.edit { preferences -> preferences[Keys.AI_TOP_P] = value }
    }

    suspend fun setAiTopK(value: Int) {
        dataStore.edit { preferences -> preferences[Keys.AI_TOP_K] = value }
    }

    suspend fun setAiMaxTokens(value: Int) {
        dataStore.edit { preferences -> preferences[Keys.AI_MAX_TOKENS] = value }
    }

    suspend fun setAiPresencePenalty(value: Float) {
        dataStore.edit { preferences -> preferences[Keys.AI_PRESENCE_PENALTY] = value }
    }

    suspend fun setAiFrequencyPenalty(value: Float) {
        dataStore.edit { preferences -> preferences[Keys.AI_FREQUENCY_PENALTY] = value }
    }

    suspend fun setAiSampleSize(value: Int) {
        dataStore.edit { preferences -> preferences[Keys.AI_SAMPLE_SIZE] = value }
    }

    suspend fun setAiDigestMode(mode: String) {
        dataStore.edit { preferences -> preferences[Keys.AI_DIGEST_MODE] = mode }
    }

    suspend fun setAiIncludeExtendedFields(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[Keys.AI_INCLUDE_EXTENDED_FIELDS] = enabled }
    }

    suspend fun exportAiPreferencesForBackup(): List<PreferenceBackupEntry> {
        val aiKeys = allAiPreferenceKeyNames()
        val prefs = dataStore.data.first()
        return prefs.asMap().mapNotNull { (key, value) ->
            if (key.name !in aiKeys) return@mapNotNull null
            when (value) {
                is String -> PreferenceBackupEntry(key.name, "string", stringValue = value)
                is Int -> PreferenceBackupEntry(key.name, "int", intValue = value)
                is Long -> PreferenceBackupEntry(key.name, "long", longValue = value)
                is Boolean -> PreferenceBackupEntry(key.name, "boolean", booleanValue = value)
                is Float -> PreferenceBackupEntry(key.name, "float", floatValue = value)
                is Double -> PreferenceBackupEntry(key.name, "double", doubleValue = value)
                is Set<*> -> PreferenceBackupEntry(
                    key.name,
                    "string_set",
                    stringSetValue = value.filterIsInstance<String>().toSet()
                )
                else -> null
            }
        }
    }

    suspend fun importAiPreferencesFromBackup(
        entries: List<PreferenceBackupEntry>,
        clearExisting: Boolean = true
    ) {
        val aiKeys = allAiPreferenceKeyNames()
        dataStore.edit { prefs ->
            if (clearExisting) {
                prefs.asMap().keys
                    .filter { it.name in aiKeys }
                    .forEach { key ->
                        @Suppress("UNCHECKED_CAST")
                        prefs.remove(key as Preferences.Key<Any>)
                    }
            }
            for (entry in entries) {
                if (entry.key !in aiKeys) continue
                when (entry.type) {
                    "string" -> prefs[stringPreferencesKey(entry.key)] = entry.stringValue ?: ""
                    "int" -> prefs[intPreferencesKey(entry.key)] = entry.intValue ?: 0
                    "long" -> prefs[longPreferencesKey(entry.key)] = entry.longValue ?: 0L
                    "boolean" -> prefs[booleanPreferencesKey(entry.key)] = entry.booleanValue ?: false
                    "float" -> prefs[floatPreferencesKey(entry.key)] = entry.floatValue ?: 0f
                    "double" -> prefs[doublePreferencesKey(entry.key)] = entry.doubleValue ?: 0.0
                    "string_set", "stringSet" -> prefs[stringSetPreferencesKey(entry.key)] = entry.stringSetValue ?: emptySet()
                }
            }
        }
    }

    suspend fun clearAllAiPreferences() {
        val aiKeys = allAiPreferenceKeyNames()
        dataStore.edit { prefs ->
            prefs.asMap().keys
                .filter { it.name in aiKeys }
                .forEach { key ->
                    @Suppress("UNCHECKED_CAST")
                    prefs.remove(key as Preferences.Key<Any>)
                }
        }
    }
}
