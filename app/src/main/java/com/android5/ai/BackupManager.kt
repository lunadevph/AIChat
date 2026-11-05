package com.android5.ai

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Password-encrypted portable backups. Version 1 is supported only for legacy import. */
object BackupManager {

    private const val MAGIC = "AICHATBACKUP"
    private const val VERSION = 2
    private const val MAX_BACKUP_BYTES = 64 * 1024 * 1024
    private val gson = Gson()

    data class ExportOptions(val conversations: Boolean = true, val images: Boolean = true, val apiKeys: Boolean = true)

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
        val disabledTools: Set<String>? = null,
        val showToolOutput: Boolean = true,
        val customProviders: List<Providers.Custom> = emptyList(),
        val selectedProvider: String? = null,
        val selectedModel: String? = null,
        val bubbleStyle: String = "tonal",
        val updateChannel: String? = null
    )

    data class Backup(
        val version: Int = VERSION,
        val createdAt: String = "",
        val conversations: List<Conversation> = emptyList(),
        val settings: BackupSettings = BackupSettings(),
        val apiKeys: Map<String, String?> = emptyMap(),
        val images: Map<String, String> = emptyMap()
    )

    /** Legacy files used a public certificate hash; never use this for new exports. */
    private fun legacyKey(context: Context): SecretKey {
        val certHash = AppSigning.signingCertSha256(context)
            ?.toByteArray(Charsets.UTF_8)
            ?: "fallback".toByteArray(Charsets.UTF_8)
        val salt = "AIChatBackupSalt-v1".toByteArray(Charsets.UTF_8)
        val md = MessageDigest.getInstance("SHA-256")
        md.update(certHash)
        md.update(salt)
        return SecretKeySpec(md.digest(), "AES")
    }

    fun export(context: Context, uri: Uri, password: CharArray, options: ExportOptions = ExportOptions()): Boolean {
        return try {
            val store = ConversationStore(context)
            val convs = if (options.conversations) store.listConversations().mapNotNull { store.loadConversation(it.id) } else emptyList()
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
                disabledTools = sm.disabledTools,
                showToolOutput = sm.showToolOutput,
                customProviders = sm.customProviders,
                selectedProvider = sm.selectedProvider,
                selectedModel = sm.selectedModel,
                bubbleStyle = sm.bubbleStyle,
                updateChannel = sm.updateChannel
            )
            val apiKeys = if (options.apiKeys) (Providers.ALL.map { it.name } + sm.customProviders.map { it.name })
                .associateWith { sm.getApiKey(it) } else emptyMap()
            val images = if (options.images) convs.flatMap { it.messages }.mapNotNull { it.localImageUrl }.distinct()
                .associateWith { path ->
                    val file = java.io.File(path).canonicalFile
                    require(file.parentFile == context.filesDir.canonicalFile && file.name.startsWith("img_"))
                    android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP)
                } else emptyMap()
            val backup = Backup(
                createdAt = AppClock.utcTimestamp(),
                conversations = convs,
                settings = settings,
                apiKeys = apiKeys,
                images = images
            )
            val json = gson.toJson(backup)
            require(json.toByteArray(Charsets.UTF_8).size <= MAX_BACKUP_BYTES - 128)
            val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
            val key = BackupCrypto.deriveKey(password, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val ct = cipher.doFinal(json.toByteArray(Charsets.UTF_8))
            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(MAGIC.toByteArray(Charsets.UTF_8))
                out.write(VERSION)
                out.write(salt)
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

    fun import(context: Context, uri: Uri, password: CharArray): Boolean {
        return try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(out.size() + count <= MAX_BACKUP_BYTES)
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            } ?: return false
            val magic = MAGIC.toByteArray(Charsets.UTF_8)
            if (bytes.size < magic.size + 1 + 12) return false
            if (!bytes.copyOfRange(0, magic.size).contentEquals(magic)) return false
            val version = bytes[magic.size].toInt()
            if (version !in 1..VERSION) return false
            val saltLength = if (version == 2) 16 else 0
            val offset = magic.size + 1
            if (bytes.size < offset + saltLength + 12 + 16) return false
            val salt = bytes.copyOfRange(offset, offset + saltLength)
            val iv = bytes.copyOfRange(offset + saltLength, offset + saltLength + 12)
            val ct = bytes.copyOfRange(offset + saltLength + 12, bytes.size)
            val key = if (version == 1) legacyKey(context) else BackupCrypto.deriveKey(password, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            val json = String(cipher.doFinal(ct), Charsets.UTF_8)
            val type = object : TypeToken<Backup>() {}.type
            val backup = gson.fromJson<Backup>(json, type) ?: return false

            val store = ConversationStore(context)
            // Validate identifiers before any writes; imported paths never select local files.
            backup.conversations.forEach { ConversationIds.requireValid(it.id) }
            require(backup.conversations.map { it.id }.distinct().size == backup.conversations.size)
            require(backup.conversations.all { conv -> conv.messages.map { it.id }.distinct().size == conv.messages.size })
            val restoredImages = backup.images.mapValues { (_, encoded) ->
                val data = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
                require(data.size <= 25 * 1024 * 1024)
                java.io.File(context.filesDir, "img_${java.util.UUID.randomUUID()}.jpg").apply { writeBytes(data) }.absolutePath
            }
            backup.conversations.forEach { conv ->
                store.saveConversation(conv.copy(messages = conv.messages.map { msg ->
                    msg.copy(localImageUrl = restoredImages[msg.localImageUrl], isStreaming = false)
                }))
            }
            val sm = SettingsManager(context)
            val s = backup.settings
            sm.customInstructions = s.customInstructions
            sm.aiMemory = s.aiMemory
            sm.agentMemory = s.agentMemory
            sm.themeMode = s.themeMode
            sm.bubbleStyle = s.bubbleStyle
            s.updateChannel?.let { sm.updateChannel = it }
            sm.temperature = s.temperature
            sm.topP = s.topP
            sm.maxTokens = s.maxTokens
            sm.frequencyPenalty = s.frequencyPenalty
            sm.presencePenalty = s.presencePenalty
            sm.streamingEnabled = s.streamingEnabled
            sm.showThinking = s.showThinking
            sm.webSearchEnabled = s.webSearchEnabled
            sm.toolsEnabled = s.toolsEnabled
            s.disabledTools?.let { sm.disabledTools = it }
            sm.showToolOutput = s.showToolOutput
            sm.customProviders = s.customProviders
            backup.apiKeys.forEach { (p, k) -> if (!k.isNullOrBlank()) check(sm.setApiKey(p, k)) }
            val provider = s.selectedProvider?.takeIf { sm.hasApiKey(it) }
                ?: backup.apiKeys.entries.firstOrNull { !it.value.isNullOrBlank() }?.key
            if (provider != null && Providers.all(sm.customProviders).any { it.name == provider }) {
                sm.selectedProvider = provider
                sm.selectedModel = s.selectedModel?.takeIf { provider == s.selectedProvider }
                    ?: Providers.defaultModel(provider, sm.customProviders)
            }
            true
        } catch (e: Exception) {
            android.util.Log.e("BackupManager", "Import failed", e)
            false
        }
    }
}
