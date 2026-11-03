// Created by ruoyi.sjd on 2024/12/25.
// Copyright (c) 2024 Alibaba Group Holding Limited All rights reserved.
package com.alibaba.mnnllm.android.model

import java.util.Locale
import com.alibaba.mnnllm.android.modelist.ModelListManager

object ModelTypeUtils {

    private val VL_TOKEN = Regex("(^|[^a-z0-9])vl([^a-z0-9]|$)")

    fun isAudioModel(modelId: String): Boolean {
        return isAudioModelByTagsOrId(modelId)
    }

    fun isMultiModalModel(modelName: String): Boolean {
        return isAudioModel(modelName) || isVisualModel(modelName) || isDiffusionModel(modelName) || isOmni(modelName)
    }

    fun isQnnModel(modelId: String): Boolean {
        val normalizedId = modelId.lowercase(Locale.getDefault())
        if (modelId.startsWith("local/") && normalizedId.contains("qnn")) {
            return true
        }
        val tags = ModelListManager.getModelTags(modelId)
        if (isQnnModel(tags)) {
            return true
        }
        return false;
    }

    fun isDiffusionModel(modelName: String): Boolean {
        val lower = modelName.lowercase(Locale.getDefault())
        return lower.contains("stable-diffusion") || lower.contains("sana")
    }

    fun isSanaModel(modelName: String): Boolean {
        return modelName.lowercase(Locale.getDefault()).contains("sana")
    }

    fun requiresFaceImageInput(modelName: String): Boolean {
        return isSanaModel(modelName)
    }

    fun isVisualModel(modelId: String): Boolean {
        return isVisualModelByTagsOrId(modelId) || isSanaModel(modelId)
    }

    fun isVideoModel(modelId: String): Boolean {
        return isVideoModelByTagsOrId(modelId)
    }

    fun isR1Model(modelName: String): Boolean {
        return modelName.lowercase(Locale.getDefault()).contains("deepseek-r1")
    }

    fun isOmni(modelName: String): Boolean {
        return modelName.lowercase(Locale.getDefault()).contains("omni")
    }

    fun isSupportThinkingSwitchByTags(extraTags: List<String>): Boolean {
        return extraTags.any { it.equals("ThinkingSwitch", ignoreCase = true) }
    }

    fun isThinkingModel(modelId: String): Boolean {
        val tags = ModelListManager.getModelTags(modelId)
        val extraTags = ModelListManager.getExtraTags(modelId)
        return isThinkingModelByTags(tags) ||
            isSupportThinkingSwitchByTags(extraTags) ||
            matchesThinkingName(modelId)
    }

    fun isVisualModelByTagsOrId(modelId: String): Boolean {
        if (isVisualModelByTags(ModelListManager.getModelTags(modelId))) return true
        val normalized = modelId.lowercase(Locale.getDefault())
        if (VL_TOKEN.containsMatchIn(normalized)) return true
        return listOf(
            "vision", "visual", "multimodal", "omni", "llava", "fastvlm", "minicpm-v",
            "lfm2-vl", "docling", "image-gen", "text-to-image"
        ).any { normalized.contains(it) }
    }

    fun isAudioModelByTagsOrId(modelId: String): Boolean {
        if (isAudioModelByTags(ModelListManager.getModelTags(modelId))) return true
        val normalized = modelId.lowercase(Locale.getDefault())
        return listOf(
            "audio", "speech", "voice", "whisper", "tts", "asr", "omni", "talker",
            "zipformer", "bert-vits"
        ).any { normalized.contains(it) }
    }

    fun isVideoModelByTagsOrId(modelId: String): Boolean {
        if (isVideoModelByTags(ModelListManager.getModelTags(modelId))) return true
        return modelId.lowercase(Locale.getDefault()).contains("video")
    }

    fun matchesThinkingName(modelId: String): Boolean {
        val normalized = modelId.lowercase(Locale.getDefault())
        return listOf(
            "think", "reason", "deepseek-r1", "qwq", "vibethinker", "magistral", "gpt-oss"
        ).any { normalized.contains(it) }
    }

    fun isOpenClWarningByExtraTags(extraTags: List<String>): Boolean {
        return extraTags.any {
            it.equals("opencl_warning", ignoreCase = true) ||
                it.equals("OpenCLWarning", ignoreCase = true)
        }
    }

    fun isQnnModel(tags: List<String>): Boolean {
        return tags.any { it.equals("QNN", ignoreCase = true) }
    }

    fun supportAudioOutput(modelName: String): Boolean {
        return isOmni(modelName)
    }

    /**
     * Check if the model is a TTS (Text-to-Speech) model
     */
    fun isTtsModel(modelName: String): Boolean {
        return modelName.lowercase(Locale.getDefault()).contains("bert-vits") ||
               modelName.lowercase(Locale.getDefault()).contains("tts")
    }

    /**
     * Check if the model is a TTS model based on tags
     */
    fun isTtsModelByTags(tags: List<String>): Boolean {
        return tags.any { it.equals("TTS", ignoreCase = true) }
    }

    /**
     * Check if the model is an ASR (Automatic Speech Recognition) model based on tags
     */
    fun isAsrModelByTags(tags: List<String>): Boolean {
        return tags.any { it.equals("ASR", ignoreCase = true) }
    }

    /**
     * Check if the model is a thinking model based on tags
     */
    fun isThinkingModelByTags(tags: List<String>): Boolean {
        return tags.any { it.equals("Think", ignoreCase = true) }
    }

    /**
     * Check if the model is a visual model based on tags
     */
    fun isVisualModelByTags(tags: List<String>): Boolean {
        return tags.any { it.equals("Vision", ignoreCase = true) }
    }

    /**
     * Check if the model is a video model based on tags
     */
    fun isVideoModelByTags(tags: List<String>): Boolean {
        return tags.any { it.equals("Video", ignoreCase = true) }
    }

    /**
     * Check if the model is an audio model based on tags
     */
    fun isAudioModelByTags(tags: List<String>): Boolean {
        return tags.any { it.equals("Audio", ignoreCase = true) }
    }
}
