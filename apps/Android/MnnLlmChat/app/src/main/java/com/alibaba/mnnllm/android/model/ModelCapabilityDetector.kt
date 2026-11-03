package com.alibaba.mnnllm.android.model

import com.alibaba.mnnllm.android.modelist.ModelListManager
import com.alibaba.mnnllm.android.modelsettings.ModelConfig
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Capabilities that affect the chat input UI (thinking toggle, image, audio and video attachments).
 *
 * A capability can come from three different places:
 *  - the model market tags (Think / Vision / Audio / Video),
 *  - the model name/id (useful for community models that are not in model_market.json),
 *  - the model files itself (llm_config.json, config.json, visual.mnn, audio.mnn).
 *
 * Keeping the three sources together avoids hiding the thinking/attachment buttons when a
 * community model was downloaded without market metadata.
 */
data class ModelCapabilities(
    val supportsThinking: Boolean = false,
    val supportsVision: Boolean = false,
    val supportsAudio: Boolean = false,
    val supportsVideo: Boolean = false
) {
    val supportsAttachment: Boolean
        get() = supportsVision || supportsAudio || supportsVideo

    fun merge(other: ModelCapabilities): ModelCapabilities {
        return ModelCapabilities(
            supportsThinking = supportsThinking || other.supportsThinking,
            supportsVision = supportsVision || other.supportsVision,
            supportsAudio = supportsAudio || other.supportsAudio,
            supportsVideo = supportsVideo || other.supportsVideo
        )
    }

    companion object {
        val NONE = ModelCapabilities()
    }
}

object ModelCapabilityDetector {

    private val VL_TOKEN = Regex("(^|[^a-z0-9])vl([^a-z0-9]|$)")

    /**
     * Fast, synchronous detection based on market tags and the model id/name.
     * Safe to call from the UI thread.
     */
    fun detectFromMetadata(modelId: String, modelName: String? = null): ModelCapabilities {
        val tags = runCatching { ModelListManager.getModelTags(modelId) }.getOrDefault(emptyList())
        val extraTags = runCatching { ModelListManager.getExtraTags(modelId) }.getOrDefault(emptyList())
        val haystack = buildHaystack(modelId, modelName)

        return ModelCapabilities(
            supportsThinking = ModelTypeUtils.isThinkingModelByTags(tags) ||
                ModelTypeUtils.isSupportThinkingSwitchByTags(extraTags) ||
                looksLikeThinkingModel(haystack),
            supportsVision = ModelTypeUtils.isVisualModelByTags(tags) ||
                looksLikeVisionModel(haystack) ||
                ModelTypeUtils.requiresFaceImageInput(haystack),
            supportsAudio = ModelTypeUtils.isAudioModelByTags(tags) ||
                looksLikeAudioModel(haystack),
            supportsVideo = ModelTypeUtils.isVideoModelByTags(tags) ||
                haystack.contains("video")
        )
    }

    /**
     * Complete detection. Reads the downloaded model files, so it must be called off the UI thread.
     */
    suspend fun detect(modelId: String, modelName: String? = null): ModelCapabilities =
        withContext(Dispatchers.IO) {
            detectFromMetadata(modelId, modelName).merge(detectFromModelFiles(modelId))
        }

    private fun detectFromModelFiles(modelId: String): ModelCapabilities {
        val modelDir = resolveModelDirectory(modelId) ?: return ModelCapabilities.NONE
        var result = ModelCapabilities.NONE

        val configJson = readJson(File(modelDir, "config.json"))
        if (configJson != null) {
            result = result.merge(capabilitiesFromConfigJson(configJson))
        }

        val llmConfigJson = readJson(File(modelDir, "llm_config.json"))
        if (llmConfigJson != null) {
            result = result.merge(capabilitiesFromLlmConfigJson(llmConfigJson))
        }

        if (File(modelDir, "visual.mnn").exists() || File(modelDir, "qnn/visual.mnn").exists()) {
            result = result.merge(ModelCapabilities(supportsVision = true))
        }
        if (File(modelDir, "audio.mnn").exists()) {
            result = result.merge(ModelCapabilities(supportsAudio = true))
        }
        return result
    }

    private fun capabilitiesFromConfigJson(json: JsonObject): ModelCapabilities {
        val visualModel = json.stringOrNull("visual_model")
        val audioModel = json.stringOrNull("audio_model")
        val videoModel = json.stringOrNull("video_model")
        val hasThinkingKey = json.has("enable_thinking") ||
            json.objectOrNull("jinja")?.hasThinkingContext() == true

        return ModelCapabilities(
            supportsThinking = hasThinkingKey,
            supportsVision = !visualModel.isNullOrBlank(),
            supportsAudio = !audioModel.isNullOrBlank(),
            supportsVideo = !videoModel.isNullOrBlank()
        )
    }

    private fun capabilitiesFromLlmConfigJson(json: JsonObject): ModelCapabilities {
        val jinja = json.objectOrNull("jinja")
        val chatTemplate = jinja?.stringOrNull("chat_template")
        val hasThinkingContext = jinja?.hasThinkingContext() == true ||
            chatTemplate?.contains("enable_thinking", ignoreCase = true) == true

        return ModelCapabilities(
            supportsThinking = hasThinkingContext,
            supportsVision = json.booleanOrFalse("is_visual"),
            supportsAudio = json.booleanOrFalse("is_audio"),
            supportsVideo = json.has("video_pad")
        )
    }

    private fun JsonObject.hasThinkingContext(): Boolean {
        val context = objectOrNull("context") ?: return false
        return context.has("enable_thinking")
    }

    private fun JsonObject.objectOrNull(key: String): JsonObject? {
        val value = get(key) ?: return null
        return if (value.isJsonObject) value.asJsonObject else null
    }

    private fun JsonObject.booleanOrFalse(key: String): Boolean {
        return get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean == true
    }

    private fun JsonObject.stringOrNull(key: String): String? {
        val value = get(key) ?: return null
        return if (value.isJsonPrimitive) value.asString else null
    }

    private fun readJson(file: File): JsonObject? {
        if (!file.exists()) return null
        return runCatching {
            JsonParser.parseString(file.readText()).asJsonObject
        }.getOrNull()
    }

    private fun resolveModelDirectory(modelId: String): File? {
        val configFile = runCatching { ModelConfig.getDefaultConfigFile(modelId) }.getOrNull()
        if (!configFile.isNullOrBlank()) {
            val file = File(configFile)
            return if (file.isDirectory) file else file.parentFile
        }
        return runCatching {
            when {
                modelId.startsWith("local/") -> File(modelId.removePrefix("local/"))
                else -> null
            }
        }.getOrNull()
    }

    private fun buildHaystack(modelId: String, modelName: String?): String {
        return buildString {
            append(modelId)
            if (!modelName.isNullOrBlank()) {
                append(' ')
                append(modelName)
            }
        }.lowercase(Locale.ROOT)
    }

    private fun looksLikeThinkingModel(haystack: String): Boolean {
        return listOf(
            "think", "reason", "deepseek-r1", "qwq", "vibethinker", "magistral", "gpt-oss"
        ).any { haystack.contains(it) }
    }

    private fun looksLikeVisionModel(haystack: String): Boolean {
        if (VL_TOKEN.containsMatchIn(haystack)) return true
        return listOf(
            "vision", "visual", "multimodal", "omni", "llava", "fastvlm", "minicpm-v",
            "lfm2-vl", "docling", "image-gen", "text-to-image", "sana"
        ).any { haystack.contains(it) }
    }

    private fun looksLikeAudioModel(haystack: String): Boolean {
        return listOf(
            "audio", "speech", "voice", "whisper", "tts", "asr", "omni", "talker",
            "zipformer", "bert-vits"
        ).any { haystack.contains(it) }
    }
}
