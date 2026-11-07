package com.vidlab.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vidlab.app.data.model.MediaFile
import com.vidlab.app.data.model.ScrapedMediaItem
import com.vidlab.app.ui.catalog.*
import com.vidlab.app.ui.player.PlayerActivity
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

            var selectedItem by remember { mutableStateOf<ScrapedMediaItem?>(null) }
            var showSourceSheet by remember { mutableStateOf(false) }
            val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
            val coroutineScope = rememberCoroutineScope()

            var simklConnected by remember { mutableStateOf(false) }

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
                    ModalDrawerSheet(
                        drawerContainerColor = Color(0xFF100D1C),
                        modifier = Modifier.width(300.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                            Text("VidLab", color = Color.White, fontSize = 22.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            Text("Media Center & Scrobbler", color = Color(0xFF9E95B8), fontSize = 12.sp)

                            Spacer(modifier = Modifier.height(24.dp))
                            HorizontalDivider(color = Color(0xFF26203B))
                            Spacer(modifier = Modifier.height(20.dp))

                            // Simkl Integration Section
                            Text("INTEGRATIONS", color = Color(0xFF8B5CF6), fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                            Spacer(modifier = Modifier.height(10.dp))

                            Surface(
                                color = Color(0xFF171324),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth().border(0.5.dp, Color(0xFF26203B), RoundedCornerShape(10.dp))
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text("Simkl Scrobbler", color = Color.White, fontSize = 14.sp)
                                        Text(if (simklConnected) "Logged in • Auto-tracking" else "Not linked", color = if (simklConnected) Color(0xFF4CAF50) else Color(0xFF9E95B8), fontSize = 11.sp)
                                    }
                                    Switch(
                                        checked = simklConnected,
                                        onCheckedChange = { simklConnected = it },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color.White,
                                            checkedTrackColor = Color(0xFF8B5CF6)
                                        )
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(20.dp))

                            // Settings options
                            NavigationDrawerItem(
                                label = { Text("Rescan WebDAV Library", color = Color.White) },
                                icon = { Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.White) },
                                selected = false,
                                onClick = {
                                    coroutineScope.launch { drawerState.close() }
                                    viewModel.loadMediaCenter()
                                },
                                colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent)
                            )

                            NavigationDrawerItem(
                                label = { Text("Browse File Storage", color = Color.White) },
                                icon = { Icon(Icons.Default.Folder, contentDescription = null, tint = Color.White) },
                                selected = false,
                                onClick = {
                                    coroutineScope.launch { drawerState.close() }
                                    viewModel.openExplorerPath("/")
                                    showSourceSheet = true
                                },
                                colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent)
                            )
                        }
                    }
                }
            ) {
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
                            onOpenDrawer = { coroutineScope.launch { drawerState.open() } },
                            onOpenFileSource = {
                                viewModel.openExplorerPath("/")
                                showSourceSheet = true
                            },
                            onRescan = { viewModel.loadMediaCenter() },
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
    }

    private fun launchPlayer(file: MediaFile) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("FILE_PATH", file.path)
            putExtra("FILE_NAME", file.name)
        }
        startActivity(intent)
    }
}
