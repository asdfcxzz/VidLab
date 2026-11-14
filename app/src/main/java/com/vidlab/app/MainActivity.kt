package com.vidlab.app

import android.content.Intent
import android.os.Bundle
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
            var showRecentHistoryModal by remember { mutableStateOf(false) }
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
                        },
                        onRecentClick = {
                            coroutineScope.launch { drawerState.close() }
                            showRecentHistoryModal = true
                        },
                        sections = sections,
                        onSelectHistoryItem = { item ->
                            coroutineScope.launch { drawerState.close() }
                            selectedItem = item
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

                                        if (showRecentHistoryModal) {
                        val historyStore = remember { com.vidlab.app.data.prefs.WatchProgressStore(this@MainActivity) }
                        val rev = com.vidlab.app.data.prefs.WatchProgressStore.revision.intValue
                        val historyList = remember(sections, rev) {
                            historyStore.getWatchHistory(sections.flatMap { it.items }.distinctBy { it.id })
                        }
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { showRecentHistoryModal = false },
                            containerColor = Color(0xFF131A29),
                            title = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Recent Watch History", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                    if (historyList.isNotEmpty()) {
                                        TextButton(onClick = { historyStore.clearHistory() }) {
                                            Text("Clear", color = Color(0xFF8B85CF), fontSize = 12.sp)
                                        }
                                    }
                                }
                            },
                            text = {
                                if (historyList.isEmpty()) {
                                    Text(
                                        "No watch history yet. Play any movie or episode and it will appear here.",
                                        color = Color(0xFF94A3B8),
                                        fontSize = 13.sp
                                    )
                                } else {
                                    androidx.compose.foundation.lazy.LazyColumn(
                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                        modifier = Modifier.heightIn(max = 420.dp)
                                    ) {
                                        items(historyList.size) { idx ->
                                            val entry = historyList[idx]
                                            Surface(
                                                onClick = {
                                                    showRecentHistoryModal = false
                                                    selectedItem = entry.item
                                                },
                                                color = Color(0xFF1A2236),
                                                shape = RoundedCornerShape(10.dp),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(10.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                                ) {
                                                    coil.compose.AsyncImage(
                                                        model = entry.item.posterUrl ?: entry.item.backdropUrl,
                                                        contentDescription = entry.item.title,
                                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                                        modifier = Modifier.size(width = 46.dp, height = 66.dp).clip(RoundedCornerShape(6.dp))
                                                    )
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(entry.item.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                        val sub = buildString {
                                                            if (entry.episodeLabel != null) append("${entry.episodeLabel} • ")
                                                            if (entry.progress.watched) append("Watched")
                                                            else append("${(entry.progress.fraction * 100).toInt()}% watched")
                                                        }
                                                        Text(sub, color = Color(0xFF94A3B8), fontSize = 11.sp)
                                                        Spacer(modifier = Modifier.height(6.dp))
                                                        LinearProgressIndicator(
                                                            progress = { if (entry.progress.watched) 1f else entry.progress.fraction },
                                                            color = Color(0xFF8B85CF),
                                                            trackColor = Color(0xFF222E46),
                                                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { showRecentHistoryModal = false }) {
                                    Text("Close", color = Color(0xFF8B85CF), fontWeight = FontWeight.Bold)
                                }
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
                    // Ambiguous home-screen popup disabled by user preference,
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
