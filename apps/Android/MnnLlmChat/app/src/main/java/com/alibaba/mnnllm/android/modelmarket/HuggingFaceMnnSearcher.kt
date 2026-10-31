package com.alibaba.mnnllm.android.modelmarket

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Searches HuggingFace Hub for every MNN converted model that is publicly
 * available (any organization: taobao-mnn, AlizarFin, CJKu, Media-techtw,
 * sm96120719, hcomal, and any other community uploader), not only the ones
 * listed in the curated model_market.json.
 *
 * It uses the public HuggingFace Hub search API:
 *   https://huggingface.co/api/models?pipeline_tag=text-generation&search=mnn&full=true
 * Results are cached briefly so repeated searches do not hammer the network.
 */
object HuggingFaceMnnSearcher {

    private const val TAG = "HuggingFaceMnnSearcher"
    private const val HF_MODELS_API = "https://huggingface.co/api/models"
    private const val RESULT_LIMIT = 100
    private const val CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutes

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    private class CacheEntry(val timestamp: Long, val items: List<HfApiModelEntry>)

    @Volatile
    private var cache: CacheEntry? = null

    private data class HfApiModelEntry(
        @SerializedName("_id") val id: String? = null,
        @SerializedName("id") val modelId: String? = null,
        val author: String? = null,
        val tags: List<String>? = null,
        val downloads: Long? = null,
        @SerializedName("lastModified") val lastModified: String? = null,
        @SerializedName("pipeline_tag") val pipelineTag: String? = null,
        val likes: Int? = null,
    )

    /**
     * Search all public MNN models on HuggingFace matching [query].
     * An empty query returns the most relevant MNN models available.
     */
    suspend fun searchMnnModels(query: String): List<ModelMarketItem> = withContext(Dispatchers.IO) {
        try {
            val entries = fetchEntries() ?: return@withContext emptyList()
            entries.mapNotNull { it.toMarketItemOrNull(query) }
        } catch (e: Exception) {
            Log.w(TAG, "HuggingFace MNN search failed: ${e.message}")
            emptyList()
        }
    }

    private suspend fun fetchEntries(): List<HfApiModelEntry>? {
        val now = System.currentTimeMillis()
        cache?.let {
            if (now - it.timestamp < CACHE_TTL_MS) return it.items
        }
        val url = buildString {
            append(HF_MODELS_API)
            append("?pipeline_tag=text-generation")
            append("&search=mnn")
            append("&full=true")
            append("&sort=downloads")
            append("&direction=-1")
            append("&limit=")
            append(RESULT_LIMIT)
        }
        val request = Request.Builder()
            .url(url)
            .addHeader("Accept", "application/json")
            .build()
        val response = httpClient.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "fetchEntries http ${resp.code}")
                return null
            }
            val body = resp.body?.string()
            if (body.isNullOrEmpty()) return null
            val parsed: Array<HfApiModelEntry> =
                gson.fromJson(body, Array<HfApiModelEntry>::class.java) ?: return null
            val list = parsed.toList()
            cache = CacheEntry(now, list)
            return list
        }
    }

    private fun HfApiModelEntry.toMarketItemOrNull(query: String): ModelMarketItem? {
        val repoPath = modelId ?: id ?: return null
        if (!repoPath.contains("/")) return null
        val name = repoPath.substringAfterLast("/")
        val haystack = (name + " " + repoPath + " " + (tags?.joinToString(" ") ?: "")).lowercase()
        // The repo must look like an MNN conversion.
        if (!haystack.contains("mnn")) return null
        // Apply the user's search text locally over the full result set.
        val q = query.trim().lowercase()
        if (q.isNotEmpty() && !haystack.contains(q)) return null

        val vendor = author ?: repoPath.substringBefore("/")
        val sizeB = estimateSizeBillions(name)
        val hfTags = listOf("MNN") + (tags ?: emptyList())
            .filter { !it.startsWith("region:") && !it.equals("text-generation", true) }
            .map { it.substringAfterLast("/") }
            .distinct()
            .take(6)
        val description = "MNN model from HuggingFace ($repoPath)" +
            (if (downloads != null && downloads > 0) " · ${downloads} downloads" else "")

        return ModelMarketItem(
            modelName = name,
            vendor = vendor,
            sizeB = sizeB,
            tags = hfTags,
            categories = emptyList(),
            sources = mapOf("HuggingFace" to repoPath),
            description = description,
            fileSize = 0L,
        ).apply {
            currentSource = "HuggingFace"
            currentRepoPath = repoPath
            modelId = "HuggingFace/$repoPath"
        }
    }

    private fun estimateSizeBillions(name: String): Double {
        val match = Regex("""(\d+(?:\.\d+)?)\s*([bBmM])\b""").find(name) ?: return 0.0
        val value = match.groupValues[1].toDoubleOrNull() ?: return 0.0
        return when (match.groupValues[2].lowercase()) {
            "b" -> value
            "m" -> value / 1000.0
            else -> 0.0
        }
    }
}
