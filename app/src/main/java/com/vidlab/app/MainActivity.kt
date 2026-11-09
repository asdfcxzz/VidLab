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
            val progressState by viewModel.progressState.collectAsState()
            val singleRenamePlan by viewModel.singleRenamePlan.collectAsState()
            val isSingleRenaming by viewModel.isSingleRenaming.collectAsState()

            var selectedItem by remember { mutableStateOf<ScrapedMediaItem?>(null) }
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

            BackHandler(enabled = drawerState.isOpen || selectedItem != null || showRenamerModal || singleRenamePlan != null || showSourceSheet) {
                if (drawerState.isOpen) coroutineScope.launch { drawerState.close() }
                else if (singleRenamePlan != null) viewModel.dismissSingleRenamePlan()
                else if (showRenamerModal) showRenamerModal = false
                else if (showSourceSheet) showSourceSheet = false
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
                Box(modifier = Modifier.fillMaxSize().background(Color(0xFF09070F))) {
                    if (selectedItem != null) {
                        MediaDetailScreen(
                            item = selectedItem!!,
                            onBack = { selectedItem = null },
                            onPlayFile = { file -> launchPlayer(file) },
                            onRematch = {},
                            onRenamePermanently = { item ->
                                viewModel.prepareSingleItemRename(item)
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
                            onRematchItem = {}
                        )
                    }

                    // Specific Item Rename Preview Bottom Sheet
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

                    // Batch Renamer Drawer Bottom Sheet
                    if (showRenamerModal) {
                        ModalBottomSheet(
                            onDismissRequest = { if (!progressState.isRunning) showRenamerModal = false },
                            containerColor = Color(0xFF141120)
                        ) {
                            AutoRenameScreen(
                                state = progressState,
                                onRestart = { viewModel.triggerLibraryBatchRename() }
                            )
                        }
                    }

                    // Ambiguous Match Manual Selector Dialog
                    if (activeAmbiguousPrompt != null) {
                        AmbiguousMatchDialog(
                            prompt = activeAmbiguousPrompt!!,
                            onSelectMatch = { cand ->
                                viewModel.applyManualRematch(
                                    activeAmbiguousPrompt!!.mediaKey,
                                    cand.id.toString(),
                                    cand.mediaType
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

    private fun launchPlayer(file: MediaFile) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("FILE_PATH", file.path)
            putExtra("FILE_NAME", file.name)
        }
        startActivity(intent)
    }
}
