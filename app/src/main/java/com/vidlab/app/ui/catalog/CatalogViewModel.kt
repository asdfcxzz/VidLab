package com.vidlab.app.ui.catalog

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vidlab.app.data.model.*
import com.vidlab.app.data.remote.MediaCatalogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RenameProgressState(
    val isRunning: Boolean = false,
    val currentStep: String = "Idle",
    val processedCount: Int = 0,
    val totalCount: Int = 0,
    val currentFile: String = "",
    val logs: List<String> = emptyList(),
    val isCompleted: Boolean = false,
    val error: String? = null
)

class CatalogViewModel(application: Application) : AndroidViewModel(application) {

    val repository = MediaCatalogRepository(application)

    private val _feedSections = MutableStateFlow<List<MediaFeedSection>>(emptyList())
    val feedSections: StateFlow<List<MediaFeedSection>> = _feedSections.asStateFlow()

    private val _isFeedLoading = MutableStateFlow(false)
    val isFeedLoading: StateFlow<Boolean> = _isFeedLoading.asStateFlow()

    private val _feedError = MutableStateFlow<String?>(null)
    val feedError: StateFlow<String?> = _feedError.asStateFlow()

    private val _progressState = MutableStateFlow(RenameProgressState())
    val progressState: StateFlow<RenameProgressState> = _progressState.asStateFlow()

    private val _singleRenamePlan = MutableStateFlow<List<RenameOperation>?>(null)
    val singleRenamePlan: StateFlow<List<RenameOperation>?> = _singleRenamePlan.asStateFlow()

    private val _isSingleRenaming = MutableStateFlow(false)
    val isSingleRenaming: StateFlow<Boolean> = _isSingleRenaming.asStateFlow()

    private val _searchResults = MutableStateFlow<List<TmdbSearchResult>>(emptyList())
    val searchResults: StateFlow<List<TmdbSearchResult>> = _searchResults.asStateFlow()

    val ambiguousPrompts: SharedFlow<AmbiguousPrompt> = repository.ambiguousMatchesFlow

    init {
        loadMediaCenter()
    }

    fun loadMediaCenter() {
        viewModelScope.launch {
            _isFeedLoading.value = true
            _feedError.value = null
            val result = repository.buildMediaCenterFeed()
            result.onSuccess { sections ->
                _feedSections.value = sections
                _isFeedLoading.value = false
            }.onFailure { err ->
                _feedError.value = err.localizedMessage
                _isFeedLoading.value = false
            }
        }
    }

    fun searchCandidates(query: String) {
        viewModelScope.launch {
            _searchResults.value = repository.scraper.searchMetadata(query)
        }
    }

    fun applyCandidateSelection(item: ScrapedMediaItem, candidate: TmdbSearchResult) {
        repository.saveManualBinding(item.id, candidate.title, candidate.mediaType, candidate.year)
        val updatedItem = item.copy(title = candidate.title, releaseYear = candidate.year)
        _singleRenamePlan.value = repository.generateRenamePlan(updatedItem, candidate.title, candidate.year)
    }

    fun applyManualCustomRename(item: ScrapedMediaItem, title: String, year: String?) {
        val type = if (item.isSeries) "tv" else "movie"
        repository.saveManualBinding(item.id, title, type, year)
        val updatedItem = item.copy(title = title, releaseYear = year)
        _singleRenamePlan.value = repository.generateRenamePlan(updatedItem, title, year)
    }

    fun dismissSingleRenamePlan() {
        _singleRenamePlan.value = null
    }

    fun executeSingleItemRename(onSuccess: () -> Unit) {
        val ops = _singleRenamePlan.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _isSingleRenaming.value = true
            val app = getApplication<Application>()
            var failed = 0
            for (op in ops) {
                val res = repository.webDavClient.moveFile(op.oldPath, op.newPath)
                if (res.isFailure) failed++
            }
            _isSingleRenaming.value = false
            _singleRenamePlan.value = null

            withContext(Dispatchers.Main) {
                if (failed == 0) {
                    Toast.makeText(app, "Successfully restructured and renamed!", Toast.LENGTH_SHORT).show()
                    onSuccess()
                } else {
                    Toast.makeText(app, "$failed file(s) failed to rename", Toast.LENGTH_LONG).show()
                }
            }
            loadMediaCenter()
        }
    }

    fun triggerLibraryBatchRename() {
        viewModelScope.launch(Dispatchers.IO) {
            _progressState.update {
                it.copy(
                    isRunning = true,
                    currentStep = "Scanning storage for media...",
                    logs = listOf("Starting WebDAV crawl..."),
                    isCompleted = false,
                    error = null
                )
            }

            val feedResult = repository.buildMediaCenterFeed()
            if (feedResult.isFailure) {
                val err = feedResult.exceptionOrNull()?.localizedMessage ?: "Scan failed"
                _progressState.update { it.copy(isRunning = false, error = err) }
                return@launch
            }

            val sections = feedResult.getOrDefault(emptyList())
            val allItems = sections.flatMap { it.items }
            val allOps = mutableListOf<RenameOperation>()

            for (item in allItems) {
                allOps.addAll(repository.generateRenamePlan(item))
            }

            if (allOps.isEmpty()) {
                _progressState.update {
                    it.copy(
                        isRunning = false,
                        isCompleted = true,
                        currentStep = "All files are already correctly structured.",
                        logs = it.logs + "No pending rename operations."
                    )
                }
                return@launch
            }

            _progressState.update {
                it.copy(
                    totalCount = allOps.size,
                    currentStep = "Renaming & restructuring files..."
                )
            }

            var completed = 0
            for (op in allOps) {
                _progressState.update {
                    it.copy(
                        currentFile = "${op.fileName} ➔ ${op.newFileName}",
                        processedCount = completed
                    )
                }

                val moveResult = repository.webDavClient.moveFile(op.oldPath, op.newPath)
                val logEntry = if (moveResult.isSuccess) {
                    "✓ Moved: ${op.fileName} -> ${op.newPath}"
                } else {
                    "✗ Failed: ${op.fileName} (${moveResult.exceptionOrNull()?.localizedMessage})"
                }

                completed++
                _progressState.update {
                    it.copy(
                        processedCount = completed,
                        logs = (listOf(logEntry) + it.logs).take(100)
                    )
                }
            }

            _progressState.update {
                it.copy(
                    isRunning = false,
                    isCompleted = true,
                    currentStep = "Done! Successfully structured $completed file(s)."
                )
            }

            loadMediaCenter()
        }
    }
}
