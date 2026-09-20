package com.android5.ai

import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object Providers {
    data class Info(
        val name: String,
        val tagline: String,
        val keyPrefix: String,
        val keyUrl: String
    )

    val ALL: List<Info> = listOf(
        Info("OpenRouter", "500+ models, including free tiers", "sk-or-", "https://openrouter.ai/keys"),
        Info("OpenAI", "GPT-5.6, o-series reasoning & GPT-OSS", "sk-", "https://platform.openai.com/api-keys"),
        Info("Google", "Gemini 3.x Flash, Pro & Omni models", "AIza", "https://aistudio.google.com/app/apikey"),
        Info("Groq", "Ultra-fast Llama, Qwen & open models", "gsk_", "https://console.groq.com/keys"),
        Info("DeepSeek", "DeepSeek V4 Pro & Flash", "sk-", "https://platform.deepseek.com/api_keys"),
        Info("Claude", "Anthropic Claude Opus 4.1 & Sonnet 4", "sk-ant-", "https://console.anthropic.com/settings/keys"),
        Info("Mistral", "Mistral Large 3, Medium 3.5 & Codestral", "…", "https://console.mistral.ai/api-keys"),
        Info("Grok", "xAI Grok 4.6 & 4.x family", "xai-", "https://console.x.ai")
    )

    fun info(provider: String): Info = ALL.firstOrNull { it.name == provider }
        ?: ALL.first()

    fun names(): List<String> = ALL.map { it.name }

    const val ANTHROPIC_VERSION = "2023-06-01"

    private val validationClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    private val chatClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun baseUrl(provider: String): String = when (provider) {
        "OpenAI" -> "https://api.openai.com/v1/"
        "Google" -> "https://generativelanguage.googleapis.com/v1beta/openai/"
        "Groq" -> "https://api.groq.com/openai/v1/"
        "DeepSeek" -> "https://api.deepseek.com/v1/"
        "Claude" -> "https://api.anthropic.com/v1/"
        "Mistral" -> "https://api.mistral.ai/v1/"
        "Grok" -> "https://api.x.ai/v1/"
        else -> "https://openrouter.ai/api/v1/"
    }

    fun isAnthropic(provider: String): Boolean = provider == "Claude"

    fun api(provider: String, forValidation: Boolean = false): OpenRouterApi =
        Retrofit.Builder()
            .baseUrl(baseUrl(provider))
            .client(if (forValidation) validationClient else chatClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(OpenRouterApi::class.java)

    sealed class KeyValidation {
        object Valid : KeyValidation()
        object InvalidKey : KeyValidation()
        data class NetworkError(val detail: String?) : KeyValidation()
    }

    suspend fun validateKey(provider: String, key: String): KeyValidation {
        if (key.isBlank()) return KeyValidation.InvalidKey
        return try {
            when (provider) {
                "OpenRouter" -> {
                    val response = api(provider, forValidation = true).checkKey("Bearer $key")
                    if (response.isSuccessful) KeyValidation.Valid else KeyValidation.InvalidKey
                }
                "Claude" -> try {
                    val models = api(provider, forValidation = true).getModels(
                        authorization = null,
                        xApiKey = key,
                        anthropicVersion = ANTHROPIC_VERSION
                    )
                    if (!models.data.isNullOrEmpty()) KeyValidation.Valid else KeyValidation.InvalidKey
                } catch (e: HttpException) {
                    handleHttpError(e)
                }
                else -> try {
                    val models = api(provider, forValidation = true).getModels("Bearer $key")
                    if (!models.data.isNullOrEmpty()) KeyValidation.Valid else KeyValidation.InvalidKey
                } catch (e: HttpException) {
                    handleHttpError(e)
                }
            }
        } catch (e: Exception) {
            KeyValidation.NetworkError(e.localizedMessage)
        }
    }

    fun defaultModel(provider: String): String = when (provider) {
        "OpenAI" -> "gpt-5.6-sol"
        "Google" -> "gemini-3.7-flash"
        "Groq" -> "llama-4-scout-17b-16e-instruct"
        "DeepSeek" -> "deepseek-v4-pro"
        "Claude" -> "claude-opus-4-1-20250805"
        "Mistral" -> "mistral-large-latest"
        "Grok" -> "grok-4.6"
        else -> "openrouter/free"
    }

    fun defaultModels(provider: String): List<ModelInfo> = when (provider) {
        "OpenAI" -> listOf(
            // GPT-5.6 family (Aug 2026)
            ModelInfo(id = "gpt-5.6-sol", name = "GPT-5.6 Sol", description = "Premier flagship for complex reasoning & coding", architecture = Architecture(modality = "text+image->text")),
            ModelInfo(id = "gpt-5.6-terra", name = "GPT-5.6 Terra", description = "High intelligence balanced with cost-efficiency", architecture = Architecture(modality = "text+image->text")),
            ModelInfo(id = "gpt-5.6-luna", name = "GPT-5.6 Luna", description = "Optimized for cost-sensitive high-volume workloads", architecture = Architecture(modality = "text+image->text")),
            // o-series reasoning
            ModelInfo(id = "o3", name = "o3", description = "Deep multi-step reasoning model with vision", architecture = Architecture(modality = "text+image->text")),
            ModelInfo(id = "o3-pro", name = "o3-pro", description = "Most powerful reasoning model", architecture = Architecture(modality = "text+image->text")),
            ModelInfo(id = "o4-mini", name = "o4-mini", description = "Fast, cost-effective reasoning with tool use", architecture = Architecture(modality = "text+image->text")),
            // Open-weight models
            ModelInfo(id = "gpt-oss-120b", name = "GPT-OSS 120B", description = "Open-weight reasoning model, self-hostable", architecture = Architecture(modality = "text->text")),
            ModelInfo(id = "gpt-oss-20b", name = "GPT-OSS 20B", description = "Lightweight open-weight model for edge/local", architecture = Architecture(modality = "text->text")),
            // Previous gen (still available)
            ModelInfo(id = "gpt-4.1", name = "GPT-4.1", description = "Previous flagship with 1M context", architecture = Architecture(modality = "text+image->text")),
            ModelInfo(id = "gpt-4.1-mini", name = "GPT-4.1 Mini", description = "Fast, affordable balanced model", architecture = Architecture(modality = "text+image->text")),
            ModelInfo(id = "gpt-4.1-nano", name = "GPT-4.1 Nano", description = "Ultra-fast cheapest model for simple tasks", architecture = Architecture(modality = "text+image->text")),
            ModelInfo(id = "gpt-4o", name = "GPT-4o", description = "High-intelligence vision model", architecture = Architecture(modality = "text+image->text")),
            ModelInfo(id = "gpt-4o-mini", name = "GPT-4o Mini", description = "Fast, affordable lightweight vision model", architecture = Architecture(modality = "text+image->text"))
        )
        "Google" -> listOf(
            // Gemini 3.x (2026)
            ModelInfo(id = "gemini-3.7-flash", name = "Gemini 3.7 Flash", description = "Most intelligent workhorse for coding & agents", architecture = Architecture(modality = "text+image->text")),
            ModelInfo(id = "gemini-3.5-flash", name = "Gemini 3.5 Flash", description = "High-performance agentic & coding at scale", architecture = Architecture(modality = "text+image->text")),
            ModelInfo(id = "gemini-3.5-flash-lite", name = "Gemini 3.5 Flash-Lite", description = "Cost-efficient high-throughput model", architecture = Architecture(modality = "text+image->text")),
            ModelInfo(id = "gemini-3.1-pro", name = "Gemini 3.1 Pro (Preview)", description = "Frontier reasoning & advanced coding", architecture = Architecture(modality = "text+image->text")),
            ModelInfo(id = "gemini-3.1-flash-lite", name = "Gemini 3.1 Flash-Lite", description = "Budget-friendly classification & extraction", architecture = Architecture(modality = "text+image->text")),
            // Previous gen (retiring Oct 2026)
            ModelInfo(id = "gemini-2.5-pro", name = "Gemini 2.5 Pro", description = "Previous-gen complex task model (retiring Oct 2026)", architecture = Architecture(modality = "text+image->text")),
            ModelInfo(id = "gemini-2.5-flash", name = "Gemini 2.5 Flash", description = "Previous-gen fast reasoning (retiring Oct 2026)", architecture = Architecture(modality = "text+image->text"))
        )
        "Groq" -> listOf(
            // Llama family
            ModelInfo(id = "llama-4-scout-17b-16e-instruct", name = "Llama 4 Scout 17B", description = "Meta MoE model with 16 experts, ultra-fast on Groq", architecture = Architecture(modality = "text+image->text")),
            ModelInfo(id = "llama-3.3-70b-versatile", name = "Llama 3.3 70B Versatile", description = "Versatile open language model", architecture = Architecture(modality = "text->text")),
            ModelInfo(id = "llama-3.2-90b-vision-preview", name = "Llama 3.2 90B Vision", description = "High-accuracy multimodal reasoning", architecture = Architecture(modality = "text+image->text")),
            ModelInfo(id = "llama-3.2-11b-vision-preview", name = "Llama 3.2 11B Vision", description = "Fast vision and text model", architecture = Architecture(modality = "text+image->text")),
            // Qwen family
            ModelInfo(id = "qwen-3.8-32b", name = "Qwen 3.8 32B", description = "Alibaba Qwen latest reasoning model", architecture = Architecture(modality = "text->text")),
            ModelInfo(id = "qwen-3.6-32b", name = "Qwen 3.6 32B", description = "Alibaba Qwen high-performance model", architecture = Architecture(modality = "text->text")),
            // Other
            ModelInfo(id = "openai/gpt-oss-safeguard-20b", name = "GPT-OSS 20B (Safety)", description = "OpenAI open-weight model with safety tuning", architecture = Architecture(modality = "text->text")),
            ModelInfo(id = "deepseek-r1-distill-llama-70b", name = "DeepSeek R1 Distill 70B", description = "Ultra-fast reasoning on Groq", architecture = Architecture(modality = "text->text"))
        )
        "DeepSeek" -> listOf(
            // V4 generation (Aug 2026)
            ModelInfo(id = "deepseek-v4-pro", name = "DeepSeek V4 Pro", description = "1.6T param flagship for complex agentic tasks", architecture = Architecture(modality = "text->text")),
            ModelInfo(id = "deepseek-v4-flash", name = "DeepSeek V4 Flash", description = "Cost-efficient 284B high-throughput model", architecture = Architecture(modality = "text->text")),
            ModelInfo(id = "deepseek-v4-flash-vision-exp", name = "DeepSeek V4 Flash Vision", description = "Experimental multimodal V4 with vision", architecture = Architecture(modality = "text+image->text")),
            // Legacy (still accessible)
            ModelInfo(id = "deepseek-chat", name = "DeepSeek V3 (Chat)", description = "671B MoE language model", architecture = Architecture(modality = "text->text")),
            ModelInfo(id = "deepseek-reasoner", name = "DeepSeek R1 (Reasoner)", description = "Reinforcement-learning reasoning model", architecture = Architecture(modality = "text->text"))
        )
        "Claude" -> listOf(
            // Claude 4.x (2025-2026)
            ModelInfo(id = "claude-opus-4-1-20250805", name = "Claude Opus 4.1", description = "Most powerful Claude — coding, search & reasoning", architecture = Architecture(modality = "text+image->text")),
            ModelInfo(id = "claude-opus-4-20250514", name = "Claude Opus 4", description = "Deep reasoning & complex analysis", architecture = Architecture(modality = "text+image->text")),
            ModelInfo(id = "claude-sonnet-4-20250514", name = "Claude Sonnet 4", description = "Balanced intelligence & speed, 1M context", architecture = Architecture(modality = "text+image->text")),
            // Previous gen
            ModelInfo(id = "claude-3-7-sonnet-20250219", name = "Claude 3.7 Sonnet", description = "Hybrid reasoning & vision model", architecture = Architecture(modality = "text+image->text")),
            ModelInfo(id = "claude-3-5-sonnet-20241022", name = "Claude 3.5 Sonnet v2", description = "Previous-gen high-intelligence model", architecture = Architecture(modality = "text+image->text")),
            ModelInfo(id = "claude-3-5-haiku-20241022", name = "Claude 3.5 Haiku", description = "Fast Claude model with high accuracy", architecture = Architecture(modality = "text+image->text"))
        )
        "Mistral" -> listOf(
            // Current generation (2026)
            ModelInfo(id = "mistral-large-latest", name = "Mistral Large 3", description = "Flagship multimodal & multilingual model", architecture = Architecture(modality = "text+image->text")),
            ModelInfo(id = "mistral-medium-latest", name = "Mistral Medium 3.5", description = "Frontier agentic workflows & coding", architecture = Architecture(modality = "text+image->text")),
            ModelInfo(id = "mistral-small-latest", name = "Mistral Small 4", description = "Efficient hybrid reasoning & coding", architecture = Architecture(modality = "text->text")),
            ModelInfo(id = "codestral-latest", name = "Codestral", description = "Specialized coding & code generation", architecture = Architecture(modality = "text->text")),
            ModelInfo(id = "devstral-latest", name = "Devstral 2", description = "Agentic software engineering model", architecture = Architecture(modality = "text->text")),
            ModelInfo(id = "ministral-14b-latest", name = "Ministral 14B", description = "Compact edge model, strong reasoning", architecture = Architecture(modality = "text->text")),
            ModelInfo(id = "ministral-3b-latest", name = "Ministral 3B", description = "Ultra-compact edge & budget model", architecture = Architecture(modality = "text->text")),
            ModelInfo(id = "pixtral-large-latest", name = "Pixtral Large", description = "124B multimodal vision understanding", architecture = Architecture(modality = "text+image->text")),
            ModelInfo(id = "mistral-embed", name = "Mistral Embed", description = "Text embedding for search & RAG", architecture = Architecture(modality = "text->text"))
        )
        "Grok" -> listOf(
            // Grok 4.x family (2026)
            ModelInfo(id = "grok-4.6", name = "Grok 4.6", description = "xAI flagship — coding, agents & reasoning", architecture = Architecture(modality = "text+image->text")),
            ModelInfo(id = "grok-4.5", name = "Grok 4.5", description = "Opus-class model for coding & agentic work", architecture = Architecture(modality = "text+image->text")),
            ModelInfo(id = "grok-4.3", name = "Grok 4.3", description = "1M token context high-capability model", architecture = Architecture(modality = "text+image->text")),
            ModelInfo(id = "grok-4.1-fast", name = "Grok 4.1 Fast", description = "Low-latency model with 2M context window", architecture = Architecture(modality = "text+image->text")),
            ModelInfo(id = "grok-build-0.1", name = "Grok Build", description = "Cost-efficient coding & dev model", architecture = Architecture(modality = "text->text")),
            // Previous gen
            ModelInfo(id = "grok-3", name = "Grok 3", description = "Previous-gen capable reasoning model", architecture = Architecture(modality = "text+image->text")),
            ModelInfo(id = "grok-3-mini", name = "Grok 3 Mini", description = "Lightweight fast thinking model", architecture = Architecture(modality = "text+image->text"))
        )
        else -> listOf(
            // Free models (OpenRouter)
            ModelInfo(id = "openrouter/free", name = "Auto (Free Router)", description = "Auto-selects best free model for your request", architecture = Architecture(modality = "text+image->text")),
            ModelInfo(id = "google/gemini-3.5-flash:free", name = "Gemini 3.5 Flash (Free)", description = "Free Google agentic workhorse model", architecture = Architecture(modality = "text+image->text")),
            ModelInfo(id = "meta-llama/llama-4-scout:free", name = "Llama 4 Scout (Free)", description = "Free Meta MoE model with vision", architecture = Architecture(modality = "text+image->text")),
            ModelInfo(id = "nvidia/nemotron-3-ultra:free", name = "Nemotron 3 Ultra (Free)", description = "Free NVIDIA frontier model", architecture = Architecture(modality = "text->text")),
            ModelInfo(id = "qwen/qwen-3.8-32b:free", name = "Qwen 3.8 32B (Free)", description = "Free Alibaba latest reasoning model", architecture = Architecture(modality = "text->text")),
            ModelInfo(id = "deepseek/deepseek-r1:free", name = "DeepSeek R1 (Free)", description = "Free open reasoning model", architecture = Architecture(modality = "text->text")),
            ModelInfo(id = "openai/gpt-oss-120b:free", name = "GPT-OSS 120B (Free)", description = "Free OpenAI open-weight reasoning model", architecture = Architecture(modality = "text->text")),
            ModelInfo(id = "mistralai/mistral-small-4:free", name = "Mistral Small 4 (Free)", description = "Free efficient Mistral hybrid model", architecture = Architecture(modality = "text->text")),
            // Paid popular models
            ModelInfo(id = "openai/gpt-5.6-sol", name = "GPT-5.6 Sol", description = "OpenAI's premier flagship via OpenRouter", architecture = Architecture(modality = "text+image->text")),
            ModelInfo(id = "anthropic/claude-opus-4.1", name = "Claude Opus 4.1", description = "Most powerful Claude via OpenRouter", architecture = Architecture(modality = "text+image->text")),
            ModelInfo(id = "x-ai/grok-4.6", name = "Grok 4.6", description = "xAI flagship via OpenRouter", architecture = Architecture(modality = "text+image->text")),
            ModelInfo(id = "google/gemini-3.7-flash", name = "Gemini 3.7 Flash", description = "Google workhorse via OpenRouter", architecture = Architecture(modality = "text+image->text"))
        )
    }

    private fun handleHttpError(e: HttpException): Providers.KeyValidation = when (e.code()) {
        401, 403 -> KeyValidation.InvalidKey
        else -> KeyValidation.NetworkError("HTTP ${e.code()}")
    }
}
