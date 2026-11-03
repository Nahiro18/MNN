// Created by ruoyi.sjd on 2025/5/6.
// Copyright (c) 2024 Alibaba Group Holding Limited All rights reserved.

package com.alibaba.mnnllm.android.chat.input

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import com.alibaba.mnnllm.android.R
import com.alibaba.mnnllm.android.chat.ChatActivity
import com.alibaba.mnnllm.android.chat.DEFAULT_SANA_PROMPT
import com.alibaba.mnnllm.android.chat.input.AttachmentPickerModule.AttachmentType
import com.alibaba.mnnllm.android.chat.input.AttachmentPickerModule.ImagePickCallback
import com.alibaba.mnnllm.android.chat.ChatActivity.Companion.TAG
import com.alibaba.mnnllm.android.chat.input.VoiceRecordingModule.VoiceRecordingListener
import com.alibaba.mnnllm.android.chat.chatlist.ChatViewHolders
import com.alibaba.mnnllm.android.chat.model.ChatDataItem
import com.alibaba.mnnllm.android.databinding.ActivityChatBinding
import com.alibaba.mnnllm.android.llm.LlmSession
import com.alibaba.mnnllm.android.utils.KeyboardUtils
import com.alibaba.mnnllm.android.model.ModelCapabilities
import com.alibaba.mnnllm.android.model.ModelCapabilityDetector
import com.alibaba.mnnllm.android.model.ModelTypeUtils
import com.alibaba.mnnllm.android.utils.Permissions.REQUEST_RECORD_AUDIO_PERMISSION
import java.util.Date
import androidx.lifecycle.lifecycleScope
import com.alibaba.mnnllm.android.modelist.ModelListManager
import com.alibaba.mnnllm.android.modelsettings.ModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatInputComponent(
    private val chatActivity: ChatActivity,
    private val binding: ActivityChatBinding,
    modelId:String,
    modelName: String,
) {
    private var currentModelId: String = modelId
    private var currentModelName: String = modelName
    private var onStopGenerating: (() -> Unit)? = null
    private var onThinkingModeChanged: ((Boolean) -> Unit)? = null
    private var onAudioOutputModeChanged: ((Boolean) -> Unit)? = null
    private var onSendMessage: ((ChatDataItem) -> Unit)? = null
    private lateinit var editUserMessage: EditText
    private var buttonSend: ImageView = binding.btnSend
    var attachmentPickerModule: AttachmentPickerModule? = null
    private lateinit var voiceRecordingModule: VoiceRecordingModule
    private var currentUserMessage: ChatDataItem? = null
    private val imageMore: ImageView = binding.btPlus
    private val buttonSwitchVoice: View = binding.btSwitchAudio
    private var capabilities: ModelCapabilities = ModelCapabilities.NONE
    private var capabilityRequestId = 0
    private var thinkingStateModelId: String? = null

    init {
        buttonSend.setEnabled(false)
        buttonSend.setOnClickListener { handleSendClick() }
        setupEditText()
        setupVoiceRecordingModule()
        setupToggleAudioOutput()
        observeModelList()
        refreshCapabilities()
    }

    /**
     * Update the model name and refresh related UI components
     */
    fun updateModel(newModelId:String, newModelName: String) {
        currentModelId = newModelId
        currentModelName = newModelName
        thinkingStateModelId = null

        voiceRecordingModule.updateModel(newModelName)
        refreshCapabilities()
        updateInputHint()
        editUserMessage.setText(
            resolveInputTextForModel(
                editUserMessage.text?.toString().orEmpty(),
                ModelTypeUtils.requiresFaceImageInput(currentModelId)
            )
        )
    }

    /**
     * Model tags are loaded asynchronously (cache first, then disk/network). Re-evaluate the
     * input capabilities whenever the model list state changes so the Think / attachment
     * buttons appear even when the chat was opened before the metadata was ready.
     */
    private fun observeModelList() {
        chatActivity.lifecycleScope.launch {
            ModelListManager.observeModelList().collect { state ->
                if (state is ModelListManager.ModelListState.Success) {
                    refreshCapabilities()
                }
            }
        }
    }

    private fun refreshCapabilities() {
        val modelId = currentModelId
        val modelName = currentModelName
        val requestId = ++capabilityRequestId

        applyCapabilities(
            ModelCapabilityDetector.detectFromMetadata(modelId, modelName),
            modelId
        )

        chatActivity.lifecycleScope.launch {
            val detected = ModelCapabilityDetector.detect(modelId, modelName)
            if (requestId != capabilityRequestId || modelId != currentModelId) {
                return@launch
            }
            applyCapabilities(detected, modelId)
        }
    }

    private fun applyCapabilities(newCapabilities: ModelCapabilities, modelId: String) {
        val previous = capabilities
        val attachmentsChanged = previous.supportsVision != newCapabilities.supportsVision ||
            previous.supportsAudio != newCapabilities.supportsAudio ||
            previous.supportsVideo != newCapabilities.supportsVideo
        capabilities = newCapabilities

        updateThinkingButton(newCapabilities.supportsThinking, modelId)
        updateAudioOutput()
        updateAttachmentButton(attachmentsChanged)
        updateVoiceButtonVisibility()
        updateInputHint()
    }


    private fun setupToggleAudioOutput() {
        binding.btnToggleAudioOutput.setOnClickListener {
            if (chatActivity.isLoading) {
                return@setOnClickListener
            }
            if (!binding.btnToggleAudioOutput.isSelected) {
                android.app.AlertDialog.Builder(chatActivity)
                    .setMessage(R.string.audio_output_confirm)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        binding.btnToggleAudioOutput.isSelected = true
                        onAudioOutputModeChanged?.apply {
                            this(binding.btnToggleAudioOutput.isSelected)
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } else {
                binding.btnToggleAudioOutput.isSelected = false
                onAudioOutputModeChanged?.apply {
                    this(binding.btnToggleAudioOutput.isSelected)
                }
            }
        }
        updateAudioOutput()
    }

    private fun updateAudioOutput() {
        if (ModelTypeUtils.supportAudioOutput(currentModelName)) {
            binding.btnToggleAudioOutput.visibility = View.VISIBLE
        } else {
            binding.btnToggleAudioOutput.visibility = View.GONE
        }
    }
    
    private fun updateThinkingButton(supportsThinking: Boolean, modelId: String) {
        binding.btnToggleThinking.visibility = if (supportsThinking) View.VISIBLE else View.GONE
        if (!supportsThinking) {
            return
        }
        binding.btnToggleThinking.setOnClickListener {
            if (chatActivity.isLoading) return@setOnClickListener
            binding.btnToggleThinking.isSelected = !binding.btnToggleThinking.isSelected
            onThinkingModeChanged?.apply { this(binding.btnToggleThinking.isSelected) }
        }

        // Load the saved toggle state only once per model, otherwise a metadata refresh would
        // overwrite the value the user just selected.
        if (thinkingStateModelId == modelId) {
            return
        }
        thinkingStateModelId = modelId
        chatActivity.lifecycleScope.launch {
            val enableThinking = withContext(Dispatchers.IO) {
                ModelConfig.loadConfig(modelId)?.jinja?.context?.enableThinking != false
            }
            if (modelId == currentModelId && binding.btnToggleThinking.visibility == View.VISIBLE) {
                binding.btnToggleThinking.isSelected = enableThinking
            }
        }
    }

    private fun handleSendClick() {
        Log.d(
            TAG,
            "handleSendClick isGenerating : ${chatActivity.isGenerating}"
        )
        if (chatActivity.isGenerating) {
            this.onStopGenerating?.invoke()
        } else {
            sendUserMessage()
        }
    }

    private fun setupEditText() {
        editUserMessage = binding.etMessage
        updateInputHint()
        editUserMessage.setText(
            resolveInputTextForModel(
                editUserMessage.text?.toString().orEmpty(),
                ModelTypeUtils.requiresFaceImageInput(currentModelId)
            )
        )
        editUserMessage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
            }

            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
            }

            override fun afterTextChanged(s: Editable) {
                updateSenderButton()
                updateVoiceButtonVisibility()
            }
        })
    }

    private fun updateInputHint() {
        val hintRes = if (ModelTypeUtils.requiresFaceImageInput(currentModelId)) {
            R.string.input_face_image_hint
        } else {
            R.string.input_messages
        }
        editUserMessage.hint = chatActivity.getString(hintRes)
    }

    fun updateSenderButton() {
        val enabled = shouldEnableSendButton(
            isLoading = chatActivity.isLoading,
            isGenerating = chatActivity.isGenerating,
            hasAttachment = currentUserMessage != null,
            inputText = editUserMessage.text.toString(),
            requiresFaceImage = ModelTypeUtils.requiresFaceImageInput(currentModelId)
        )
        buttonSend.isEnabled = enabled
        buttonSend.setImageResource(if (!chatActivity.isGenerating) R.drawable.button_send else R.drawable.ic_stop)
    }

    private fun sendUserMessage() {
        if (!buttonSend.isEnabled) {
            return
        }
        val inputString = editUserMessage.text.toString().trim { it <= ' ' }
        if (currentUserMessage == null) {
            currentUserMessage = ChatDataItem(ChatViewHolders.USER)
        }
        currentUserMessage!!.text = inputString
        currentUserMessage!!.time = chatActivity.dateFormat!!.format(Date())
        editUserMessage.setText("")
        KeyboardUtils.hideKeyboard(editUserMessage)
        this.onSendMessage?.let { it(currentUserMessage!!) }
        if (attachmentPickerModule != null) {
            attachmentPickerModule!!.clearInput()
            attachmentPickerModule!!.hideAttachmentLayout()
        }
        currentUserMessage = null
    }

    private fun updateVoiceButtonVisibility() {
        var visible = capabilities.supportsAudio
        if (chatActivity.isGenerating) {
            visible = false
        } else if (currentUserMessage != null) {
            visible = false
        } else if (!TextUtils.isEmpty(editUserMessage.text.toString())) {
            visible = false
        }
        buttonSwitchVoice.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun updateAttachmentButton(capabilitiesChanged: Boolean) {
        if (!capabilities.supportsAttachment) {
            attachmentPickerModule?.hideAttachmentLayout()
            attachmentPickerModule = null
            imageMore.visibility = View.GONE
            return
        }
        imageMore.visibility = if (currentUserMessage == null) View.VISIBLE else View.GONE
        if (attachmentPickerModule == null || capabilitiesChanged) {
            setupAttachmentPickerModule()
        }
    }

    private fun setupAttachmentPickerModule() {
        attachmentPickerModule?.hideAttachmentLayout()
        val module = AttachmentPickerModule(chatActivity, capabilities)
        attachmentPickerModule = module
        module.setOnImagePickCallback(object : ImagePickCallback {
            override fun onAttachmentPicked(imageUris: List<Uri>?, audio: AttachmentType?) {
                imageMore.visibility = View.GONE
                updateVoiceButtonVisibility()
                currentUserMessage = ChatDataItem(ChatViewHolders.USER)
                when (audio) {
                    AttachmentType.Audio -> {
                        currentUserMessage!!.audioUri = imageUris?.firstOrNull()
                    }
                    AttachmentType.Video -> {
                        currentUserMessage!!.videoUri = imageUris?.firstOrNull()
                    }
                    else -> {
                        currentUserMessage!!.imageUris = imageUris
                    }
                }
                updateSenderButton()
            }

            override fun onAttachmentRemoved() {
                currentUserMessage = null
                imageMore.visibility = View.VISIBLE
                updateSenderButton()
                updateVoiceButtonVisibility()
            }

            override fun onAttachmentLayoutShow() {
                imageMore.setImageResource(R.drawable.ic_bottom)
            }

            override fun onAttachmentLayoutHide() {
                imageMore.setImageResource(R.drawable.ic_plus)
            }
        })
        imageMore.setOnClickListener {
            voiceRecordingModule.exitRecordingMode()
            attachmentPickerModule?.toggleAttachmentVisibility()
        }
    }

    private fun setupVoiceRecordingModule() {
        voiceRecordingModule = VoiceRecordingModule(chatActivity)
        voiceRecordingModule.setOnVoiceRecordingListener(object : VoiceRecordingListener {
            override fun onEnterRecordingMode() {
                updateAudioOutput()
                binding.btnToggleThinking.visibility = View.GONE
                editUserMessage.visibility = View.GONE
                KeyboardUtils.hideKeyboard(editUserMessage)
                if (attachmentPickerModule != null) {
                    attachmentPickerModule!!.hideAttachmentLayout()
                }
                editUserMessage.visibility = View.GONE
            }

            override fun onLeaveRecordingMode() {
                if (capabilities.supportsThinking) {
                    binding.btnToggleThinking.visibility = View.VISIBLE
                }
                updateAudioOutput()
                binding.btnSend.visibility = View.VISIBLE
                editUserMessage.visibility = View.VISIBLE
                editUserMessage.requestFocus()
                KeyboardUtils.showKeyboard(editUserMessage)
            }

            override fun onRecordSuccess(duration: Float, recordingFilePath: String?) {
                val chatDataItem = ChatDataItem.createAudioInputData(
                    chatActivity.dateFormat!!.format(Date()),
                    "",
                    recordingFilePath!!,
                    duration
                )
                this@ChatInputComponent.onSendMessage?.let { it(chatDataItem) }
            }

            override fun onRecordCanceled() {
            }
        })
        voiceRecordingModule!!.setup(chatActivity.isAudioModel)
    }

    fun setOnSendMessage(onSendMessage: (ChatDataItem)->Unit) {
        this.onSendMessage = onSendMessage
    }

    fun setOnThinkingModeChanged(onThinkingModeChanged: (Boolean)->Unit) {
        this.onThinkingModeChanged = onThinkingModeChanged
    }

    fun setOnAudioOutputModeChanged(onAudioOutputChanged: (Boolean)->Unit) {
        this.onAudioOutputModeChanged = onAudioOutputChanged
    }

    fun setIsGenerating(generating: Boolean) {
        updateSenderButton()
        updateVoiceButtonVisibility()
    }

    fun handleResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (attachmentPickerModule != null && attachmentPickerModule!!.canHandleResult(requestCode)) {
            attachmentPickerModule?.onActivityResult(requestCode, resultCode, data)
        }
    }

    fun onLoadingStatesChanged(loading: Boolean) {
        this.updateSenderButton()
        if (!loading && capabilities.supportsAudio) {
            voiceRecordingModule.onEnabled()
        }
    }

    fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                voiceRecordingModule.handlePermissionAllowed()
            } else {
                voiceRecordingModule.handlePermissionDenied()
            }
        } else if (attachmentPickerModule != null && 
                   requestCode == AttachmentPickerModule.REQUEST_CODE_CAMERA_PERMISSION) {
            attachmentPickerModule!!.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    fun setOnStopGenerating(onStopGenerating: () -> Unit) {
        this.onStopGenerating = onStopGenerating
    }

    companion object {
        internal fun shouldEnableSendButton(
            isLoading: Boolean,
            isGenerating: Boolean,
            hasAttachment: Boolean,
            inputText: String,
            requiresFaceImage: Boolean
        ): Boolean {
            if (isGenerating) return true
            if (isLoading) return false
            if (requiresFaceImage) return hasAttachment
            return hasAttachment || inputText.isNotBlank()
        }

        internal fun resolveInputTextForModel(
            inputText: String,
            requiresFaceImage: Boolean
        ): String {
            if (!requiresFaceImage) return inputText
            return if (inputText.isBlank()) DEFAULT_SANA_PROMPT else inputText
        }
    }

}
