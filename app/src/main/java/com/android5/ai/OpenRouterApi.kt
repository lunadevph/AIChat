package com.android5.ai

import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.GET
import retrofit2.http.Streaming

interface OpenRouterApi {
    @POST("chat/completions")
    suspend fun getCompletion(
        @Header("Authorization") authorization: String? = null,
        @Header("x-api-key") xApiKey: String? = null,
        @Header("HTTP-Referer") referer: String = "http://www.google.com",
        @Header("X-Title") title: String = "AI Chat API BACKEND",
        @Header("anthropic-version") anthropicVersion: String? = null,
        @Body request: ChatRequest
    ): ChatResponse

    @Streaming
    @POST("chat/completions")
    suspend fun getCompletionStream(
        @Header("Authorization") authorization: String? = null,
        @Header("x-api-key") xApiKey: String? = null,
        @Header("HTTP-Referer") referer: String = "http://www.google.com",
        @Header("X-Title") title: String = "AI Chat API BACKEND",
        @Header("anthropic-version") anthropicVersion: String? = null,
        @Body request: ChatRequest
    ): ResponseBody

    @GET("models")
    suspend fun getModels(
        @Header("Authorization") authorization: String? = null,
        @Header("x-api-key") xApiKey: String? = null,
        @Header("anthropic-version") anthropicVersion: String? = null
    ): ModelResponse

    @GET("auth/key")
    suspend fun checkKey(
        @Header("Authorization") authorization: String
    ): retrofit2.Response<Unit>
}
