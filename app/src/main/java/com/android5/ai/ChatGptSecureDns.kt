package com.android5.ai

import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** Opt-in, credential-free DNS lookup. TLS still validates the original OpenAI hostname. */
internal object ChatGptSecureDns {
    private val client = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS).callTimeout(4, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    val hosts = setOf("auth.openai.com", "api.openai.com")

    fun lookup(host: String): List<InetAddress> {
        if (host !in hosts) throw UnknownHostException(host)
        val addresses = mutableListOf<InetAddress>()
        for (type in listOf(1, 28)) {
            val url = "https://1.1.1.1/dns-query".toHttpUrl().newBuilder()
                .addQueryParameter("name", host).addQueryParameter("type", type.toString()).build()
            runCatching {
                client.newCall(Request.Builder().url(url).header("Accept", "application/dns-json").build()).execute().use {
                    if (!it.isSuccessful) return@use
                    val body = it.body ?: return@use
                    val source = body.source()
                    source.request(65_537)
                    val bytes = source.buffer.readByteArray(minOf(source.buffer.size, 65_537))
                    if (bytes.size > 65_536) return@use
                    addresses += parse(host, type, bytes.toString(Charsets.UTF_8))
                }
            }
        }
        return addresses.distinct().ifEmpty { throw UnknownHostException(host) }
    }

    internal fun parse(host: String, type: Int, body: String): List<InetAddress> {
        require(host in hosts && type in setOf(1, 28))
        val json = JsonParser.parseString(body).asJsonObject
        if (json["Status"]?.asInt != 0 || json["TC"]?.asBoolean == true) return emptyList()
        val question = json.getAsJsonArray("Question")?.singleOrNull()?.asJsonObject ?: return emptyList()
        if (question["name"]?.asString?.trimEnd('.') != host || question["type"]?.asInt != type) return emptyList()
        return json.getAsJsonArray("Answer")?.mapNotNull { answer ->
            val record = answer.asJsonObject
            if (record["type"]?.asInt != type) return@mapNotNull null
            val literal = record["data"]?.asString.orEmpty()
            val valid = if (type == 1) literal.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))
                && literal.split('.').all { it.toInt() in 0..255 }
            else ':' in literal && literal.matches(Regex("[0-9A-Fa-f:]+"))
            if (!valid) return@mapNotNull null
            runCatching { InetAddress.getByName(literal) }.getOrNull()?.takeUnless {
                it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress || it.isSiteLocalAddress || it.isMulticastAddress
            }
        }.orEmpty()
    }
}
