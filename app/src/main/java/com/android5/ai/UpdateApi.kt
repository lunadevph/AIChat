package com.android5.ai

import retrofit2.http.GET
import retrofit2.http.Url

interface UpdateApi {
    @GET
    suspend fun getUpdateConfig(@Url url: String): AppUpdateConfig
}
