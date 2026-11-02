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
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

enum class LoadingStatus {
    IDLE, THINKING, SEARCHING, STREAMING, RATE_LIMITED
}

/** How many tool rounds a single reply may trigger before the app stops looping. */
private const val MAX_TOOL_STEPS = 8

/**
 * Rate limits are retried rather than reported: providers answer 429 for a busy key
 * or a throttled free tier, and the wait is usually seconds.
 */
private const val MAX_RATE_LIMIT_RETRIES = 4
/** First retry wait; it doubles each attempt (2s, 4s, 8s, 16s). */
private const val RATE_LIMIT_BASE_DELAY_MS = 2_000L
/** Upper bound on a provider's Retry-After, so a bad header cannot stall a reply. */
private const val RATE_LIMIT_MAX_DELAY_MS = 60_000L

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

    private var requestProvider: String? = null
    private var requestModel: String? = null
    private var requestTranscript: List<Message>? = null
    private val toolHistory = mutableListOf<ApiMessage>()
    private var allowToolCalls = true
    private var forceNonStream = false

    private fun getApi(): OpenRouterApi = Providers.api(requestProvider ?: settingsManager.selectedProvider)

    val conversations = mutableStateListOf<Conversation>()
    var currentConversation by mutableStateOf<Conversation?>(null)
    
    val messages = mutableStateListOf<Message>()
    var availableModels = mutableStateListOf<ModelInfo>()
    
    var loadingStatus by mutableStateOf(LoadingStatus.IDLE)
        private set

    /** Seconds until the next rate-limit retry, while that wait is on screen. */
    var rateLimitRetryIn by mutableStateOf(0)
        private set

    private var currentJob: Job? = null
    private var activeStream: okhttp3.ResponseBody? = null

    /**
     * Error body read while classifying a failure, kept so the handler that shows
     * the message can still read it: a Retrofit error body can only be consumed once.
     */
    private var lastErrorBody: String? = null

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
        if (currentConversation?.id == conversation.id) return
        if (currentConversation?.id != conversation.id) stopGeneration()
        val full = conversationStore.loadConversation(conversation.id) ?: conversation
        val index = conversations.indexOfFirst { it.id == full.id }
        if (index != -1) conversations[index] = full
        currentConversation = full
        settingsManager.currentConversationId = full.id
        messages.clear()
        messages.addAll(full.messages)
        // An image picked for one chat must not leak into another.
        selectedImageUri = null
    }

    fun createNewConversation() {
        val newConv = Conversation(title = defaultChatTitle)
        conversations.add(0, newConv)
        selectConversation(newConv)
        saveConversations()
    }

    fun deleteConversation(conversation: Conversation) {
        if (currentConversation?.id == conversation.id) stopGeneration()
        val full = conversationStore.loadConversation(conversation.id)
        full?.messages?.forEach { msg ->
            msg.localImageUrl?.let { path -> File(path).takeIf { it.exists() }?.delete() }
        }
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
        val custom = settingsManager.customProviders
        viewModelScope.launch {
            val fetched = try {
                val response = if (!apiKey.isNullOrBlank()) {
                    if (Providers.isAnthropic(provider)) {
                        getAnthropicApi().getModels(
                            url = Providers.modelsUrl(provider, custom),
                            xApiKey = apiKey,
                            anthropicVersion = Providers.ANTHROPIC_VERSION
                        )
                    } else {
                        getApi().getModels(Providers.modelsUrl(provider, custom), "Bearer $apiKey")
                    }
                } else {
                    getApi().getModels(Providers.modelsUrl(provider, custom))
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
                // fall back to the list for this provider.
                availableModels.clear()
                availableModels.addAll(
                    Providers.defaultModels(provider, custom)
                        .distinctBy { it.id }
                        .sortedBy { it.displayName }
                )
                modelsProvider = provider
            }
            // Otherwise the fetch failed but the catalog on screen is already this
            // provider's, so keep it instead of downgrading to the built-in list.

            if (modelsProvider == provider &&
                availableModels.isNotEmpty() &&
                availableModels.none { it.id == settingsManager.selectedModel }
            ) {
                val dm = Providers.defaultModel(provider, custom)
                if (dm.isNotBlank()) settingsManager.selectedModel = dm
            }
        }
    }

    fun sendMessage(content: String, activityContext: Context) {
        if (content.isBlank() && selectedImageUri == null) return
        if (currentJob != null) return

        val imageUri = selectedImageUri
        val localPath = imageUri?.let { saveImageLocally(activityContext, it) }
        // If the user attached an image but it could not be read, say so instead
        // of silently dropping it — otherwise the reply answers a different question.
        if (imageUri != null && localPath == null) {
            messages.add(
                Message(
                    role = "assistant",
                    content = context.getString(R.string.err_image_attach),
                    isError = true
                )
            )
            updateCurrentConversation()
            selectedImageUri = null
            return
        }

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
            // Keep completed actions when retrying the answer, so writes are not repeated.
            if (messages.last().tools.orEmpty().any { it.result != null }) break
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
            messages.add(Message(role = "assistant", content = context.getString(R.string.err_unauthorized), isError = true))
            updateCurrentConversation()
            return
        }

        val conversationId = currentConversation?.id
        requestProvider = settingsManager.selectedProvider
        requestModel = settingsManager.selectedModel
        requestTranscript = messages.toList()
        toolHistory.clear()
        currentJob = viewModelScope.launch {
            val webSearchOn = settingsManager.webSearchEnabled
            // Only fire the automatic image search for a prompt that clearly asks
            // for a picture, so ordinary text questions do not pay for it.
            val isImageQuery = Regex(
                """\b(image|picture|photo|wallpaper)s?\b""",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(prompt)

            loadingStatus = when {
                webSearchOn || isImageQuery -> LoadingStatus.SEARCHING
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

                ToolReplyLoop.run(
                    maxToolRounds = if (settingsManager.toolsEnabled) MAX_TOOL_STEPS else 0,
                    request = { allowTools, retryEmpty ->
                        allowToolCalls = settingsManager.toolsEnabled && allowTools
                        forceNonStream = retryEmpty
                        loadingStatus = LoadingStatus.THINKING
                        val correction = if (retryEmpty || !allowTools) context.getString(R.string.tool_followup_instruction) else null
                        val turn = if (Providers.isAnthropic(requestProvider.orEmpty())) {
                            requestAnthropicTurn(apiKey, searchGrounding, null, correction)
                        } else {
                            requestTurn(apiKey, searchGrounding, null, correction)
                        }
                        if (turn != null && turn.raw.isBlank() && turn.calls.isEmpty() && turn.index in messages.indices) {
                            messages.removeAt(turn.index)
                            updateCurrentConversation()
                        }
                        turn
                    },
                    apply = { turn -> finalizeTurn(turn) }
                )

                if (currentConversation?.title == defaultChatTitle && messages.size >= 2) {
                    generateChatTitle(apiKey)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (currentConversation?.id != conversationId) return@launch
                val lastIdx = messages.lastIndex
                if (lastIdx >= 0 && messages[lastIdx].isStreaming) {
                    if (messages[lastIdx].content.isBlank()) {
                        messages.removeAt(lastIdx)
                    } else {
                        messages[lastIdx] = messages[lastIdx].copy(isStreaming = false)
                    }
                    updateCurrentConversation()
                }
            } catch (e: EmptyReplyException) {
                showReplyError(context.getString(R.string.err_empty_reply))
            } catch (e: HttpException) {
                if (currentJob?.isCancelled == true || currentConversation?.id != conversationId) return@launch
                val body = lastErrorBody ?: try { e.response()?.errorBody()?.string() } catch (_: Exception) { null }
                lastErrorBody = null
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
                showReplyError(errorMsg)
            } catch (e: Exception) {
                if (currentJob?.isCancelled == true || currentConversation?.id != conversationId) return@launch
                android.util.Log.e("AIChat", "Request failed", e)
                showReplyError(context.getString(R.string.err_network, e.localizedMessage ?: "Unknown error"))
            } finally {
                loadingStatus = LoadingStatus.IDLE
                rateLimitRetryIn = 0
                activeStream = null
                currentJob = null
                requestProvider = null
                requestModel = null
                requestTranscript = null
                toolHistory.clear()
                forceNonStream = false
            }
        }
    }

    private fun buildApiMessages(
        searchGrounding: String?,
        toolResults: String? = null,
        correction: String? = null
    ): List<ApiMessage> {
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
            append("\n\n")
            if (toolsEnabled) {
                append("\n\n").append(AiTools.describe(toolScope))
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
            if (!correction.isNullOrBlank()) {
                append("\n\n").append(correction)
            }
        }
        if (systemContent.isNotBlank()) {
            apiMessages.add(ApiMessage(role = "system", content = systemContent))
        }

        apiMessages.addAll((requestTranscript ?: messages.toList()).filterNot { it.isError }.flatMap { msg ->
            val exchange = NativeTools.restoreExchange(msg)
            if (exchange.isNotEmpty()) return@flatMap exchange
            listOf(if (msg.localImageUrl != null && msg.role == "user") {
                val file = File(msg.localImageUrl)
                val base64Image = if (file.exists()) {
                    Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
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
                ApiMessage(role = msg.role, content = msg.content, reasoningContent = if (msg.role == "assistant") msg.reasoning.orEmpty() else null)
            })
        })
        apiMessages.addAll(toolHistory)
        val needsReasoning = requestProvider == "DeepSeek" || requestModel.orEmpty().contains("deepseek", ignoreCase = true)
        return if (needsReasoning) apiMessages else apiMessages.map { it.copy(reasoningContent = null) }
    }

    private fun isReasoningModel(modelId: String): Boolean {
        val lower = modelId.lowercase()
        return lower.startsWith("o1") || lower.startsWith("o3") || lower.startsWith("o4") ||
               lower.contains("/o1") || lower.contains("/o3") || lower.contains("/o4")
    }

    /** One request round — streaming or not — with the current model's parameters. */
    private suspend fun requestTurn(
        apiKey: String,
        searchGrounding: String?,
        toolResults: String?,
        correction: String?
    ): ToolReply? {
        lastErrorBody = null
        val isReasoning = isReasoningModel((requestModel ?: settingsManager.selectedModel))
        val isGoogle = (requestProvider ?: settingsManager.selectedProvider) == "Google"
        val apiMessages = buildApiMessages(searchGrounding, toolResults, correction)
        val hasToolContext = apiMessages.any { !it.toolCalls.isNullOrEmpty() }
        val request = ChatRequest(
            model = (requestModel ?: settingsManager.selectedModel),
            messages = apiMessages,
            tools = if (allowToolCalls || hasToolContext) NativeTools.openAiDefinitions() else null,
            toolChoice = if (!allowToolCalls && hasToolContext) "none" else null,
            stream = if (settingsManager.streamingEnabled && !forceNonStream) true else null,
            temperature = if (isReasoning) null else settingsManager.temperature.toDouble(),
            topP = if (isReasoning) null else settingsManager.topP.toDouble(),
            maxTokens = if (isReasoning) null else settingsManager.maxTokens,
            maxCompletionTokens = if (isReasoning) settingsManager.maxTokens else null,
            frequencyPenalty = if (isReasoning || isGoogle) null else settingsManager.frequencyPenalty.toDouble(),
            presencePenalty = if (isReasoning || isGoogle) null else settingsManager.presencePenalty.toDouble()
        )

        val isAnthropic = Providers.isAnthropic((requestProvider ?: settingsManager.selectedProvider))
        val authHeader = if (isAnthropic) null else "Bearer $apiKey"
        val xApiKeyHeader = if (isAnthropic) apiKey else null
        val anthropicHeader = if (isAnthropic) Providers.ANTHROPIC_VERSION else null

        return retryingOnRateLimit {
            if (settingsManager.streamingEnabled && !forceNonStream) {
                streamResponse(authHeader, xApiKeyHeader, anthropicHeader, request)
            } else {
                nonStreamResponse(authHeader, xApiKeyHeader, anthropicHeader, request)
            }
        }
    }

    // ---------------------------------------------------------------------
    // Anthropic (Claude) path. Claude is the one provider that is not
    // OpenAI-shaped: it speaks the Messages API at /v1/messages, with a
    // separate `system` field and content blocks instead of a `content` string.
    // Native tool_use/tool_result blocks are translated to the shared executor.
    // ---------------------------------------------------------------------

    private suspend fun requestAnthropicTurn(
        apiKey: String,
        searchGrounding: String?,
        toolResults: String?,
        correction: String?
    ): ToolReply? {
        lastErrorBody = null
        val isReasoning = isReasoningModel((requestModel ?: settingsManager.selectedModel))
        val apiMessages = buildApiMessages(searchGrounding, toolResults, correction)
        val (system, messages) = buildAnthropicMessages(apiMessages)
        val hasToolContext = apiMessages.any { !it.toolCalls.isNullOrEmpty() }

        val request = AnthropicRequest(
            model = (requestModel ?: settingsManager.selectedModel),
            messages = messages,
            system = system,
            tools = if (allowToolCalls || hasToolContext) NativeTools.anthropicDefinitions() else null,
            toolChoice = if (!allowToolCalls && hasToolContext) mapOf("type" to "none") else null,
            maxTokens = settingsManager.maxTokens.coerceAtLeast(1),
            stream = if (settingsManager.streamingEnabled && !forceNonStream) true else null,
            temperature = if (isReasoning) null else settingsManager.temperature.toDouble(),
            topP = if (isReasoning) null else settingsManager.topP.toDouble()
        )

        return retryingOnRateLimit {
            val api = getAnthropicApi()
            if (settingsManager.streamingEnabled && !forceNonStream) {
                anthropicStreamResponse(api, apiKey, request)
            } else {
                anthropicNonStreamResponse(api, apiKey, request)
            }
        }
    }

    private fun buildAnthropicMessages(apiMessages: List<ApiMessage>): Pair<String?, List<AnthropicMessage>> {
        val systemParts = mutableListOf<String>()
        val messages = mutableListOf<AnthropicMessage>()
        for (am in apiMessages) {
            if (am.role == "system") {
                val text = when (val c = am.content) {
                    is String -> c
                    is List<*> -> c.filterIsInstance<ContentPart>().joinToString("\n") { it.text.orEmpty() }
                    else -> c.toString()
                }
                if (text.isNotBlank()) systemParts.add(text)
                continue
            }
            if (am.role == "tool") {
                val block = AnthropicContentBlock(type = "tool_result", toolUseId = am.toolCallId, content = am.content.toString())
                if (messages.lastOrNull()?.role == "user") {
                    val previous = messages.removeAt(messages.lastIndex)
                    messages.add(previous.copy(content = previous.content + block))
                } else messages.add(AnthropicMessage("user", listOf(block)))
                continue
            }
            val blocks = when (val c = am.content) {
                is String -> listOf(AnthropicContentBlock(type = "text", text = c))
                is List<*> -> c.filterIsInstance<ContentPart>().mapNotNull { part ->
                    when (part.type) {
                        "text" -> AnthropicContentBlock(type = "text", text = part.text)
                        "image_url" -> {
                            val url = part.imageUrl?.url ?: return@mapNotNull null
                            val m = Regex("^data:([^;]+);base64,(.+)$", RegexOption.DOT_MATCHES_ALL).find(url)
                            val mediaType = m?.groupValues?.get(1) ?: "image/jpeg"
                            val data = m?.groupValues?.get(2) ?: url
                            AnthropicContentBlock(
                                type = "image",
                                source = AnthropicImageSource(mediaType = mediaType, data = data)
                            )
                        }
                        else -> null
                    }
                }
                null -> emptyList()
                else -> listOf(AnthropicContentBlock(type = "text", text = c.toString()))
            }
            val toolBlocks = am.toolCalls.orEmpty().map { call ->
                AnthropicContentBlock(type = "tool_use", id = call.id, name = call.function?.name,
                    input = com.google.gson.JsonParser.parseString(call.function?.arguments ?: "{}").asJsonObject)
            }
            val allBlocks = blocks.filterNot { it.type == "text" && it.text.isNullOrBlank() } + toolBlocks
            if (allBlocks.isNotEmpty()) messages.add(AnthropicMessage(role = am.role, content = allBlocks))
        }
        return systemParts.joinToString("\n\n").takeIf { it.isNotBlank() } to messages
    }

    private suspend fun anthropicNonStreamResponse(
        api: AnthropicApi,
        apiKey: String,
        request: AnthropicRequest
    ): ToolReply? {
        val response = api.getMessages(
            xApiKey = apiKey,
            anthropicVersion = Providers.ANTHROPIC_VERSION,
            request = request
        )
        val raw = response.text
        val calls = response.content.orEmpty().filter { it.type == "tool_use" }.map {
            StreamToolCall(id = it.id, type = "function", function = StreamToolFunction(it.name, it.input?.toString() ?: "{}"))
        }
        val reasoning = response.thinking
        messages.add(
            Message(
                role = "assistant",
                content = visibleText(raw, streaming = false),
                reasoning = reasoning,
                isStreaming = false
            )
        )
        updateCurrentConversation()
        return ToolReply(raw, messages.lastIndex, reasoning, calls)
    }

    private suspend fun anthropicStreamResponse(
        api: AnthropicApi,
        apiKey: String,
        request: AnthropicRequest
    ): ToolReply? {
        val body = api.getMessagesStream(
            xApiKey = apiKey,
            anthropicVersion = Providers.ANTHROPIC_VERSION,
            request = request
        )
        activeStream = body
        val streamingMessage = Message(role = "assistant", content = "", isStreaming = true)
        messages.add(streamingMessage)
        val msgIndex = messages.lastIndex
        loadingStatus = LoadingStatus.STREAMING

        val contentBuilder = StringBuilder()
        val thinkingBuilder = StringBuilder()
        val toolCalls = NativeTools.Accumulator()
        var lastUiUpdateTime = 0L

        withContext(Dispatchers.IO) {
            body.use { responseBody ->
                val reader = BufferedReader(InputStreamReader(responseBody.byteStream(), Charsets.UTF_8))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    ensureActive()
                    val trimmed = line!!.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith(":")) continue
                    if (!trimmed.startsWith("data:")) continue
                    val data = trimmed.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    try {
                        val event = gson.fromJson(data, AnthropicStreamEvent::class.java) ?: continue
                        when (event.type) {
                            "error" -> throw java.io.IOException(event.error?.message ?: "Provider stream failed")
                            "content_block_start" -> event.contentBlock?.takeIf { it.type == "tool_use" }?.let {
                                toolCalls.add(StreamToolCall(index = event.index, id = it.id, function = StreamToolFunction(it.name)))
                            }
                            "content_block_delta" -> {
                                val delta = event.delta
                                when (delta?.type) {
                                    "input_json_delta" -> toolCalls.add(StreamToolCall(index = event.index,
                                        function = StreamToolFunction(arguments = delta.partialJson)))
                                    "text_delta" -> delta.text?.let { contentBuilder.append(it) }
                                    "thinking_delta" -> delta.thinking?.let { thinkingBuilder.append(it) }
                                    else -> {}
                                }
                            }
                        }
                        val now = System.currentTimeMillis()
                        if (now - lastUiUpdateTime >= 35) {
                            lastUiUpdateTime = now
                            val currentContent = contentBuilder.toString()
                            val currentReasoning = thinkingBuilder.toString().takeIf { it.isNotBlank() }
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
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: com.google.gson.JsonParseException) {
                        // Skip malformed chunks.
                    }
                }
            }
        }

        val raw = contentBuilder.toString()
        val reasoning = thinkingBuilder.toString().takeIf { it.isNotBlank() }
        withContext(Dispatchers.Main) {
            if (msgIndex in messages.indices) {
                messages[msgIndex] = messages[msgIndex].copy(
                    content = visibleText(raw, streaming = false),
                    reasoning = reasoning,
                    isStreaming = false
                )
            }
        }
        return ToolReply(raw, msgIndex, reasoning, toolCalls.complete())
    }

    private fun getAnthropicApi(): AnthropicApi = Providers.anthropicApi()

    /**
     * Runs [block], and when the provider answers 429 waits and tries again instead
     * of handing the user an error: a rate limit is usually a busy key or a
     * throttled free tier, and it clears on its own within seconds. The wait doubles
     * each attempt, a provider-supplied Retry-After wins, and the countdown is on
     * screen while it runs. Stop still cancels the retry.
     */
    private suspend fun <T> retryingOnRateLimit(block: suspend () -> T): T {
        var attempt = 0

        while (true) {
            try {
                return block()
            } catch (e: HttpException) {
                if (e.code() != 429 || attempt >= MAX_RATE_LIMIT_RETRIES) throw e
                attempt++

                val waitMs = retryAfterMs(e) ?: (RATE_LIMIT_BASE_DELAY_MS shl (attempt - 1))
                var secondsLeft = ((waitMs + 999) / 1000).toInt().coerceAtLeast(1)

                withContext(Dispatchers.Main) {
                    rateLimitRetryIn = secondsLeft
                    loadingStatus = LoadingStatus.RATE_LIMITED
                }

                while (secondsLeft > 0) {
                    delay(1_000)
                    secondsLeft--
                    withContext(Dispatchers.Main) { rateLimitRetryIn = secondsLeft }
                }

                withContext(Dispatchers.Main) {
                    rateLimitRetryIn = 0
                    loadingStatus = LoadingStatus.THINKING
                }
            }
        }
    }

    /** A 429's Retry-After in milliseconds, whether it is seconds or an HTTP date. */
    private fun retryAfterMs(e: HttpException): Long? {
        val raw = try {
            e.response()?.headers()?.get("Retry-After")?.trim()
        } catch (_: Exception) {
            null
        } ?: return null

        raw.toLongOrNull()?.let { seconds ->
            return (seconds * 1_000L).coerceIn(0L, RATE_LIMIT_MAX_DELAY_MS)
        }

        return try {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
                .parse(raw)
                ?.let { (it.time - System.currentTimeMillis()).coerceIn(0L, RATE_LIMIT_MAX_DELAY_MS) }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun streamResponse(authHeader: String?, xApiKey: String?, anthropicHeader: String?, request: ChatRequest): ToolReply? {
        val responseBody = getApi().getCompletionStream(
            authorization = authHeader,
            xApiKey = xApiKey,
            anthropicVersion = anthropicHeader,
            request = request
        )

        activeStream = responseBody
        // Add placeholder streaming message
        val streamingMessage = Message(role = "assistant", content = "", isStreaming = true)
        messages.add(streamingMessage)
        val msgIndex = messages.lastIndex
        loadingStatus = LoadingStatus.STREAMING

        val contentBuilder = StringBuilder()
        val reasoningBuilder = StringBuilder()
        var lastUiUpdateTime = 0L
        val toolCalls = NativeTools.Accumulator()

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
                            throw java.io.IOException(chunk.error.message ?: "Provider stream failed")
                        }

                        val choice = chunk.choices?.firstOrNull() ?: continue
                        val delta = choice.delta ?: continue

                        // Multiple tool calls may interleave their argument fragments.
                        delta.toolCalls.orEmpty().forEach { toolCalls.add(it) }

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
                        if (choice.finishReason != null) break
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: com.google.gson.JsonParseException) {
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
        return ToolReply(raw, msgIndex, reasoning, toolCalls.complete())
    }

    private suspend fun nonStreamResponse(authHeader: String?, xApiKey: String?, anthropicHeader: String?, request: ChatRequest): ToolReply? {
        val nonStreamRequest = request.copy(stream = null)
        val response = getApi().getCompletion(
            authorization = authHeader,
            xApiKey = xApiKey,
            anthropicVersion = anthropicHeader,
            request = nonStreamRequest
        )

        val assistantMsg = response.choices.firstOrNull()?.message ?: return null

        val calls = assistantMsg.toolCalls.orEmpty()

        val reasoning = assistantMsg.thinking?.takeIf { it.isNotBlank() }
        messages.add(
            Message(
                role = "assistant",
                content = visibleText(assistantMsg.content.orEmpty(), streaming = false),
                reasoning = reasoning,
                isStreaming = false
            )
        )
        updateCurrentConversation()
        return ToolReply(assistantMsg.content.orEmpty(), messages.lastIndex, reasoning, calls)
    }

    /**
     * Applies a finished reply: saves any memory it asked for, runs the tool calls
     * it made, and adds tool status to the transcript. Returns the tool runs
     * so the caller can feed their results back to the model.
     */
    private suspend fun finalizeTurn(turn: ToolReply): List<AiTools.Run> {
        val (withoutMemory, newMemory) = extractMemory(turn.raw)
        if (newMemory != null) settingsManager.agentMemory = newMemory

        val calls = if (settingsManager.toolsEnabled && allowToolCalls) turn.calls else emptyList()
        require(calls.size <= 32 && calls.all { !it.id.isNullOrBlank() } && calls.map { it.id }.distinct().size == calls.size) {
            "Invalid tool call IDs or too many tool calls"
        }
        val runs = calls.map { call ->
            try { withContext(Dispatchers.IO) { AiTools.execute(NativeTools.decode(call), toolScope) } }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { AiTools.Run(call.function?.name.orEmpty(), false, "", e.message ?: "Invalid arguments") }
        }
        if (calls.isNotEmpty()) toolHistory.addAll(NativeTools.exchange(turn.raw, turn.reasoning, calls, runs))

        withContext(Dispatchers.Main) {
            if (turn.index in messages.indices) {
                messages[turn.index] = messages[turn.index].copy(
                    content = withoutMemory.trim(),
                    reasoning = turn.reasoning,
                    isStreaming = false,
                    tools = runs.mapIndexed { index, run ->
                        // `label` is the path for the file tools, which is exactly
                        // what the transcript needs to link a run back to the file
                        // it touched. Device paths are absolute and live outside
                        // the file history screen, so they are shown without a link.
                        val filePath = if (run.name in AiTools.fileTools &&
                            !run.label.startsWith("/")
                        ) {
                            run.label
                        } else {
                            null
                        }
                        ToolRun(run.name, run.label, run.ok, filePath,
                            callId = calls[index].id, arguments = calls[index].function?.arguments ?: "{}", result = run.result)
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
        val display = extractMemory(text).first
        return if (streaming) display else display.trim()
    }

    fun stopGeneration() {
        currentJob?.cancel()
        try { activeStream?.close() } catch (_: Exception) { }
        activeStream = null
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

    private fun generateChatTitle(apiKey: String) {
        // The title request is fire-and-forget, so remember which conversation it
        // belongs to: the user can open another chat while it is still in flight,
        // and the reply used to be applied to whatever was current on arrival.
        val conv = currentConversation ?: return
        val firstMessage = messages.find { it.role == "user" }?.content ?: return

        val provider = requestProvider ?: settingsManager.selectedProvider
        val model = requestModel ?: settingsManager.selectedModel
        viewModelScope.launch {
            try {
                val summaryPrompt = "Generate a short title (max 4 words) for this chat. Return only the title."

                val request = ChatRequest(
                    model = model,
                    messages = listOf(
                        ApiMessage(role = "system", content = summaryPrompt),
                        ApiMessage(role = "user", content = firstMessage)
                    )
                )
                val raw = if (Providers.isAnthropic(provider)) {
                    getAnthropicApi().getMessages(apiKey, Providers.ANTHROPIC_VERSION,
                        AnthropicRequest(model = model, system = summaryPrompt,
                            messages = listOf(AnthropicMessage("user", listOf(AnthropicContentBlock(text = firstMessage)))))).text
                } else {
                    Providers.api(provider).getCompletion(authorization = "Bearer $apiKey", request = request)
                        .choices.firstOrNull()?.message?.content ?: return@launch
                }
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
                val full = conversationStore.loadConversation(conv.id) ?: return@launch
                conversationStore.saveConversation(full.copy(title = title))
            } catch (e: Exception) {}
        }
    }

    private fun saveImageLocally(context: Context, uri: Uri): String? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                ?: return null
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return null
            val file = File(context.filesDir, "img_${UUID.randomUUID()}.jpg")
            try {
                file.outputStream().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
                }
            } finally {
                bitmap.recycle()
            }
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
        stopGeneration()
        messages.forEach { msg ->
            msg.localImageUrl?.let { path -> File(path).takeIf { it.exists() }?.delete() }
        }
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

    /**
     * Shows a failure in the transcript: it replaces the reply still arriving, or
     * starts one when the request failed before anything was shown.
     */
    private fun showReplyError(errorMsg: String) {
        val streamIdx = messages.indexOfLast { it.isStreaming }
        if (streamIdx != -1) {
            messages[streamIdx] = Message(role = "assistant", content = errorMsg, isStreaming = false, isError = true)
        } else {
            messages.add(Message(role = "assistant", content = errorMsg, isError = true))
        }
        updateCurrentConversation()
    }
}

