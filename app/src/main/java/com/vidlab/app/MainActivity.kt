package com.vidlab.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

        setContent {
            val sections by viewModel.feedSections.collectAsState()
            val isLoading by viewModel.isFeedLoading.collectAsState()
            val errorMessage by viewModel.feedError.collectAsState()
            val explorerFiles by viewModel.explorerFiles.collectAsState()
            val searchResults by viewModel.searchResults.collectAsState()
            val renamePlan by viewModel.renamePlan.collectAsState()
            val isRenaming by viewModel.isRenaming.collectAsState()

            var selectedItem by remember { mutableStateOf<ScrapedMediaItem?>(null) }
            var showSourceSheet by remember { mutableStateOf(false) }
            var itemToRematch by remember { mutableStateOf<ScrapedMediaItem?>(null) }
            var activeAmbiguousPrompt by remember { mutableStateOf<AmbiguousPrompt?>(null) }

            val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
            val coroutineScope = rememberCoroutineScope()
            var simklConnected by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                viewModel.ambiguousPrompts.collectLatest { prompt ->
                    activeAmbiguousPrompt = prompt
                }
            }

            BackHandler(enabled = drawerState.isOpen || selectedItem != null) {
                if (drawerState.isOpen) {
                    coroutineScope.launch { drawerState.close() }
                } else {
                    selectedItem = null
                }
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
                        onBrowseStorageClick = {
                            coroutineScope.launch { drawerState.close() }
                            viewModel.openExplorerPath("/")
                            showSourceSheet = true
                        }
                    )
                }
            ) {
                Box(modifier = Modifier.fillMaxSize().background(Color(0xFF09070F))) {
                    if (selectedItem != null) {
                        MediaDetailScreen(
                            item = selectedItem!!,
                            onBack = { selectedItem = null },
                            onPlayFile = { file -> launchPlayer(file) },
                            onRematch = { item ->
                                itemToRematch = item
                                viewModel.searchMetadata(item.title)
                            },
                            onRenamePermanently = { item ->
                                viewModel.prepareRenamePlan(item)
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
                                itemToRematch = item
                                viewModel.searchMetadata(item.title)
                            }
                        )
                    }

                    if (activeAmbiguousPrompt != null) {
                        AmbiguousMatchDialog(
                            prompt = activeAmbiguousPrompt!!,
                            onSelectMatch = { cand ->
                                viewModel.applyManualRematch(activeAmbiguousPrompt!!.mediaKey, String.format("tt%07d", cand.id), cand.mediaType)
                                activeAmbiguousPrompt = null
                            },
                            onDismiss = { activeAmbiguousPrompt = null }
                        )
                    }

                    if (itemToRematch != null) {
                        FixMatchBottomSheet(
                            item = itemToRematch!!,
                            searchResults = searchResults,
                            initialQuery = itemToRematch!!.title,
                            onSearch = { query -> viewModel.searchMetadata(query) },
                            onSelectMatch = { result ->
                                viewModel.applyManualRematch(itemToRematch!!.id, String.format("tt%07d", result.id), result.mediaType)
                                itemToRematch = null
                                selectedItem = null
                                viewModel.clearSearchResults()
                            },
                            onDismiss = {
                                itemToRematch = null
                                viewModel.clearSearchResults()
                            }
                        )
                    }

                    // Permanent Rename Preview Modal
                    if (renamePlan != null) {
                        RenamePreviewBottomSheet(
                            operations = renamePlan!!,
                            isRenaming = isRenaming,
                            onConfirm = {
                                viewModel.executeRename {
                                    selectedItem = null
                                }
                            },
                            onDismiss = { viewModel.dismissRenamePlan() }
                        )
                    }

                    if (showSourceSheet) {
                        ModalBottomSheet(
                            onDismissRequest = { showSourceSheet = false },
                            containerColor = Color(0xFF141120)
                        ) {
                            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                                Text("File Source Explorer", color = Color.White, fontSize = 18.sp)
                                Spacer(modifier = Modifier.height(12.dp))
                                LazyColumn(modifier = Modifier.heightIn(max = 450.dp)) {
                                    items(explorerFiles) { file ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    if (file.isDirectory) viewModel.navigateExplorerTo(file)
                                                    else {
                                                        launchPlayer(file)
                                                        showSourceSheet = false
                                                    }
                                                }
                                                .padding(vertical = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = if (file.isDirectory) Icons.Default.Folder else Icons.Default.PlayArrow,
                                                contentDescription = null,
                                                tint = if (file.isDirectory) Color(0xFFFFB300) else Color.White
                                            )
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Text(file.name, color = Color.White, fontSize = 14.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun launchPlayer(file: MediaFile) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("FILE_PATH", file.path)
            putExtra("FILE_NAME", file.name)
        }
        startActivity(intent)
    }
}
