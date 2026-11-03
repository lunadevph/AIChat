package com.android5.ai

import androidx.compose.foundation.Image
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource

fun providerIconRes(provider: String): Int = when (provider) {
    "OpenAI" -> R.drawable.ic_provider_openai
    "Google" -> R.drawable.ic_provider_gemini
    "Groq" -> R.drawable.ic_provider_groq
    "DeepSeek" -> R.drawable.ic_provider_deepseek
    "Claude" -> R.drawable.ic_provider_claude
    "Mistral" -> R.drawable.ic_provider_mistral
    "Grok" -> R.drawable.ic_provider_grok
    "NVIDIA" -> R.drawable.ic_provider_nvidia
    "OpenRouter" -> R.drawable.ic_provider_openrouter
    else -> R.drawable.ic_provider_custom
}

@Composable
fun ProviderIcon(provider: String, modifier: Modifier = Modifier, contentDescription: String? = provider) {
    val monochrome = provider !in setOf("Google", "DeepSeek", "Claude", "Mistral", "NVIDIA")
    Image(
        painter = painterResource(providerIconRes(provider)),
        contentDescription = contentDescription,
        colorFilter = if (monochrome) ColorFilter.tint(MaterialTheme.colorScheme.onSurface) else null,
        modifier = modifier
    )
}
