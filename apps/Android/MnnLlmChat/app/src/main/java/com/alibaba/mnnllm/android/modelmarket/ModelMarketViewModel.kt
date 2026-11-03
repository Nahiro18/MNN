package com.alibaba.mnnllm.android.modelmarket

import android.app.Application
import android.os.Handler
import android.text.TextUtils
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.alibaba.mls.api.download.DownloadInfo
import com.alibaba.mls.api.download.DownloadListener
import com.alibaba.mls.api.download.DownloadState
import com.alibaba.mls.api.download.ModelDownloadManager
import com.alibaba.mnnllm.android.model.Modality
import com.alibaba.mnnllm.android.model.ModelTypeUtils
import com.alibaba.mnnllm.android.chat.model.ChatDataManager
import com.alibaba.mnnllm.android.download.DownloadForegroundServiceManager
import com.alibaba.mnnllm.android.modelmarket.ModelMarketCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

class ModelMarketViewModel(application: Application) : AndroidViewModel(application), DownloadListener {

    companion object {
        private const val TAG = "ModelMarketViewModel"
    }

    private val downloadManager = ModelDownloadManager.getInstance(application)
    private var allModels: List<ModelMarketItemWrapper> = emptyList()
    private var mainHandler: Handler = Handler(application.mainLooper)
    private val lastUpdateTimeMap: MutableMap<String, Long> = HashMap()
    private val lastDownloadProgressStage: MutableMap<String, String> = HashMap()
    private var modelMarketConfig: ModelMarketConfig? = null
    private var currentFilterState: FilterState = FilterState()

    private val _models = MutableLiveData<List<ModelMarketItemWrapper>>()
    val models: LiveData<List<ModelMarketItemWrapper>> = _models

    private val _progressUpdate = MutableLiveData<Pair<String, DownloadInfo>>()
    val progressUpdate: LiveData<Pair<String, DownloadInfo>> = _progressUpdate

    private val _itemUpdate = MutableLiveData<String>()
    val itemUpdate: LiveData<String> = _itemUpdate

    // Add loading and error states
    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private val _loadError = MutableLiveData<String?>()
    val loadError: LiveData<String?> = _loadError

    // Community MNN models discovered on HuggingFace (any org, not only taobao-mnn),
    // merged into search results so users can find and add every MNN model available.
    private var hfSearchResults: List<ModelMarketItemWrapper> = emptyList()
    private var hfSearchJob: kotlinx.coroutines.Job? = null
    private var hfSearchSeq = 0

    init {
        loadModels()
        downloadManager.addListener(this)
    }

    private fun updateDownloadInfo(modelId: String, downloadInfo: DownloadInfo) {
        val currentList = allModels.map { it.copy() }
        currentList.find { it.modelMarketItem.modelId == modelId }?.let {
            it.downloadInfo = downloadInfo
        }
        allModels = currentList

        hfSearchResults = hfSearchResults.map { wrapper ->
            if (wrapper.modelMarketItem.modelId == modelId) {
                wrapper.copy().also { it.downloadInfo = downloadInfo }
            } else {
                wrapper
            }
        }
        // Re-apply current filter state instead of showing all models
        applyCurrentFilters()
    }

    private fun findModelWrapper(modelId: String): ModelMarketItemWrapper? {
        return allModels.find { it.modelMarketItem.modelId == modelId }
            ?: hfSearchResults.find { it.modelMarketItem.modelId == modelId }
    }
    
    private fun applyCurrentFilters() {
        var filteredList = allModels

        if (currentFilterState.tagKeys.isNotEmpty()) {
            filteredList = filteredList.filter { wrapper ->
                currentFilterState.tagKeys.all { tagKey -> 
                    wrapper.modelMarketItem.structuredTags.any { it.key == tagKey }
                }
            }
        }

        if (currentFilterState.vendors.isNotEmpty()) {
            filteredList = filteredList.filter { wrapper ->
                currentFilterState.vendors.contains(wrapper.modelMarketItem.vendor)
            }
        }

        if (currentFilterState.size != null) {
            val (min, max) = when (currentFilterState.size) {
                "<1B" -> 0.0 to 1.0
                "1B-5B" -> 1.0 to 5.0
                "5B-15B" -> 5.0 to 15.0
                ">15B" -> 15.0 to Double.MAX_VALUE
                else -> 0.0 to Double.MAX_VALUE
            }
            filteredList = filteredList.filter { it.modelMarketItem.sizeB >= min && it.modelMarketItem.sizeB < max }
        }

        if (currentFilterState.modality != null) {
            filteredList = filteredList.filter { wrapper ->
                Modality.checkModality(wrapper.modelMarketItem.modelId.lowercase(),
                    currentFilterState.modality!!
                )
            }
        }

        if (currentFilterState.downloadState != null) {
            Log.d(TAG, "Filtering for download state: ${currentFilterState.downloadState}. Total models before filter: ${filteredList.size}")
            
            filteredList = filteredList.filter { wrapper ->
                wrapper.downloadInfo.downloadState == currentFilterState.downloadState
            }
            
            Log.d(TAG, "Models after download state filter: ${filteredList.size}")
        }

        if (currentFilterState.source != null) {
            filteredList = filteredList.filter { wrapper ->
                wrapper.modelMarketItem.sources.containsKey(currentFilterState.source)
            }
        }

        // Apply search query filter
        val searchQuery = currentFilterState.searchQuery.trim()
        if (searchQuery.isNotEmpty()) {
            val searchLower = searchQuery.lowercase(Locale.getDefault())
            filteredList = filteredList.filter { wrapper ->
                val modelMarketItem = wrapper.modelMarketItem
                // Search in model name, description, tags, etc.
                modelMarketItem.modelName.lowercase(Locale.getDefault()).contains(searchLower) ||
                modelMarketItem.description?.lowercase(Locale.getDefault())?.contains(searchLower) == true ||
                modelMarketItem.tags.any { it.lowercase(Locale.getDefault()).contains(searchLower) }
            }

            // Merge community MNN models found on HuggingFace (any org, not only
            // taobao-mnn/Alibaba), so the app search shows every MNN model
            // available on the Hub.
            triggerHuggingFaceSearch(searchQuery)
            val localNames = allModels.map {
                it.modelMarketItem.currentRepoPath.ifEmpty { it.modelMarketItem.modelName }
                    .lowercase(Locale.getDefault())
            }.toHashSet()
            val extra = hfSearchResults.filter {
                !localNames.contains(
                    it.modelMarketItem.currentRepoPath.ifEmpty { it.modelMarketItem.modelName }
                        .lowercase(Locale.getDefault())
                ) &&
                    (currentFilterState.source == null ||
                        it.modelMarketItem.sources.containsKey(currentFilterState.source))
            }
            filteredList = filteredList + extra
        } else {
            hfSearchJob?.cancel()
            hfSearchResults = emptyList()
        }

        _models.postValue(filteredList)
    }

    private fun triggerHuggingFaceSearch(query: String) {
        if (hfSearchJob?.isActive == true) return
        val seq = ++hfSearchSeq
        hfSearchJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val items = HuggingFaceMnnSearcher.searchMnnModels(query)
                if (seq != hfSearchSeq) return@launch
                val wrappers = items.map { item ->
                    val downloadInfo = downloadManager.getDownloadInfo(item.modelId)
                    ModelMarketItemWrapper(item, downloadInfo)
                }
                hfSearchResults = wrappers
                // Re-apply filters so results appear once they arrive.
                applyCurrentFilters()
            } catch (e: Exception) {
                Log.w(TAG, "HuggingFace community search failed: ${e.message}")
            }
        }
    }

    fun loadModels() {
        viewModelScope.launch {
            try {
                // Set loading state
                _isLoading.postValue(true)
                _loadError.postValue(null)
                
                // Get pre-processed config with all models
                val config = ModelRepository.getModelMarketData()
                modelMarketConfig = config
                
                if (config == null) {
                    // Handle case where data couldn't be loaded
                    _isLoading.postValue(false)
                    _loadError.postValue("Failed to load model data")
                    return@launch
                }
                
                // Initialize TagMapper with config
                TagMapper.initializeFromConfig(config)
                
                // Use pre-processed models directly from config
                val marketItems = config.llmModels
                allModels = marketItems.map {
                    val downloadInfo = downloadManager.getDownloadInfo(it.modelId)
                    // If download hasn't started and we have file size in metadata, use it
                    if (downloadInfo.totalSize == 0L && it.fileSize > 0) {
                        downloadInfo.totalSize = it.fileSize
                    }
                    Log.d(TAG, "Model: ${it.modelName}, modelId: ${it.modelId} initial downloadState: ${downloadInfo.downloadState}")
                    ModelMarketItemWrapper(it, downloadInfo)
                }
                Log.d(TAG, "allModels: ${allModels.size} models loaded")
                
                // Clear loading state
                _isLoading.postValue(false)
                applyCurrentFilters()
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load models", e)
                _isLoading.postValue(false)
                _loadError.postValue("Loading failed: ${e.message}")
            }
        }
    }



    fun applyFilters(filterState: FilterState) {
        currentFilterState = filterState
        applyCurrentFilters()
    }

    private fun resolveModelName(modelId: String): String? {
        return findModelWrapper(modelId)?.modelMarketItem?.modelName
    }

    fun startDownload(item: ModelMarketItem) {
        // Persist capabilities (tags/extra tags) before the transfer starts. Community models
        // come from the HuggingFace search and are not part of allModels, so waiting for the
        // download-finished callback used to lose their Think/Vision/Audio metadata.
        ModelMarketCache.writeMarketConfig(item)
        downloadManager.startDownload(item.modelId)
    }

    fun pauseDownload(item: ModelMarketItem) {
        downloadManager.pauseDownload(item.modelId)
    }

    fun getDownloadInfo(item: ModelMarketItem): DownloadInfo {
        return downloadManager.getDownloadInfo(item.modelId)
    }

    fun deleteModel(item: ModelMarketItem) {
        viewModelScope.launch {
            downloadManager.deleteModel(item.modelId)
        }
    }

    fun updateModel(item: ModelMarketItem) {
        ModelMarketCache.writeMarketConfig(item)
        downloadManager.startDownload(item.modelId)
    }

    fun getAvailableVendors(): List<String> {
        val availableVendors = allModels.map { it.modelMarketItem.vendor }.distinct()
        val vendorOrder = modelMarketConfig?.vendorOrder ?: emptyList()
        
        // Sort vendors according to the custom order, with unlisted vendors at the end
        return availableVendors.sortedWith { a, b ->
            val indexA = vendorOrder.indexOf(a)
            val indexB = vendorOrder.indexOf(b)
            
            when {
                indexA == -1 && indexB == -1 -> a.compareTo(b) // Both not in order list, sort alphabetically
                indexA == -1 -> 1 // a not in order list, put it after b
                indexB == -1 -> -1 // b not in order list, put it after a
                else -> indexA.compareTo(indexB) // Both in order list, sort by index
            }
        }
    }

    fun getAvailableTags(): List<Tag> {
        return allModels.flatMap { it.modelMarketItem.structuredTags }
            .distinctBy { it.key }
            .sortedBy { it.getDisplayText() }
    }
    
    fun getQuickFilterTags(): List<Tag> {
        return modelMarketConfig?.let { config ->
            TagMapper.getQuickFilterTags(config.quickFilterTags)
        } ?: emptyList()
    }

    fun handleItemClick(modelItem: ModelMarketItem, downloadInfo: DownloadInfo) {
        when (downloadInfo.downloadState) {
            DownloadState.COMPLETED -> {
                // This part needs to be implemented to run the model
            }
            DownloadState.NOT_START,
            DownloadState.FAILED, 
            DownloadState.PAUSED -> {
                startDownload(modelItem)
            }
            DownloadState.DOWNLOADING -> {
                // Already downloading, could show a toast or do nothing
            }
        }
    }

    // Utility to write market config JSON after download

    // DownloadListener implementation - reusing logic from ModelListPresenter
    override fun onDownloadTotalSize(modelId: String, totalSize: Long) {
        mainHandler.post {
            findModelWrapper(modelId)?.let { updateDownloadInfo(modelId, downloadManager.getDownloadInfo(it.modelMarketItem.modelId)) }
            _itemUpdate.value = modelId
        }
    }

    override fun onDownloadHasUpdate(modelId: String, downloadInfo: DownloadInfo) {
        mainHandler.post {
            findModelWrapper(modelId)?.let { updateDownloadInfo(modelId, downloadInfo) }
            _itemUpdate.value = modelId
        }
    }

    override fun onDownloadStart(modelId: String) {
        lastDownloadProgressStage.remove(modelId)
        lastUpdateTimeMap.remove(modelId)
        mainHandler.post {
            findModelWrapper(modelId)?.let { updateDownloadInfo(modelId, downloadManager.getDownloadInfo(it.modelMarketItem.modelId)) }
            _itemUpdate.value = modelId
            DownloadForegroundServiceManager.onDownloadStateChanged(
                modelId = modelId,
                modelName = resolveModelName(modelId),
                isDownloading = true
            )
        }
    }

    override fun onDownloadFailed(modelId: String, e: Exception) {
        Log.d(TAG, "[onDownloadFailed] Received for modelId: $modelId, error: ${e.message}")
        mainHandler.post {
            val wrapper = findModelWrapper(modelId)
            if (wrapper != null) {
                Log.d(TAG, "[onDownloadFailed] Found wrapper for $modelId. Updating info.")
                updateDownloadInfo(modelId, downloadManager.getDownloadInfo(wrapper.modelMarketItem.modelId))
                _itemUpdate.value = modelId
                Log.d(TAG, "[onDownloadFailed] Fired _itemUpdate for $modelId.")
            } else {
                Log.e(TAG, "[onDownloadFailed] Could not find wrapper for modelId: $modelId")
            }
            DownloadForegroundServiceManager.onDownloadStateChanged(
                modelId = modelId,
                modelName = resolveModelName(modelId),
                isDownloading = false
            )
        }
    }

    override fun onDownloadProgress(modelId: String, downloadInfo: DownloadInfo) {
        Log.v(TAG, "[onDownloadProgress] Entry point for $modelId. Stage: ${downloadInfo.progressStage}, Progress: ${downloadInfo.progress}")
        val lastUpdateTime = lastUpdateTimeMap[modelId]
        val now = System.currentTimeMillis()
        val progressStateChanged = !TextUtils.equals(
            downloadInfo.progressStage,
            lastDownloadProgressStage[modelId]
        )
        Log.d(TAG, "[onDownloadProgress] Progress state changed: $progressStateChanged lastUpdateTime=$lastUpdateTime, now=$now")
//        if (!progressStateChanged && lastUpdateTime != null && now - lastUpdateTime < 500) {
//            return
//        }
        
        lastDownloadProgressStage[modelId] = downloadInfo.progressStage ?: ""
        lastUpdateTimeMap[modelId] = now

        mainHandler.post {
            Log.d(TAG, "onDownloadProgress: modelId=$modelId, progress=${downloadInfo.progress}")
            // Force a full item update if the progress stage changes (e.g., from nothing to "Preparing")
            // to ensure the UI state (like failed -> downloading) is refreshed correctly.
            if (progressStateChanged) {
                findModelWrapper(modelId)?.let { updateDownloadInfo(modelId, downloadInfo) }
                _itemUpdate.value = modelId
            } else {
                // Just update progress without full refresh
                _progressUpdate.value = Pair(modelId, downloadInfo)
            }
            
            DownloadForegroundServiceManager.onDownloadStateChanged(
                modelId = modelId,
                modelName = resolveModelName(modelId),
                isDownloading = downloadInfo.downloadState == DownloadState.DOWNLOADING
            )
        }
    }

    override fun onDownloadFinished(modelId: String, path: String) {
        recordModelTypeIfNeeded(modelId, path)
        mainHandler.post {
            findModelWrapper(modelId)?.let { wrapper ->
                updateDownloadInfo(modelId, downloadManager.getDownloadInfo(wrapper.modelMarketItem.modelId))
                ModelMarketCache.writeMarketConfig(wrapper.modelMarketItem)
            }
            _itemUpdate.value = modelId
            DownloadForegroundServiceManager.onDownloadStateChanged(
                modelId = modelId,
                modelName = resolveModelName(modelId),
                isDownloading = false
            )
        }
    }

    private fun recordModelTypeIfNeeded(modelId: String, path: String) {
        val modelType = resolveModelType(modelId)
        if (modelType == "LLM") return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resolvedPath = if (path.isNotBlank()) {
                    path
                } else {
                    downloadManager.getDownloadedFile(modelId)?.absolutePath.orEmpty()
                }
                if (resolvedPath.isBlank()) {
                    Log.w(TAG, "Skip recording type for $modelId: empty model path")
                    return@launch
                }
                ChatDataManager.getInstance(getApplication()).recordDownloadHistory(
                    modelId,
                    resolvedPath,
                    modelType
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to record type for $modelId", e)
            }
        }
    }

    private fun resolveModelType(modelId: String): String {
        if (ModelTypeUtils.isDiffusionModel(modelId)) return "DIFFUSION"
        if (ModelTypeUtils.isAudioModel(modelId)) return "AUDIO"
        if (ModelTypeUtils.isVisualModel(modelId)) return "VISION"
        if (ModelTypeUtils.isVideoModel(modelId)) return "VIDEO"
        return "LLM"
    }

    override fun onDownloadPaused(modelId: String) {
        mainHandler.post {
            findModelWrapper(modelId)?.let { updateDownloadInfo(modelId, downloadManager.getDownloadInfo(it.modelMarketItem.modelId)) }
            _itemUpdate.value = modelId
            DownloadForegroundServiceManager.onDownloadStateChanged(
                modelId = modelId,
                modelName = resolveModelName(modelId),
                isDownloading = false
            )
        }
    }

    override fun onDownloadFileRemoved(modelId: String) {
        mainHandler.post {
            findModelWrapper(modelId)?.let { updateDownloadInfo(modelId, downloadManager.getDownloadInfo(it.modelMarketItem.modelId)) }
            _itemUpdate.value = modelId
            DownloadForegroundServiceManager.onDownloadStateChanged(
                modelId = modelId,
                modelName = resolveModelName(modelId),
                isDownloading = false
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        downloadManager.removeListener(this)
        mainHandler.removeCallbacksAndMessages(null)
    }
} 
