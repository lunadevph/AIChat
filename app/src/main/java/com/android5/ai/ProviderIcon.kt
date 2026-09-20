package com.android5.ai

import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource

fun providerIconRes(provider: String): Int = when (provider) {
    "OpenAI" -> R.drawable.ic_provider_openai
    "Google" -> R.drawable.ic_provider_gemini
    "Groq" -> R.drawable.ic_provider_groq
    "DeepSeek" -> R.drawable.ic_provider_deepseek
    "Claude" -> R.drawable.ic_provider_claude
    "Mistral" -> R.drawable.ic_provider_mistral
    "Grok" -> R.drawable.ic_provider_grok
    else -> R.drawable.ic_provider_openrouter
}

@Composable
fun ProviderIcon(provider: String, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(providerIconRes(provider)),
        contentDescription = provider,
        modifier = modifier.clip(CircleShape)
    )
}
