package com.android5.ai

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.UUID

enum class LoadingStatus {
    IDLE, THINKING, SEARCHING, CREATING_IMAGE, STREAMING
}

class ChatViewModel(application: Application, private val settingsManager: SettingsManager) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val gson = Gson()
    private val conversationStore = ConversationStore(context)

    private fun getApi(): OpenRouterApi {
        return Providers.api(settingsManager.selectedProvider)
    }

    val conversations = mutableStateListOf<Conversation>()
    var currentConversation by mutableStateOf<Conversation?>(null)
    
    val messages = mutableStateListOf<Message>()
    var availableModels = mutableStateListOf<ModelInfo>()
    
    var loadingStatus by mutableStateOf(LoadingStatus.IDLE)
        private set

    private var currentJob: Job? = null

    val isLoading: Boolean get() = loadingStatus != LoadingStatus.IDLE

    var selectedImageUri by mutableStateOf<Uri?>(null)

    init {
        loadConversations()
        fetchModels()
    }

    private fun loadConversations() {
        val saved = conversationStore.listConversations()
        conversations.clear()
        conversations.addAll(saved)
        
        val lastId = settingsManager.currentConversationId
        val lastConv = conversations.find { it.id == lastId } ?: conversations.firstOrNull()
        
        if (lastConv != null) {
            selectConversation(lastConv)
        } else {
            createNewConversation()
        }
    }

    fun selectConversation(conversation: Conversation) {
        val full = conversationStore.loadConversation(conversation.id) ?: conversation
        val index = conversations.indexOfFirst { it.id == full.id }
        if (index != -1) conversations[index] = full
        currentConversation = full
        settingsManager.currentConversationId = full.id
        messages.clear()
        messages.addAll(full.messages)
    }

    fun createNewConversation() {
        val newConv = Conversation(title = "New Chat")
        conversations.add(0, newConv)
        selectConversation(newConv)
        saveConversations()
    }

    fun deleteConversation(conversation: Conversation) {
        conversations.remove(conversation)
        conversationStore.deleteConversation(conversation.id)
        if (currentConversation?.id == conversation.id) {
            val next = conversations.firstOrNull()
            if (next != null) selectConversation(next) else createNewConversation()
        }
        saveConversations()
    }

    fun fetchModels() {
        val provider = settingsManager.selectedProvider
        val apiKey = settingsManager.apiKey
        viewModelScope.launch {
            try {
                val response = if (!apiKey.isNullOrBlank()) {
                    if (Providers.isAnthropic(provider)) {
                        getApi().getModels(authorization = null, xApiKey = apiKey, anthropicVersion = Providers.ANTHROPIC_VERSION)
                    } else {
                        getApi().getModels("Bearer $apiKey")
                    }
                } else {
                    getApi().getModels(null)
                }
                if (!response.data.isNullOrEmpty()) {
                    availableModels.clear()
                    availableModels.addAll(response.data.sortedBy { it.displayName })
                } else {
                    availableModels.clear()
                    availableModels.addAll(Providers.defaultModels(provider).sortedBy { it.displayName })
                }
            } catch (e: Exception) {
                availableModels.clear()
                availableModels.addAll(Providers.defaultModels(provider).sortedBy { it.displayName })
            }

            if (availableModels.none { it.id == settingsManager.selectedModel }) {
                settingsManager.selectedModel = Providers.defaultModel(provider)
            }
        }
    }

    fun sendMessage(content: String, activityContext: Context) {
        if (content.isBlank() && selectedImageUri == null) return

        val imageUri = selectedImageUri
        val localPath = imageUri?.let { saveImageLocally(activityContext, it) }
        
        val userMessage = Message(
            role = "user", 
            content = content,
            localImageUrl = localPath
        )
        messages.add(userMessage)
        updateCurrentConversation()
        
        selectedImageUri = null
        val apiKey = settingsManager.apiKey ?: return

        currentJob = viewModelScope.launch {
            val webSearchOn = settingsManager.webSearchEnabled
            val isImageQuery = content.contains("image", ignoreCase = true) ||
                    content.contains("photo", ignoreCase = true) ||
                    content.contains("picture", ignoreCase = true) ||
                    content.contains("show me", ignoreCase = true) ||
                    content.contains("wallpaper", ignoreCase = true) ||
                    content.contains("pic", ignoreCase = true)

            loadingStatus = when {
                webSearchOn || isImageQuery -> LoadingStatus.SEARCHING
                content.contains("create", ignoreCase = true) && content.contains("image", ignoreCase = true) -> LoadingStatus.CREATING_IMAGE
                else -> LoadingStatus.THINKING
            }

            try {
                var searchGrounding: String? = null
                if (webSearchOn || isImageQuery) {
                    loadingStatus = LoadingStatus.SEARCHING
                    val results = WebSearchService.search(content, maxResults = 5)
                    val images = WebSearchService.searchImages(content, maxResults = 4)
                    searchGrounding = WebSearchService.formatGroundingContext(content, results, images)
                    loadingStatus = LoadingStatus.THINKING
                }

                val apiMessages = buildApiMessages(searchGrounding)

                val useStreaming = settingsManager.streamingEnabled
                val isReasoning = isReasoningModel(settingsManager.selectedModel)
                val request = ChatRequest(
                    model = settingsManager.selectedModel,
                    messages = apiMessages,
                    tools = if (webSearchOn && (settingsManager.selectedProvider == "OpenAI" || settingsManager.selectedProvider == "Claude")) {
                        buildWebSearchTools(settingsManager.selectedProvider)
                    } else null,
                    stream = if (useStreaming) true else null,
                    temperature = if (isReasoning) null else settingsManager.temperature.toDouble(),
                    topP = if (isReasoning) null else settingsManager.topP.toDouble(),
                    maxTokens = if (isReasoning) null else settingsManager.maxTokens,
                    maxCompletionTokens = if (isReasoning) settingsManager.maxTokens else null,
                    frequencyPenalty = if (isReasoning) null else settingsManager.frequencyPenalty.toDouble(),
                    presencePenalty = if (isReasoning) null else settingsManager.presencePenalty.toDouble()
                )

                val isAnthropic = Providers.isAnthropic(settingsManager.selectedProvider)
                val authHeader = if (isAnthropic) null else "Bearer $apiKey"
                val xApiKeyHeader = if (isAnthropic) apiKey else null
                val anthropicHeader = if (isAnthropic) Providers.ANTHROPIC_VERSION else null

                if (useStreaming) {
                    streamResponse(authHeader, xApiKeyHeader, anthropicHeader, request)
                } else {
                    nonStreamResponse(authHeader, xApiKeyHeader, anthropicHeader, request)
                }

                if (currentConversation?.title == "New Chat" && messages.size >= 2) {
                    generateChatTitle(apiKey)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                val lastIdx = messages.lastIndex
                if (lastIdx >= 0 && messages[lastIdx].isStreaming) {
                    if (messages[lastIdx].content.isBlank()) {
                        messages.removeAt(lastIdx)
                    } else {
                        messages[lastIdx] = messages[lastIdx].copy(isStreaming = false)
                    }
                    updateCurrentConversation()
                }
            } catch (e: HttpException) {
                val body = try { e.response()?.errorBody()?.string() } catch (_: Exception) { null }
                android.util.Log.e("AIChat", "HTTP ${e.code()}: $body")
                val errorMsg = when (e.code()) {
                    401 -> context.getString(R.string.err_unauthorized)
                    402 -> context.getString(R.string.err_insufficient_credits)
                    403 -> context.getString(R.string.err_forbidden)
                    404 -> context.getString(R.string.err_not_found)
                    429 -> context.getString(R.string.err_rate_limit)
                    500, 502, 503 -> context.getString(R.string.err_server)
                    else -> context.getString(R.string.err_api_code, e.code())
                }
                val streamIdx = messages.indexOfLast { it.isStreaming }
                if (streamIdx != -1) {
                    messages[streamIdx] = Message(role = "assistant", content = errorMsg, isStreaming = false)
                } else {
                    messages.add(Message(role = "assistant", content = errorMsg))
                }
                updateCurrentConversation()
            } catch (e: Exception) {
                android.util.Log.e("AIChat", "Request failed", e)
                val errorMsg = context.getString(R.string.err_network, e.localizedMessage ?: "Unknown error")
                val streamIdx = messages.indexOfLast { it.isStreaming }
                if (streamIdx != -1) {
                    messages[streamIdx] = Message(role = "assistant", content = errorMsg, isStreaming = false)
                } else {
                    messages.add(Message(role = "assistant", content = errorMsg))
                }
                updateCurrentConversation()
            } finally {
                loadingStatus = LoadingStatus.IDLE
                currentJob = null
            }
        }
    }

    private fun buildApiMessages(searchGrounding: String?): List<ApiMessage> {
        val apiMessages = mutableListOf<ApiMessage>()
        val instructions = settingsManager.customInstructions
        val userMemory = settingsManager.aiMemory
        val agentMemory = settingsManager.agentMemory
        val systemContent = buildString {
            if (instructions.isNotBlank()) append(instructions).append("\n\n")
            if (userMemory.isNotBlank()) append("User-provided long-term memory:\n").append(userMemory).append("\n\n")
            if (agentMemory.isNotBlank()) append("Your self-managed memory of this user:\n").append(agentMemory).append("\n\n")
            append(
                "You have a persistent memory that carries across all conversations with this user. " +
                "When you learn something worth remembering (facts, preferences, context, plans), " +
                "update your memory by responding with a fenced code block whose language is `memory` " +
                "containing your COMPLETE updated memory (not just the new line). Only include that block " +
                "when you actually want to save or update memory; otherwise do not use it.\n\n" +
                "IMAGE DISPLAY CAPABILITY: You can display pictures and web images inline in your response by outputting Markdown image tags: `![Description](https://url/to/image.jpg)`. Use this whenever providing photos, pictures, diagrams, or visual examples."
            )
            if (searchGrounding != null) {
                append("\n\n").append(searchGrounding)
            }
        }
        if (systemContent.isNotBlank()) {
            apiMessages.add(ApiMessage(role = "system", content = systemContent))
        }

        apiMessages.addAll(messages.map { msg ->
            if (msg.localImageUrl != null && msg.role == "user") {
                val file = File(msg.localImageUrl)
                val base64Image = if (file.exists()) {
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    val outputStream = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 60, outputStream)
                    Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
                } else null
                
                val contentParts = mutableListOf<ContentPart>()
                if (msg.content.isNotBlank()) {
                    contentParts.add(ContentPart(type = "text", text = msg.content))
                }
                if (base64Image != null) {
                    contentParts.add(ContentPart(
                        type = "image_url", 
                        imageUrl = ApiImageUrl(url = "data:image/jpeg;base64,$base64Image")
                    ))
                }
                ApiMessage(role = msg.role, content = contentParts)
            } else {
                ApiMessage(role = msg.role, content = msg.content)
            }
        })
        return apiMessages
    }

    private fun isReasoningModel(modelId: String): Boolean {
        val lower = modelId.lowercase()
        return lower.startsWith("o1") || lower.startsWith("o3") || lower.startsWith("o4") ||
               lower.contains("/o1") || lower.contains("/o3") || lower.contains("/o4")
    }

    private suspend fun streamResponse(authHeader: String?, xApiKey: String?, anthropicHeader: String?, request: ChatRequest) {
        val responseBody = getApi().getCompletionStream(
            authorization = authHeader,
            xApiKey = xApiKey,
            anthropicVersion = anthropicHeader,
            request = request
        )

        // Add placeholder streaming message
        val streamingMessage = Message(role = "assistant", content = "", isStreaming = true)
        messages.add(streamingMessage)
        val msgIndex = messages.lastIndex
        loadingStatus = LoadingStatus.STREAMING

        val contentBuilder = StringBuilder()
        val reasoningBuilder = StringBuilder()
        var lastUiUpdateTime = 0L

        withContext(Dispatchers.IO) {
            responseBody.use { body ->
                val reader = BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    ensureActive()
                    val trimmed = line!!.trim()
                    
                    // Skip empty lines and SSE comments
                    if (trimmed.isEmpty() || trimmed.startsWith(":")) continue
                    
                    // Handle SSE data lines
                    if (!trimmed.startsWith("data:")) continue
                    val data = trimmed.removePrefix("data:").trim()
                    
                    // Handle stream end sentinel
                    if (data == "[DONE]") break

                    try {
                        val chunk = gson.fromJson(data, StreamChunk::class.java) ?: continue

                        // Check for mid-stream error from provider
                        if (chunk.error != null) {
                            val errMsg = chunk.error.message ?: "Stream error"
                            contentBuilder.append(errMsg)
                            break
                        }

                        val delta = chunk.choices?.firstOrNull()?.delta ?: continue

                        // Accumulate content and reasoning
                        delta.content?.let { contentBuilder.append(it) }
                        delta.thinking?.let { reasoningBuilder.append(it) }

                        val now = System.currentTimeMillis()
                        if (now - lastUiUpdateTime >= 35) {
                            lastUiUpdateTime = now
                            val currentContent = contentBuilder.toString()
                            val currentReasoning = reasoningBuilder.toString().takeIf { it.isNotBlank() }
                            withContext(Dispatchers.Main) {
                                if (msgIndex in messages.indices) {
                                    messages[msgIndex] = messages[msgIndex].copy(
                                        content = currentContent,
                                        reasoning = currentReasoning,
                                        isStreaming = true
                                    )
                                }
                            }
                        }

                        // Check finish reason
                        if (chunk.choices?.firstOrNull()?.finishReason != null) break
                    } catch (_: Exception) {
                        // Skip malformed chunks
                    }
                }
            }
        }

        // Finalize: extract memory and mark as done
        val finalContent = contentBuilder.toString()
        val finalReasoning = reasoningBuilder.toString().takeIf { it.isNotBlank() }
        val (displayContent, newMemory) = extractMemory(finalContent)
        if (newMemory != null) settingsManager.agentMemory = newMemory

        withContext(Dispatchers.Main) {
            if (msgIndex in messages.indices) {
                messages[msgIndex] = messages[msgIndex].copy(
                    content = displayContent,
                    reasoning = finalReasoning,
                    isStreaming = false
                )
            }
        }
        updateCurrentConversation()
    }

    private suspend fun nonStreamResponse(authHeader: String?, xApiKey: String?, anthropicHeader: String?, request: ChatRequest) {
        val nonStreamRequest = request.copy(stream = null)
        val response = getApi().getCompletion(
            authorization = authHeader,
            xApiKey = xApiKey,
            anthropicVersion = anthropicHeader,
            request = nonStreamRequest
        )

        response.choices.firstOrNull()?.message?.let { assistantMsg ->
            val (displayContent, newMemory) = extractMemory(assistantMsg.content)
            if (newMemory != null) settingsManager.agentMemory = newMemory

            val reasoning = assistantMsg.thinking?.takeIf { it.isNotBlank() }
            val assistantMessage = Message(role = "assistant", content = displayContent, reasoning = reasoning, isStreaming = false)
            messages.add(assistantMessage)
            updateCurrentConversation()
        }
    }

    fun stopGeneration() {
        currentJob?.cancel()
        val lastIdx = messages.lastIndex
        if (lastIdx >= 0 && messages[lastIdx].isStreaming) {
            if (messages[lastIdx].content.isBlank()) {
                messages.removeAt(lastIdx)
            } else {
                messages[lastIdx] = messages[lastIdx].copy(isStreaming = false)
            }
            updateCurrentConversation()
        }
        loadingStatus = LoadingStatus.IDLE
    }

    private fun buildWebSearchTools(provider: String): List<Map<String, Any>> {
        return when (provider) {
            "OpenAI" -> listOf(mapOf("type" to "web_search_preview"))
            "Claude" -> listOf(
                mapOf(
                    "type" to "web_search_20250305",
                    "name" to "web_search",
                    "max_uses" to 5
                )
            )
            else -> listOf(
                mapOf(
                    "type" to "web_search",
                    "search_context_size" to "medium"
                )
            )
        }
    }

    private fun generateChatTitle(apiKey: String) {
        viewModelScope.launch {
            try {
                val summaryPrompt = "Generate a short title (max 4 words) for this chat. Return only the title."
                val firstMessage = messages.find { it.role == "user" }?.content ?: return@launch
                
                val request = ChatRequest(
                    model = settingsManager.selectedModel,
                    messages = listOf(
                        ApiMessage(role = "system", content = summaryPrompt),
                        ApiMessage(role = "user", content = firstMessage)
                    )
                )
                val isAnthropic = Providers.isAnthropic(settingsManager.selectedProvider)
                val response = getApi().getCompletion(
                    authorization = if (isAnthropic) null else "Bearer $apiKey",
                    xApiKey = if (isAnthropic) apiKey else null,
                    anthropicVersion = if (isAnthropic) Providers.ANTHROPIC_VERSION else null,
                    request = request
                )
                response.choices.firstOrNull()?.message?.content?.trim()?.let { title ->
                    if (title.isNotEmpty()) {
                        currentConversation?.let { conv ->
                            val updatedConv = conv.copy(title = title.removeSurrounding("\""))
                            val index = conversations.indexOfFirst { it.id == conv.id }
                            if (index != -1) conversations[index] = updatedConv
                            currentConversation = updatedConv
                            saveConversations()
                        }
                    }
                }
            } catch (e: Exception) {}
        }
    }

    private fun saveImageLocally(context: Context, uri: Uri): String? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val bitmap = BitmapFactory.decodeStream(inputStream)
            val file = File(context.filesDir, "img_${System.currentTimeMillis()}.jpg")
            val outputStream = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
            outputStream.close()
            file.absolutePath
        } catch (e: Exception) { null }
    }

    private fun updateCurrentConversation() {
        currentConversation?.let { conv ->
            val updatedConv = conv.copy(
                messages = messages.toList(),
                lastUpdated = System.currentTimeMillis()
            )
            val index = conversations.indexOfFirst { it.id == conv.id }
            if (index != -1) conversations[index] = updatedConv
            currentConversation = updatedConv
            saveConversations()
        }
    }

    private fun saveConversations() {
        currentConversation?.let { conversationStore.saveConversation(it) }
    }

    fun clearHistory() {
        messages.clear()
        updateCurrentConversation()
    }

    private fun extractMemory(content: String): Pair<String, String?> {
        val regex = Regex("```memory\\s*\\n([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        val match = regex.find(content) ?: return content to null
        val memory = match.groupValues[1].trim()
        val display = content.replace(regex, "").trim()
        return display to memory.takeIf { it.isNotBlank() }
    }
}

