package com.vidlab.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.vidlab.app.data.model.MediaFile
import com.vidlab.app.data.model.ScrapedMediaItem
import com.vidlab.app.ui.catalog.*
import com.vidlab.app.ui.player.PlayerActivity

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

            var selectedItem by remember { mutableStateOf<ScrapedMediaItem?>(null) }
            var showSourceSheet by remember { mutableStateOf(false) }
            Box(modifier = Modifier.fillMaxSize().background(Color(0xFF09070F))) {
                if (selectedItem != null) {
                    MediaDetailScreen(
                        item = selectedItem!!,
                        onBack = { selectedItem = null },
                        onPlayFile = { file -> launchPlayer(file) }
                    )
                } else {
                    MediaCenterScreen(
                        sections = sections,
                        isLoading = isLoading,
                        errorMessage = errorMessage,
                        onOpenFileSource = {
                            viewModel.openExplorerPath("/")
                            showSourceSheet = true
                        },
                        onRescan = { viewModel.loadMediaCenter() },
                        onPlayFile = { file -> launchPlayer(file) },
                        onSelectItem = { item -> selectedItem = item }
                    )
                }

                if (showSourceSheet) {
                    ModalBottomSheet(
                        onDismissRequest = { showSourceSheet = false },
                        containerColor = Color(0xFF141120)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
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

    private fun launchPlayer(file: MediaFile) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("FILE_PATH", file.path)
            putExtra("FILE_NAME", file.name)
        }
        startActivity(intent)
    }
}
