package com.vidlab.app.ui.catalog

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vidlab.app.data.model.MediaFeedSection
import com.vidlab.app.data.model.MediaFile
import com.vidlab.app.data.remote.MediaCatalogRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CatalogViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MediaCatalogRepository(application.applicationContext)

    private val _feedSections = MutableStateFlow<List<MediaFeedSection>>(emptyList())
    val feedSections: StateFlow<List<MediaFeedSection>> = _feedSections.asStateFlow()

    private val _isFeedLoading = MutableStateFlow(false)
    val isFeedLoading: StateFlow<Boolean> = _isFeedLoading.asStateFlow()

    private val _feedError = MutableStateFlow<String?>(null)
    val feedError: StateFlow<String?> = _feedError.asStateFlow()

    private val _explorerFiles = MutableStateFlow<List<MediaFile>>(emptyList())
    val explorerFiles: StateFlow<List<MediaFile>> = _explorerFiles.asStateFlow()

    private val pathBackStack = mutableListOf<String>()

    init {
        val cached = repository.getCachedFeed()
        if (!cached.isNullOrEmpty()) {
            _feedSections.value = cached
        } else {
            loadMediaCenter()
        }
    }

    fun loadMediaCenter() {
        viewModelScope.launch {
            _isFeedLoading.value = true
            _feedError.value = null
            repository.buildMediaCenterFeed()
                .onSuccess { sections ->
                    _feedSections.value = sections
                    _isFeedLoading.value = false
                }
                .onFailure { err ->
                    _feedError.value = err.localizedMessage ?: "Failed to connect to WebDAV backend"
                    _isFeedLoading.value = false
                }
        }
    }

    fun openExplorerPath(path: String = "/") {
        viewModelScope.launch {
            repository.getRawDirectory(path)
                .onSuccess { files -> _explorerFiles.value = files }
        }
    }

    fun navigateExplorerTo(folder: MediaFile) {
        if (!folder.isDirectory) return
        pathBackStack.add(folder.path)
        openExplorerPath(folder.path)
    }

    fun navigateExplorerBack(): Boolean {
        if (pathBackStack.isEmpty()) return false
        pathBackStack.removeAt(pathBackStack.lastIndex)
        val prevPath = pathBackStack.lastOrNull() ?: "/"
        openExplorerPath(prevPath)
        return true
    }
}
