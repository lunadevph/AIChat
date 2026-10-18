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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

enum class LoadingStatus {
    IDLE, THINKING, SEARCHING, CREATING_IMAGE, STREAMING, RATE_LIMITED
}

/** How many tool rounds a single reply may trigger before the app stops looping. */
private const val MAX_TOOL_STEPS = 3

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

    private fun getApi(): OpenRouterApi {
        return Providers.api(settingsManager.selectedProvider)
    }

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
                var correction: String? = null

                for (step in 0 until maxSteps) {
                    val turn = try {
                        requestTurn(apiKey, searchGrounding, toolResults, correction)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Some providers reject a reply that used their own native
                        // tool syntax (Groq's "tool_use_failed") instead of the plain
                        // tags this app asked for; others accept it and leave the
                        // reply blank. Either way: say so and ask again, once.
                        if (correction != null || !isNativeToolCallFailure(e)) throw e
                        correction = context.getString(R.string.tool_syntax_correction)
                        loadingStatus = LoadingStatus.THINKING
                        requestTurn(apiKey, searchGrounding, toolResults, correction)
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
            } catch (e: NativeToolCallException) {
                // The model answered with its own tool syntax and nothing else, and
                // the provider took it: there is no text to show, so explain instead.
                android.util.Log.w("AIChat", "The reply was a native tool call with no text")
                showReplyError(context.getString(R.string.err_native_tool_call))
            } catch (e: HttpException) {
                val body = lastErrorBody ?: try { e.response()?.errorBody()?.string() } catch (_: Exception) { null }
                lastErrorBody = null
                android.util.Log.e("AIChat", "HTTP ${e.code()}: $body")
                val errorMsg = if (e.code() == 400 && saysNativeToolCall(body)) {
                    context.getString(R.string.err_native_tool_call)
                } else when (e.code()) {
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
                android.util.Log.e("AIChat", "Request failed", e)
                showReplyError(context.getString(R.string.err_network, e.localizedMessage ?: "Unknown error"))
            } finally {
                loadingStatus = LoadingStatus.IDLE
                rateLimitRetryIn = 0
                currentJob = null
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

    /** One request round — streaming or not — with the current model's parameters. */
    private suspend fun requestTurn(
        apiKey: String,
        searchGrounding: String?,
        toolResults: String?,
        correction: String?
    ): Turn? {
        lastErrorBody = null
        val isReasoning = isReasoningModel(settingsManager.selectedModel)
        val isGoogle = settingsManager.selectedProvider == "Google"
        val webSearchOn = settingsManager.webSearchEnabled

        val request = ChatRequest(
            model = settingsManager.selectedModel,
            messages = buildApiMessages(searchGrounding, toolResults, correction),
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

        return retryingOnRateLimit {
            if (settingsManager.streamingEnabled) {
                streamResponse(authHeader, xApiKeyHeader, anthropicHeader, request)
            } else {
                nonStreamResponse(authHeader, xApiKeyHeader, anthropicHeader, request)
            }
        }
    }

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

    /**
     * True when a failed request looks like the model used its provider's own
     * function-calling format instead of this app's plain tags: a 400 rejection
     * (Groq's "tool_use_failed") or a reply that was nothing but a tool call.
     */
    private fun isNativeToolCallFailure(e: Exception): Boolean = when (e) {
        is NativeToolCallException -> true
        is HttpException -> isNativeToolCallRejection(e)
        else -> false
    }

    /**
     * True when a provider answered 400 because the model emitted its *own* native
     * tool call while the request declared no tools. Groq watches gpt-oss and Llama
     * replies for their tool-call syntax and rejects the turn with "tool_use_failed"
     * — which a system prompt full of tool tags can provoke, even though this app
     * never asks for a native call.
     */
    private fun isNativeToolCallRejection(e: HttpException): Boolean {
        if (e.code() != 400) return false

        val body = try {
            e.response()?.errorBody()?.string()
        } catch (_: Exception) {
            null
        }
        lastErrorBody = body

        if (!saysNativeToolCall(body)) return false
        android.util.Log.w("AIChat", "Provider rejected a native tool call: $body")
        return true
    }

    /** Recognises the wordings providers use for a rejected native tool call. */
    private fun saysNativeToolCall(body: String?): Boolean {
        if (body.isNullOrBlank()) return false
        val lower = body.lowercase()
        return lower.contains("tool_use_failed") ||
            lower.contains("called a tool") ||
            lower.contains("tool_choice")
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
        var nativeToolCallSeen = false

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

                        val choice = chunk.choices?.firstOrNull() ?: continue
                        if (choice.finishReason == "tool_calls") nativeToolCallSeen = true
                        val delta = choice.delta ?: continue

                        // The model used the provider's own function-calling format
                        // instead of this app's tags; there will be no text.
                        if (delta.toolCalls?.isNotEmpty() == true) nativeToolCallSeen = true

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
                    } catch (_: Exception) {
                        // Skip malformed chunks
                    }
                }
            }
        }

        // A provider that runs its own tool parser can emit a native tool call even
        // though this request declared no tools — and then the reply has no text at
        // all. Drop the empty placeholder and let the caller ask the model again.
        if (contentBuilder.isBlank() && nativeToolCallSeen) {
            withContext(Dispatchers.Main) {
                if (msgIndex in messages.indices) messages.removeAt(msgIndex)
            }
            throw NativeToolCallException()
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

        // Some providers accept a native tool call and return no text alongside it;
        // a blank bubble would be the whole reply, so let the caller ask again.
        if (assistantMsg.content.isNullOrBlank() && assistantMsg.toolCalls?.isNotEmpty() == true) {
            throw NativeToolCallException()
        }

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

    /**
     * Shows a failure in the transcript: it replaces the reply still arriving, or
     * starts one when the request failed before anything was shown.
     */
    private fun showReplyError(errorMsg: String) {
        val streamIdx = messages.indexOfLast { it.isStreaming }
        if (streamIdx != -1) {
            messages[streamIdx] = Message(role = "assistant", content = errorMsg, isStreaming = false)
        } else {
            messages.add(Message(role = "assistant", content = errorMsg))
        }
        updateCurrentConversation()
    }
}

/**
 * Thrown when a reply consisted only of a tool call in the provider's own format.
 * This app asks for tools in plain text tags, so such a reply has no text to show
 * and is answered by asking the model again with that spelling out.
 */
private class NativeToolCallException : Exception("The reply was a native tool call")

