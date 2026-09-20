package com.android5.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class SettingsManager(context: Context) {

    init {
        if (_prefs == null) {
            _prefs = context.applicationContext.getSharedPreferences("ai_chat_prefs", Context.MODE_PRIVATE)
            _gson = Gson()
            _showThinkingState = mutableStateOf(_prefs!!.getBoolean("show_thinking", true))
            _webSearchState = mutableStateOf(_prefs!!.getBoolean("web_search_enabled", false))
            _themeModeState = mutableStateOf(_prefs!!.getString("theme_mode", "dynamic") ?: "dynamic")
        }
    }

    fun getApiKey(provider: String): String? {
        val raw = _prefs!!.getString("api_key_${provider.lowercase()}", null)
        if (!raw.isNullOrBlank()) {
            val decrypted = KeyStoreHelper.decrypt(raw)
            // Auto-migrate legacy unencrypted keys
            if (!raw.startsWith("enc:v1:") && !decrypted.isNullOrBlank()) {
                setApiKey(provider, decrypted)
            }
            return decrypted
        }
        // Fallback to legacy key if provider matches selectedProvider
        val legacy = _prefs!!.getString("api_key", null)
        if (provider.equals(selectedProvider, ignoreCase = true) && !legacy.isNullOrBlank()) {
            val decrypted = KeyStoreHelper.decrypt(legacy)
            if (!legacy.startsWith("enc:v1:") && !decrypted.isNullOrBlank()) {
                setApiKey(provider, decrypted)
            }
            return decrypted
        }
        return null
    }

    fun setApiKey(provider: String, key: String?) {
        val edit = _prefs!!.edit()
        val keyName = "api_key_${provider.lowercase()}"
        if (key.isNullOrBlank()) {
            edit.remove(keyName)
        } else {
            val encrypted = KeyStoreHelper.encrypt(key.trim())
            edit.putString(keyName, encrypted)
        }
        if (provider.equals(selectedProvider, ignoreCase = true)) {
            if (key.isNullOrBlank()) {
                edit.remove("api_key")
            } else {
                val encrypted = KeyStoreHelper.encrypt(key.trim())
                edit.putString("api_key", encrypted)
            }
        }
        edit.apply()
    }

    fun hasApiKey(provider: String): Boolean = !getApiKey(provider).isNullOrBlank()

    fun getAllConfiguredProviders(): List<String> = Providers.ALL.map { it.name }.filter { hasApiKey(it) }

    var apiKey: String?
        get() = getApiKey(selectedProvider)
        set(value) = setApiKey(selectedProvider, value)

    var selectedModel: String
        get() = _prefs!!.getString("selected_model", "google/gemini-2.0-flash-lite-preview-02-05:free") ?: "google/gemini-2.0-flash-lite-preview-02-05:free"
        set(value) = _prefs!!.edit().putString("selected_model", value).apply()

    var selectedProvider: String
        get() = _prefs!!.getString("selected_provider", "OpenRouter") ?: "OpenRouter"
        set(value) {
            _prefs!!.edit().putString("selected_provider", value).apply()
            val currentKey = getApiKey(value)
            if (currentKey != null) {
                _prefs!!.edit().putString("api_key", currentKey).apply()
            }
        }

    var customInstructions: String
        get() = _prefs!!.getString("custom_instructions", "") ?: ""
        set(value) = _prefs!!.edit().putString("custom_instructions", value).apply()

    var aiMemory: String
        get() = _prefs!!.getString("ai_memory", "") ?: ""
        set(value) = _prefs!!.edit().putString("ai_memory", value).apply()

    var agentMemory: String
        get() = _prefs!!.getString("agent_memory", "") ?: ""
        set(value) = _prefs!!.edit().putString("agent_memory", value).apply()

    var googleIcon: String?
        get() = _prefs!!.getString("google_icon", null)
        set(value) = _prefs!!.edit().putString("google_icon", value).apply()

    var openaiIcon: String?
        get() = _prefs!!.getString("openai_icon", null)
        set(value) = _prefs!!.edit().putString("openai_icon", value).apply()

    var openrouterIcon: String?
        get() = _prefs!!.getString("openrouter_icon", null)
        set(value) = _prefs!!.edit().putString("openrouter_icon", value).apply()

    var groqIcon: String?
        get() = _prefs!!.getString("groq_icon", null)
        set(value) = _prefs!!.edit().putString("groq_icon", value).apply()

    var deepseekIcon: String?
        get() = _prefs!!.getString("deepseek_icon", null)
        set(value) = _prefs!!.edit().putString("deepseek_icon", value).apply()

    var claudeIcon: String?
        get() = _prefs!!.getString("claude_icon", null)
        set(value) = _prefs!!.edit().putString("claude_icon", value).apply()

    var mistralIcon: String?
        get() = _prefs!!.getString("mistral_icon", null)
        set(value) = _prefs!!.edit().putString("mistral_icon", value).apply()

    var grokIcon: String?
        get() = _prefs!!.getString("grok_icon", null)
        set(value) = _prefs!!.edit().putString("grok_icon", value).apply()

    var iconBaseUrl: String
        get() = _prefs!!.getString("icon_base_url", "https://raw.githubusercontent.com/android5/ai-chat/main/") ?: "https://raw.githubusercontent.com/android5/ai-chat/main/"
        set(value) = _prefs!!.edit().putString("icon_base_url", value).apply()

    // Model parameters
    var temperature: Float
        get() = _prefs!!.getFloat("param_temperature", 0.7f)
        set(value) = _prefs!!.edit().putFloat("param_temperature", value).apply()

    var topP: Float
        get() = _prefs!!.getFloat("param_top_p", 1.0f)
        set(value) = _prefs!!.edit().putFloat("param_top_p", value).apply()

    var maxTokens: Int
        get() = _prefs!!.getInt("param_max_tokens", 4096)
        set(value) = _prefs!!.edit().putInt("param_max_tokens", value).apply()

    var frequencyPenalty: Float
        get() = _prefs!!.getFloat("param_frequency_penalty", 0.0f)
        set(value) = _prefs!!.edit().putFloat("param_frequency_penalty", value).apply()

    var presencePenalty: Float
        get() = _prefs!!.getFloat("param_presence_penalty", 0.0f)
        set(value) = _prefs!!.edit().putFloat("param_presence_penalty", value).apply()

    var streamingEnabled: Boolean
        get() = _prefs!!.getBoolean("streaming_enabled", true)
        set(value) = _prefs!!.edit().putBoolean("streaming_enabled", value).apply()

    var showThinking: Boolean
        get() = _showThinkingState!!.value
        set(value) {
            _showThinkingState!!.value = value
            _prefs!!.edit().putBoolean("show_thinking", value).apply()
        }

    var webSearchEnabled: Boolean
        get() = _webSearchState!!.value
        set(value) {
            _webSearchState!!.value = value
            _prefs!!.edit().putBoolean("web_search_enabled", value).apply()
        }

    var themeMode: String
        get() = _themeModeState!!.value
        set(value) {
            _themeModeState!!.value = value
            _prefs!!.edit().putString("theme_mode", value).apply()
        }

    var currentConversationId: String?
        get() = _prefs!!.getString("current_conv_id", null)
        set(value) = _prefs!!.edit().putString("current_conv_id", value).apply()

    var conversations: List<Conversation>
        get() {
            val json = _prefs!!.getString("conversations_list", null) ?: return emptyList()
            val type = object : TypeToken<List<Conversation>>() {}.type
            return try {
                _gson!!.fromJson<List<Conversation>>(json, type) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }
        set(value) {
            val json = _gson!!.toJson(value)
            _prefs!!.edit().putString("conversations_list", json).apply()
        }

    val isSetupComplete: Boolean
        get() = !apiKey.isNullOrBlank() || Providers.ALL.any { hasApiKey(it.name) }

    companion object {
        private var _prefs: SharedPreferences? = null
        private var _gson: Gson? = null
        private var _showThinkingState: MutableState<Boolean>? = null
        private var _webSearchState: MutableState<Boolean>? = null
        private var _themeModeState: MutableState<String>? = null
    }
}
