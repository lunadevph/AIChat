package com.android5.ai

import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Streaming
import retrofit2.http.Url

/**
 * Anthropic's Messages API — the one provider that is not OpenAI-shaped.
 *
 * Endpoint is `https://api.anthropic.com/v1/messages` (POST) for chat and
 * `/v1/models` for the catalog. Auth is `x-api-key` + `anthropic-version`,
 * which is why the headers are explicit rather than an Authorization bearer.
 */
interface AnthropicApi {
    @POST("messages")
    suspend fun getMessages(
        @Header("x-api-key") xApiKey: String,
        @Header("anthropic-version") anthropicVersion: String,
        @Body request: AnthropicRequest
    ): AnthropicResponse

    @Streaming
    @POST("messages")
    suspend fun getMessagesStream(
        @Header("x-api-key") xApiKey: String,
        @Header("anthropic-version") anthropicVersion: String,
        @Body request: AnthropicRequest
    ): ResponseBody

    @GET
    suspend fun getModels(
        @Url url: String,
        @Header("x-api-key") xApiKey: String,
        @Header("anthropic-version") anthropicVersion: String
    ): ModelResponse
}
