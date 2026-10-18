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

/** How many tool rounds a single reply may trigger before the app stops looping. */
private const val MAX_TOOL_STEPS = 3

class ChatViewModel(application: Application, private val settingsManager: SettingsManager) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val gson = Gson()
    private val conversationStore = ConversationStore(context)
    /** Sandbox the file tools may read and write; see [AiTools] and [ToolFiles]. */
    private val toolScope: AiTools.Scope
        get() = AiTools.Scope(
            root = File(context.filesDir, ToolFiles.DIR),
            settings = settingsManager,
            // Empty unless the user granted storage access in settings.
            deviceRoots = DeviceStorage.roots(context),
            context = context
        )
    /** Title shown for a conversation that has not been auto-titled yet. */
    private val defaultChatTitle = context.getString(R.string.chat_default_title)

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
        val newConv = Conversation(title = defaultChatTitle)
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

    /** Provider whose catalog is currently held in [availableModels]. */
    private var modelsProvider: String? = null

    fun fetchModels() {
        val provider = settingsManager.selectedProvider
        val apiKey = settingsManager.apiKey
        viewModelScope.launch {
            val fetched = try {
                val response = if (!apiKey.isNullOrBlank()) {
                    if (Providers.isAnthropic(provider)) {
                        getApi().getModels(
                            url = Providers.modelsUrl(provider),
                            authorization = null,
                            xApiKey = apiKey,
                            anthropicVersion = Providers.ANTHROPIC_VERSION
                        )
                    } else {
                        getApi().getModels(Providers.modelsUrl(provider), "Bearer $apiKey")
                    }
                } else {
                    getApi().getModels(Providers.modelsUrl(provider))
                }
                response.data.orEmpty()
            } catch (e: Exception) {
                null
            }

            // The user may have switched provider while this request was in flight.
            if (provider != settingsManager.selectedProvider) return@launch

            if (fetched != null && fetched.isNotEmpty()) {
                availableModels.clear()
                availableModels.addAll(fetched.distinctBy { it.id }.sortedBy { it.displayName })
                modelsProvider = provider
            } else if (fetched != null || modelsProvider != provider || availableModels.isEmpty()) {
                // Empty catalog, or a failed fetch with nothing usable on screen:
                // fall back to the built-in list for this provider.
                availableModels.clear()
                availableModels.addAll(
                    Providers.defaultModels(provider).distinctBy { it.id }.sortedBy { it.displayName }
                )
                modelsProvider = provider
            }
            // Otherwise the fetch failed but the catalog on screen is already this
            // provider's, so keep it instead of downgrading to the built-in list.

            if (modelsProvider == provider &&
                availableModels.none { it.id == settingsManager.selectedModel }
            ) {
                settingsManager.selectedModel = Providers.defaultModel(provider)
            }
        }
    }

    fun sendMessage(content: String, activityContext: Context) {
        if (content.isBlank() && selectedImageUri == null) return
        if (currentJob != null) return

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
        runCompletion()
    }

    /**
     * Regenerates the most recent answer. It removes the assistant response(s)
     * after the last user turn and re-runs the request *without* appending the
     * user message again — the previous retry path called [sendMessage], which
     * duplicated the user's message on every regeneration.
     */
    fun regenerateLast() {
        if (currentJob != null) return
        if (messages.none { it.role == "user" }) return
        while (messages.isNotEmpty() && messages.last().role != "user") {
            messages.removeAt(messages.lastIndex)
        }
        updateCurrentConversation()
        runCompletion()
    }

    private fun runCompletion() {
        val prompt = messages.lastOrNull { it.role == "user" }?.content ?: return
        val apiKey = settingsManager.apiKey
        if (apiKey.isNullOrBlank()) {
            // Surface the problem instead of silently doing nothing when the
            // selected provider has no usable key.
            messages.add(Message(role = "assistant", content = context.getString(R.string.err_unauthorized)))
            updateCurrentConversation()
            return
        }

        currentJob = viewModelScope.launch {
            val webSearchOn = settingsManager.webSearchEnabled
            val isImageQuery = prompt.contains("image", ignoreCase = true) ||
                    prompt.contains("photo", ignoreCase = true) ||
                    prompt.contains("picture", ignoreCase = true) ||
                    prompt.contains("show me", ignoreCase = true) ||
                    prompt.contains("wallpaper", ignoreCase = true) ||
                    prompt.contains("pic", ignoreCase = true)

            loadingStatus = when {
                webSearchOn || isImageQuery -> LoadingStatus.SEARCHING
                prompt.contains("create", ignoreCase = true) && prompt.contains("image", ignoreCase = true) -> LoadingStatus.CREATING_IMAGE
                else -> LoadingStatus.THINKING
            }

            try {
                var searchGrounding: String? = null
                if (webSearchOn || isImageQuery) {
                    loadingStatus = LoadingStatus.SEARCHING
                    val results = WebSearchService.search(prompt, maxResults = 5)
                    val images = WebSearchService.searchImages(prompt, maxResults = 4)
                    searchGrounding = WebSearchService.formatGroundingContext(prompt, results, images)
                    loadingStatus = LoadingStatus.THINKING
                }

                // A reply can ask for tools; when it does, the results are fed back
                // and the request runs again, up to MAX_TOOL_STEPS rounds.
                var toolResults: String? = null
                val maxSteps = if (settingsManager.toolsEnabled) MAX_TOOL_STEPS else 1

                for (step in 0 until maxSteps) {
                    val isReasoning = isReasoningModel(settingsManager.selectedModel)
                    val isGoogle = settingsManager.selectedProvider == "Google"
                    val request = ChatRequest(
                        model = settingsManager.selectedModel,
                        messages = buildApiMessages(searchGrounding, toolResults),
                        tools = if (webSearchOn && (settingsManager.selectedProvider == "OpenAI" || settingsManager.selectedProvider == "Claude")) {
                            buildWebSearchTools(settingsManager.selectedProvider)
                        } else null,
                        stream = if (settingsManager.streamingEnabled) true else null,
                        temperature = if (isReasoning) null else settingsManager.temperature.toDouble(),
                        topP = if (isReasoning) null else settingsManager.topP.toDouble(),
                        maxTokens = if (isReasoning) null else settingsManager.maxTokens,
                        maxCompletionTokens = if (isReasoning) settingsManager.maxTokens else null,
                        frequencyPenalty = if (isReasoning || isGoogle) null else settingsManager.frequencyPenalty.toDouble(),
                        presencePenalty = if (isReasoning || isGoogle) null else settingsManager.presencePenalty.toDouble()
                    )

                    val isAnthropic = Providers.isAnthropic(settingsManager.selectedProvider)
                    val authHeader = if (isAnthropic) null else "Bearer $apiKey"
                    val xApiKeyHeader = if (isAnthropic) apiKey else null
                    val anthropicHeader = if (isAnthropic) Providers.ANTHROPIC_VERSION else null

                    val turn = if (settingsManager.streamingEnabled) {
                        streamResponse(authHeader, xApiKeyHeader, anthropicHeader, request)
                    } else {
                        nonStreamResponse(authHeader, xApiKeyHeader, anthropicHeader, request)
                    } ?: break

                    val runs = finalizeTurn(turn)
                    if (runs.isEmpty() || step == maxSteps - 1) break

                    toolResults = AiTools.formatResults(runs)
                    loadingStatus = LoadingStatus.THINKING
                }

                if (currentConversation?.title == defaultChatTitle && messages.size >= 2) {
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

    private fun buildApiMessages(searchGrounding: String?, toolResults: String? = null): List<ApiMessage> {
        val apiMessages = mutableListOf<ApiMessage>()
        val instructions = settingsManager.customInstructions
        val userMemory = settingsManager.aiMemory
        val agentMemory = settingsManager.agentMemory
        val toolsEnabled = settingsManager.toolsEnabled
        val systemContent = buildString {
            if (instructions.isNotBlank()) append(instructions).append("\n\n")
            if (userMemory.isNotBlank()) append("User-provided long-term memory:\n").append(userMemory).append("\n\n")
            if (agentMemory.isNotBlank()) append("Your self-managed memory of this user:\n").append(agentMemory).append("\n\n")
            append(
                "IMAGE DISPLAY CAPABILITY: You can display pictures and web images inline in your response by outputting Markdown image tags: `![Description](https://url/to/image.jpg)`. Use this whenever providing photos, pictures, diagrams, or visual examples."
            )
            if (toolsEnabled) {
                append("\n\n").append(AiTools.describe(ToolFiles.DIR))
            } else {
                // With tools switched off the model can still keep memory; it just
                // does so with the older fenced block instead of the <memory> tool.
                append("\n\n")
                append(
                    "You have a persistent memory that carries across all conversations with this user. " +
                    "When you learn something worth remembering (facts, preferences, context, plans), " +
                    "update your memory by responding with a fenced code block whose language is `memory` " +
                    "containing your COMPLETE updated memory (not just the new line). Only include that block " +
                    "when you actually want to save or update memory; otherwise do not use it."
                )
            }
            if (searchGrounding != null) {
                append("\n\n").append(searchGrounding)
            }
            if (toolResults != null) {
                append("\n\n").append(toolResults)
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

    private suspend fun streamResponse(authHeader: String?, xApiKey: String?, anthropicHeader: String?, request: ChatRequest): Turn? {
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
                                        content = visibleText(currentContent, streaming = true),
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

        // The message stays in its slot; finalizeTurn() decides what the transcript
        // shows and which tool calls to run.
        val raw = contentBuilder.toString()
        val reasoning = reasoningBuilder.toString().takeIf { it.isNotBlank() }
        withContext(Dispatchers.Main) {
            if (msgIndex in messages.indices) {
                messages[msgIndex] = messages[msgIndex].copy(
                    content = visibleText(raw, streaming = false),
                    reasoning = reasoning,
                    isStreaming = false
                )
            }
        }
        return Turn(raw, msgIndex, reasoning)
    }

    private suspend fun nonStreamResponse(authHeader: String?, xApiKey: String?, anthropicHeader: String?, request: ChatRequest): Turn? {
        val nonStreamRequest = request.copy(stream = null)
        val response = getApi().getCompletion(
            authorization = authHeader,
            xApiKey = xApiKey,
            anthropicVersion = anthropicHeader,
            request = nonStreamRequest
        )

        val assistantMsg = response.choices.firstOrNull()?.message ?: return null
        val reasoning = assistantMsg.thinking?.takeIf { it.isNotBlank() }
        messages.add(
            Message(
                role = "assistant",
                content = visibleText(assistantMsg.content, streaming = false),
                reasoning = reasoning,
                isStreaming = false
            )
        )
        updateCurrentConversation()
        return Turn(assistantMsg.content, messages.lastIndex, reasoning)
    }

    /** A completed assistant reply, before its tool tags have been acted on. */
    private data class Turn(val raw: String, val index: Int, val reasoning: String?)

    /**
     * Applies a finished reply: saves any memory it asked for, runs the tool calls
     * it made, and rewrites the message with the tags hidden. Returns the tool runs
     * so the caller can feed their results back to the model.
     */
    private suspend fun finalizeTurn(turn: Turn): List<AiTools.Run> {
        val (withoutMemory, newMemory) = extractMemory(turn.raw)
        if (newMemory != null) settingsManager.agentMemory = newMemory

        val calls = if (settingsManager.toolsEnabled) AiTools.parse(withoutMemory) else emptyList()
        val runs = calls.map { AiTools.execute(it, toolScope) }

        withContext(Dispatchers.Main) {
            if (turn.index in messages.indices) {
                messages[turn.index] = messages[turn.index].copy(
                    content = AiTools.strip(withoutMemory).trim(),
                    reasoning = turn.reasoning,
                    isStreaming = false,
                    tools = runs.map { run ->
                        // `label` is the workspace-relative path for the file
                        // tools, which is exactly what the transcript needs to
                        // link a run back to the file it touched.
                        val filePath =
                            if (run.name in AiTools.fileTools) run.label else null
                        ToolRun(run.name, run.label, run.ok, filePath)
                    }.ifEmpty { null }
                )
            }
        }
        updateCurrentConversation()
        return runs
    }

    /**
     * What the transcript shows for a reply. Tool tags and the memory block are
     * implementation detail, so they stay hidden even while the reply streams.
     */
    private fun visibleText(raw: String, streaming: Boolean): String {
        var text = raw
        if (streaming) {
            // An unterminated memory block would otherwise show up verbatim while
            // the model is still writing it.
            val fence = text.indexOf("```memory")
            if (fence != -1 && text.indexOf("```", fence + 3) == -1) text = text.substring(0, fence)
        }
        if (text.contains('<')) {
            text = if (streaming) AiTools.stripPartial(text) else AiTools.strip(text)
        }
        val display = extractMemory(text).first
        return if (streaming) display else display.trim()
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
        // The title request is fire-and-forget, so remember which conversation it
        // belongs to: the user can open another chat while it is still in flight,
        // and the reply used to be applied to whatever was current on arrival.
        val conv = currentConversation ?: return
        val firstMessage = messages.find { it.role == "user" }?.content ?: return

        viewModelScope.launch {
            try {
                val summaryPrompt = "Generate a short title (max 4 words) for this chat. Return only the title."

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
                val raw = response.choices.firstOrNull()?.message?.content ?: return@launch
                // Keep the first line only and drop the quoting/bullet decoration
                // models like to wrap titles in.
                val title = raw.lineSequence().firstOrNull().orEmpty()
                    .trim()
                    .trim('"', '\'', '*', '#', ' ')
                    .trim()
                if (title.isEmpty()) return@launch

                val index = conversations.indexOfFirst { it.id == conv.id }
                if (index == -1) return@launch
                val updatedConv = conversations[index].copy(title = title)
                conversations[index] = updatedConv
                if (currentConversation?.id == conv.id) currentConversation = updatedConv
                saveConversations()
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

