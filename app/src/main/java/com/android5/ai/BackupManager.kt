package com.android5.ai

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Portable, encrypted backup of the app's data.
 *
 * Everything — conversations, settings and API keys — is serialized to JSON and
 * encrypted as a single AES/GCM blob, then written to a `.ai` file the user
 * picks. The key is derived from the app's signing certificate, so a backup
 * made by an official build can only be read by another official build, and a
 * tampered build cannot read it at all. That is what "encrypted with the app's
 * signing key" means here: the signing identity is the secret the encryption
 * leans on.
 */
object BackupManager {

    private const val MAGIC = "AICHATBACKUP"
    private const val VERSION = 1
    private val gson = Gson()

    data class BackupSettings(
        val customInstructions: String = "",
        val aiMemory: String = "",
        val agentMemory: String = "",
        val themeMode: String = "dynamic",
        val temperature: Float = 0.7f,
        val topP: Float = 1.0f,
        val maxTokens: Int = 4096,
        val frequencyPenalty: Float = 0.0f,
        val presencePenalty: Float = 0.0f,
        val streamingEnabled: Boolean = true,
        val showThinking: Boolean = true,
        val webSearchEnabled: Boolean = false,
        val toolsEnabled: Boolean = true,
        val customProviders: List<Providers.Custom> = emptyList()
    )

    data class Backup(
        val version: Int = VERSION,
        val createdAt: String = "",
        val conversations: List<Conversation> = emptyList(),
        val settings: BackupSettings = BackupSettings(),
        val apiKeys: Map<String, String?> = emptyMap()
    )

    /** Derives the AES key from the app's signing certificate. */
    private fun deriveKey(context: Context): SecretKey {
        val certHash = AppSigning.signingCertSha256(context)
            ?.toByteArray(Charsets.UTF_8)
            ?: "fallback".toByteArray(Charsets.UTF_8)
        val salt = "AIChatBackupSalt-v1".toByteArray(Charsets.UTF_8)
        val md = MessageDigest.getInstance("SHA-256")
        md.update(certHash)
        md.update(salt)
        return SecretKeySpec(md.digest(), "AES")
    }

    fun export(context: Context, uri: Uri): Boolean {
        return try {
            val store = ConversationStore(context)
            val convs = store.listConversations().mapNotNull { store.loadConversation(it.id) }
            val sm = SettingsManager(context)
            val settings = BackupSettings(
                customInstructions = sm.customInstructions,
                aiMemory = sm.aiMemory,
                agentMemory = sm.agentMemory,
                themeMode = sm.themeMode,
                temperature = sm.temperature,
                topP = sm.topP,
                maxTokens = sm.maxTokens,
                frequencyPenalty = sm.frequencyPenalty,
                presencePenalty = sm.presencePenalty,
                streamingEnabled = sm.streamingEnabled,
                showThinking = sm.showThinking,
                webSearchEnabled = sm.webSearchEnabled,
                toolsEnabled = sm.toolsEnabled,
                customProviders = sm.customProviders
            )
            val apiKeys = (Providers.ALL.map { it.name } + sm.customProviders.map { it.name })
                .associateWith { sm.getApiKey(it) }
            val backup = Backup(
                createdAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date()),
                conversations = convs,
                settings = settings,
                apiKeys = apiKeys
            )
            val json = gson.toJson(backup)
            val key = deriveKey(context)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val ct = cipher.doFinal(json.toByteArray(Charsets.UTF_8))
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(MAGIC.toByteArray(Charsets.UTF_8))
                out.write(VERSION)
                out.write(iv)
                out.write(ct)
                out.flush()
            } ?: return false
            true
        } catch (e: Exception) {
            android.util.Log.e("BackupManager", "Export failed", e)
            false
        }
    }

    fun import(context: Context, uri: Uri): Boolean {
        return try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return false
            val magic = MAGIC.toByteArray(Charsets.UTF_8)
            if (bytes.size < magic.size + 1 + 12) return false
            if (!bytes.copyOfRange(0, magic.size).contentEquals(magic)) return false
            val version = bytes[magic.size].toInt()
            if (version != VERSION) return false
            val iv = bytes.copyOfRange(magic.size + 1, magic.size + 1 + 12)
            val ct = bytes.copyOfRange(magic.size + 1 + 12, bytes.size)
            val key = deriveKey(context)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            val json = String(cipher.doFinal(ct), Charsets.UTF_8)
            val type = object : TypeToken<Backup>() {}.type
            val backup = gson.fromJson<Backup>(json, type) ?: return false

            val store = ConversationStore(context)
            backup.conversations.forEach { store.saveConversation(it) }
            val sm = SettingsManager(context)
            val s = backup.settings
            sm.customInstructions = s.customInstructions
            sm.aiMemory = s.aiMemory
            sm.agentMemory = s.agentMemory
            sm.themeMode = s.themeMode
            sm.temperature = s.temperature
            sm.topP = s.topP
            sm.maxTokens = s.maxTokens
            sm.frequencyPenalty = s.frequencyPenalty
            sm.presencePenalty = s.presencePenalty
            sm.streamingEnabled = s.streamingEnabled
            sm.showThinking = s.showThinking
            sm.webSearchEnabled = s.webSearchEnabled
            sm.toolsEnabled = s.toolsEnabled
            sm.customProviders = s.customProviders
            backup.apiKeys.forEach { (p, k) -> if (!k.isNullOrBlank()) sm.setApiKey(p, k) }
            true
        } catch (e: Exception) {
            android.util.Log.e("BackupManager", "Import failed", e)
            false
        }
    }
}
