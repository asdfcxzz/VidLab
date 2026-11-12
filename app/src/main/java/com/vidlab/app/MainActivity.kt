package com.vidlab.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.vidlab.app.data.model.AmbiguousPrompt
import com.vidlab.app.data.model.MediaFile
import com.vidlab.app.data.model.ScrapedMediaItem
import com.vidlab.app.ui.catalog.*
import com.vidlab.app.ui.player.PlayerActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: CatalogViewModel by viewModels()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        coil.Coil.setImageLoader(
            coil.ImageLoader.Builder(applicationContext)
                .memoryCache {
                    coil.memory.MemoryCache.Builder(applicationContext)
                        .maxSizePercent(0.25)
                        .build()
                }
                .diskCache {
                    coil.disk.DiskCache.Builder()
                        .directory(java.io.File(cacheDir, "vidlab_image_cache"))
                        .maxSizeBytes(150L * 1024 * 1024)
                        .build()
                }
                .crossfade(150)
                .respectCacheHeaders(false)
                .build()
        )
        val permsToRequest = buildList {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.Q) {
                add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        if (permsToRequest.isNotEmpty()) {
            val missing = permsToRequest.filter {
                androidx.core.content.ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                registerForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
                ) {}.launch(missing.toTypedArray())
            }
        }

        com.vidlab.app.data.remote.RcloneEngine.startForegroundServeService(this)

        setContent {
            val sections by viewModel.feedSections.collectAsState()
            val isLoading by viewModel.isFeedLoading.collectAsState()
            val errorMessage by viewModel.feedError.collectAsState()
            val progressState by viewModel.progressState.collectAsState()
            val singleRenamePlan by viewModel.singleRenamePlan.collectAsState()
            val isSingleRenaming by viewModel.isSingleRenaming.collectAsState()
            val searchResults by viewModel.searchResults.collectAsState()

            var selectedItem by remember { mutableStateOf<ScrapedMediaItem?>(null) }
            var itemForOptions by remember { mutableStateOf<ScrapedMediaItem?>(null) }
            var showRenamerModal by remember { mutableStateOf(false) }
            var showSourceSheet by remember { mutableStateOf(false) }
            var activeAmbiguousPrompt by remember { mutableStateOf<AmbiguousPrompt?>(null) }

            val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
            val coroutineScope = rememberCoroutineScope()
            var simklConnected by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                viewModel.ambiguousPrompts.collectLatest { prompt ->
                    activeAmbiguousPrompt = prompt
                }
            }

            BackHandler(enabled = drawerState.isOpen || selectedItem != null || showRenamerModal || singleRenamePlan != null || itemForOptions != null) {
                if (drawerState.isOpen) coroutineScope.launch { drawerState.close() }
                else if (singleRenamePlan != null) viewModel.dismissSingleRenamePlan()
                else if (itemForOptions != null) itemForOptions = null
                else if (showRenamerModal) showRenamerModal = false
                else selectedItem = null
            }

            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    VidLabDrawerContent(
                        simklConnected = simklConnected,
                        onSimklToggle = { simklConnected = it },
                        onRescanClick = {
                            coroutineScope.launch { drawerState.close() }
                            viewModel.loadMediaCenter()
                        },
                        onRunRenamerClick = {
                            coroutineScope.launch { drawerState.close() }
                            showRenamerModal = true
                            viewModel.triggerLibraryBatchRename()
                        },
                        onBrowseStorageClick = {
                            coroutineScope.launch { drawerState.close() }
                            showSourceSheet = true
                        }
                    )
                }
            ) {
                Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0A0E17))) {
                    if (selectedItem != null) {
                        MediaDetailScreen(
                            item = selectedItem!!,
                            onBack = { selectedItem = null },
                            onPlayFile = { file -> launchPlayer(file, selectedItem) },
                            onRematch = { item ->
                                itemForOptions = item
                                viewModel.searchCandidates(item.title)
                            },
                            onRenamePermanently = { item ->
                                itemForOptions = item
                                viewModel.searchCandidates(item.title)
                            }
                        )
                    } else {
                        MediaCenterScreen(
                            sections = sections,
                            isLoading = isLoading,
                            errorMessage = errorMessage,
                            onOpenDrawer = { coroutineScope.launch { drawerState.open() } },
                            onRescan = { viewModel.loadMediaCenter() },
                            onSelectItem = { item -> selectedItem = item },
                            onRematchItem = { item ->
                                itemForOptions = item
                                viewModel.searchCandidates(item.title)
                            }
                        )
                    }

                    // Dialog to pick Movie vs Show vs Manual Custom Name
                    if (itemForOptions != null) {
                        RenameOptionsDialog(
                            item = itemForOptions!!,
                            searchResults = searchResults,
                            onSearch = { query -> viewModel.searchCandidates(query) },
                            onSelectMatch = { cand ->
                                viewModel.applyCandidateSelection(itemForOptions!!, cand)
                                itemForOptions = null
                            },
                            onManualApply = { title, year ->
                                viewModel.applyManualCustomRename(itemForOptions!!, title, year)
                                itemForOptions = null
                            },
                            onDismiss = { itemForOptions = null }
                        )
                    }

                    // Operation Preview Sheet
                    if (singleRenamePlan != null) {
                        RenamePreviewBottomSheet(
                            operations = singleRenamePlan!!,
                            isRenaming = isSingleRenaming,
                            onConfirm = {
                                viewModel.executeSingleItemRename {
                                    selectedItem = null
                                }
                            },
                            onDismiss = { viewModel.dismissSingleRenamePlan() }
                        )
                    }

                    // Batch Storage Renamer Modal
                    if (showRenamerModal) {
                        ModalBottomSheet(
                            onDismissRequest = { if (!progressState.isRunning) showRenamerModal = false },
                            containerColor = Color(0xFF131A29)
                        ) {
                            AutoRenameScreen(
                                state = progressState,
                                onRestart = { viewModel.triggerLibraryBatchRename() }
                            )
                        }
                    }

                    // Ambiguous Confirmation Prompt
                    if (activeAmbiguousPrompt != null) {
                        AmbiguousMatchDialog(
                            prompt = activeAmbiguousPrompt!!,
                            onSelectMatch = { cand ->
                                viewModel.applyCandidateSelection(
                                    ScrapedMediaItem(
                                        id = activeAmbiguousPrompt!!.mediaKey,
                                        title = cand.title,
                                        primaryFile = MediaFile("", "", false)
                                    ),
                                    cand
                                )
                                activeAmbiguousPrompt = null
                            },
                            onDismiss = { activeAmbiguousPrompt = null }
                        )
                    }
                }
            }
        }
    }

    private fun launchPlayer(file: MediaFile, seriesItem: ScrapedMediaItem? = null) {
        val allEpisodes = seriesItem?.seasons
            ?.sortedBy { it.seasonNumber }
            ?.flatMap { season -> season.episodes.sortedBy { it.episodeNumber } }
            ?: emptyList()

        val paths = ArrayList<String>()
        val names = ArrayList<String>()
        if (allEpisodes.isNotEmpty()) {
            for (ep in allEpisodes) {
                paths.add(ep.file.path)
                names.add(ep.file.name)
            }
        } else {
            paths.add(file.path)
            names.add(file.name)
        }

        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("FILE_PATH", file.path)
            putExtra("FILE_NAME", file.name)
            putStringArrayListExtra("PLAYLIST_PATHS", paths)
            putStringArrayListExtra("PLAYLIST_NAMES", names)
        }
        startActivity(intent)
    }
}
