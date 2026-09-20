package com.android5.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class WebSearchResult(
    val title: String,
    val snippet: String,
    val url: String
)

data class WebImageResult(
    val title: String,
    val imageUrl: String,
    val sourceUrl: String,
    val thumbnail: String? = null
)

object WebSearchService {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0"

    suspend fun search(query: String, maxResults: Int = 5): List<WebSearchResult> {
        if (query.isBlank()) return emptyList()

        return withContext(Dispatchers.IO) {
            // Tier 1: DuckDuckGo HTML Search
            val ddgResults = searchDuckDuckGoHtml(query, maxResults)
            if (ddgResults.isNotEmpty()) {
                return@withContext ddgResults
            }

            // Tier 2: DuckDuckGo Instant Answer API
            val instantResults = searchDuckDuckGoInstant(query, maxResults)
            if (instantResults.isNotEmpty()) {
                return@withContext instantResults
            }

            // Tier 3: Wikipedia Search Fallback
            val wikiResults = searchWikipedia(query, maxResults)
            if (wikiResults.isNotEmpty()) {
                return@withContext wikiResults
            }

            emptyList()
        }
    }

    suspend fun searchImages(query: String, maxResults: Int = 4): List<WebImageResult> {
        if (query.isBlank()) return emptyList()

        return withContext(Dispatchers.IO) {
            // Tier 1: DuckDuckGo Images API
            val ddgImages = searchDuckDuckGoImages(query, maxResults)
            if (ddgImages.isNotEmpty()) {
                return@withContext ddgImages
            }

            // Tier 2: Wikipedia / Wikimedia PageImages API
            val wikiImages = searchWikimediaImages(query, maxResults)
            if (wikiImages.isNotEmpty()) {
                return@withContext wikiImages
            }

            emptyList()
        }
    }

    private fun searchDuckDuckGoImages(query: String, maxResults: Int): List<WebImageResult> {
        val results = mutableListOf<WebImageResult>()
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            
            // Step 1: Obtain vqd token
            val mainUrl = "https://duckduckgo.com/?q=$encodedQuery"
            val tokenReq = Request.Builder()
                .url(mainUrl)
                .header("User-Agent", USER_AGENT)
                .build()
            val tokenRes = httpClient.newCall(tokenReq).execute()
            val mainHtml = tokenRes.body?.string().orEmpty()

            val vqdPattern = Pattern.compile("vqd=[\"']?([0-9-]+)[\"']?", Pattern.CASE_INSENSITIVE)
            val matcher = vqdPattern.matcher(mainHtml)
            val vqd = if (matcher.find()) matcher.group(1) else null

            if (!vqd.isNullOrBlank()) {
                val imgUrl = "https://duckduckgo.com/i.js?q=$encodedQuery&o=json&p=1&s=0&u=bing&f=,,,&l=us-en&vqd=$vqd"
                val imgReq = Request.Builder()
                    .url(imgUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .build()
                val imgRes = httpClient.newCall(imgReq).execute()
                val jsonStr = imgRes.body?.string().orEmpty()
                val root = JSONObject(jsonStr)
                val resultsArr = root.optJSONArray("results")
                if (resultsArr != null) {
                    for (i in 0 until resultsArr.length()) {
                        if (results.size >= maxResults) break
                        val obj = resultsArr.optJSONObject(i) ?: continue
                        val image = obj.optString("image")
                        val title = obj.optString("title")
                        val source = obj.optString("url")
                        val thumbnail = obj.optString("thumbnail")

                        if (image.isNotBlank() && (image.startsWith("http://") || image.startsWith("https://"))) {
                            results.add(
                                WebImageResult(
                                    title = cleanHtml(title).ifBlank { query },
                                    imageUrl = image,
                                    sourceUrl = source.ifBlank { image },
                                    thumbnail = thumbnail.takeIf { it.isNotBlank() }
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("WebSearchService", "DDG image search failed: ${e.message}")
        }
        return results
    }

    private fun searchWikimediaImages(query: String, maxResults: Int): List<WebImageResult> {
        val results = mutableListOf<WebImageResult>()
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "https://en.wikipedia.org/w/api.php?action=query&prop=pageimages|extracts&piprop=original|thumbnail&pithumbsize=800&generator=search&gsrsearch=$encodedQuery&gsrlimit=5&format=json"

            val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
            val res = httpClient.newCall(req).execute()
            val jsonStr = res.body?.string().orEmpty()
            val json = JSONObject(jsonStr)
            val queryObj = json.optJSONObject("query") ?: return emptyList()
            val pagesObj = queryObj.optJSONObject("pages") ?: return emptyList()

            val keys = pagesObj.keys()
            while (keys.hasNext() && results.size < maxResults) {
                val key = keys.next()
                val page = pagesObj.optJSONObject(key) ?: continue
                val title = page.optString("title")
                val original = page.optJSONObject("original")?.optString("source")
                val thumb = page.optJSONObject("thumbnail")?.optString("source")
                val imgUrl = original ?: thumb

                if (!imgUrl.isNullOrBlank() && (imgUrl.startsWith("http://") || imgUrl.startsWith("https://"))) {
                    results.add(
                        WebImageResult(
                            title = title,
                            imageUrl = imgUrl,
                            sourceUrl = "https://en.wikipedia.org/wiki/${URLEncoder.encode(title.replace(" ", "_"), "UTF-8")}",
                            thumbnail = thumb
                        )
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("WebSearchService", "Wikimedia image search failed: ${e.message}")
        }
        return results
    }

    private fun searchDuckDuckGoHtml(query: String, maxResults: Int): List<WebSearchResult> {
        val results = mutableListOf<WebSearchResult>()
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "https://html.duckduckgo.com/html/?q=$encodedQuery"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.5")
                .build()

            val response = httpClient.newCall(request).execute()
            val html = response.body?.string() ?: return emptyList()

            // Pattern for DuckDuckGo HTML result blocks
            val linkPattern = Pattern.compile(
                "<a[^>]+class=[\"'][^\"']*result__a[^\"']*[\"'][^>]+href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>",
                Pattern.CASE_INSENSITIVE or Pattern.DOTALL
            )
            val snippetPattern = Pattern.compile(
                "<a[^>]+class=[\"'][^\"']*result__snippet[^\"']*[\"'][^>]*>(.*?)</a>",
                Pattern.CASE_INSENSITIVE or Pattern.DOTALL
            )

            val linkMatcher = linkPattern.matcher(html)
            val snippetMatcher = snippetPattern.matcher(html)

            while (linkMatcher.find() && results.size < maxResults) {
                var rawUrl = linkMatcher.group(1).orEmpty()
                val rawTitle = linkMatcher.group(2).orEmpty()
                var snippet = ""

                if (snippetMatcher.find()) {
                    snippet = snippetMatcher.group(1).orEmpty()
                }

                // Extract actual destination URL from DDG redirect url (uddg=...)
                if (rawUrl.contains("uddg=")) {
                    val uddgIndex = rawUrl.indexOf("uddg=") + 5
                    val endIndex = rawUrl.indexOf("&", uddgIndex).takeIf { it != -1 } ?: rawUrl.length
                    val encodedDest = rawUrl.substring(uddgIndex, endIndex)
                    rawUrl = try {
                        URLDecoder.decode(encodedDest, "UTF-8")
                    } catch (_: Exception) {
                        rawUrl
                    }
                }

                val cleanTitle = cleanHtml(rawTitle)
                val cleanSnippet = cleanHtml(snippet)

                if (cleanTitle.isNotBlank() && rawUrl.startsWith("http")) {
                    results.add(
                        WebSearchResult(
                            title = cleanTitle,
                            snippet = cleanSnippet,
                            url = rawUrl
                        )
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("WebSearchService", "DuckDuckGo HTML search failed: ${e.message}")
        }
        return results
    }

    private fun searchDuckDuckGoInstant(query: String, maxResults: Int): List<WebSearchResult> {
        val results = mutableListOf<WebSearchResult>()
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "https://api.duckduckgo.com/?q=$encodedQuery&format=json&no_html=1&skip_disambig=1"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()

            val response = httpClient.newCall(request).execute()
            val jsonStr = response.body?.string() ?: return emptyList()
            val json = JSONObject(jsonStr)

            val abstractText = json.optString("AbstractText")
            val abstractUrl = json.optString("AbstractURL")
            val heading = json.optString("Heading")

            if (abstractText.isNotBlank() && abstractUrl.isNotBlank()) {
                results.add(
                    WebSearchResult(
                        title = if (heading.isNotBlank()) heading else query,
                        snippet = abstractText,
                        url = abstractUrl
                    )
                )
            }

            val relatedTopics = json.optJSONArray("RelatedTopics")
            if (relatedTopics != null) {
                for (i in 0 until relatedTopics.length()) {
                    if (results.size >= maxResults) break
                    val topic = relatedTopics.optJSONObject(i) ?: continue
                    val text = topic.optString("Text")
                    val firstUrl = topic.optString("FirstURL")
                    if (text.isNotBlank() && firstUrl.isNotBlank()) {
                        results.add(
                            WebSearchResult(
                                title = text.take(60),
                                snippet = text,
                                url = firstUrl
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("WebSearchService", "DuckDuckGo Instant search failed: ${e.message}")
        }
        return results
    }

    private fun searchWikipedia(query: String, maxResults: Int): List<WebSearchResult> {
        val results = mutableListOf<WebSearchResult>()
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encodedQuery&format=json&utf8=1"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()

            val response = httpClient.newCall(request).execute()
            val jsonStr = response.body?.string() ?: return emptyList()
            val json = JSONObject(jsonStr)
            val queryObj = json.optJSONObject("query") ?: return emptyList()
            val searchArr = queryObj.optJSONArray("search") ?: return emptyList()

            for (i in 0 until searchArr.length()) {
                if (results.size >= maxResults) break
                val item = searchArr.optJSONObject(i) ?: continue
                val title = item.optString("title")
                val snippet = cleanHtml(item.optString("snippet"))
                val pageId = item.optLong("pageid")
                val articleUrl = "https://en.wikipedia.org/?curid=$pageId"

                if (title.isNotBlank() && snippet.isNotBlank()) {
                    results.add(
                        WebSearchResult(
                            title = title,
                            snippet = snippet,
                            url = articleUrl
                        )
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("WebSearchService", "Wikipedia search failed: ${e.message}")
        }
        return results
    }

    private fun cleanHtml(html: String): String {
        return html
            .replace(Regex("<[^>]*>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun formatGroundingContext(
        query: String,
        results: List<WebSearchResult>,
        images: List<WebImageResult> = emptyList()
    ): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val currentDate = dateFormat.format(Date())

        if (results.isEmpty() && images.isEmpty()) {
            return """
                [Web Search Context]
                Current Date: $currentDate
                Web search for "$query" did not return external snippets. Answer based on existing verified knowledge.
            """.trimIndent()
        }

        val sb = StringBuilder()
        sb.append("[Real-Time Web Search Results]\n")
        sb.append("Query: \"").append(query).append("\"\n")
        sb.append("Current Date: ").append(currentDate).append("\n\n")

        if (results.isNotEmpty()) {
            sb.append("--- Web Page Snippets ---\n")
            results.forEachIndexed { index, item ->
                sb.append("${index + 1}. Title: ").append(item.title).append("\n")
                sb.append("   URL: ").append(item.url).append("\n")
                sb.append("   Summary: ").append(item.snippet).append("\n\n")
            }
        }

        if (images.isNotEmpty()) {
            sb.append("--- Web Images Found ---\n")
            images.forEachIndexed { index, img ->
                sb.append("${index + 1}. Description: ").append(img.title).append("\n")
                sb.append("   Direct Image URL: ").append(img.imageUrl).append("\n")
                sb.append("   Source Webpage: ").append(img.sourceUrl).append("\n\n")
            }
            sb.append("IMAGE EMBEDDING INSTRUCTIONS: You can embed any relevant image directly in your markdown response using `![Description](Direct Image URL)`. Use high-quality image links from above whenever the user asks for pictures or to illustrate your answer.\n\n")
        }

        sb.append("Instructions: Use the above real-time web search results to provide an accurate, up-to-date response. Cite your sources with Markdown links [Source Name](URL) where helpful.")
        return sb.toString()
    }
}

