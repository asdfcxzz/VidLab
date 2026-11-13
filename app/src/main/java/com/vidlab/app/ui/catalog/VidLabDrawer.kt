package com.vidlab.app.ui.catalog

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.vidlab.app.data.remote.RcloneEngine
import com.vidlab.app.data.remote.RcloneRemoteConfig
import java.io.File
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vidlab.app.data.prefs.PlayerPreferences
import com.vidlab.app.data.remote.SimklManager
import com.vidlab.app.data.remote.SimklPinSession
import com.vidlab.app.data.remote.DownloadTaskManager
import com.vidlab.app.data.remote.DownloadStatus
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Close
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val DeepPurpleBg = Color(0xFF100D1C)
private val SurfaceDark = Color(0xFF131A29)
private val BorderPurple = Color(0xFF222E46)
private val AccentViolet = Color(0xFF8B85CF)

@Composable
fun VidLabDrawerContent(
    simklConnected: Boolean,
    onSimklToggle: (Boolean) -> Unit,
    onRescanClick: () -> Unit,
    onRunRenamerClick: () -> Unit,
    onBrowseStorageClick: () -> Unit = {},
    onRecentClick: () -> Unit = {},
    sections: List<com.vidlab.app.data.model.MediaFeedSection> = emptyList(),
    onSelectHistoryItem: (com.vidlab.app.data.model.ScrapedMediaItem) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playerPrefs = remember { PlayerPreferences(context) }
    val simklManager = remember { SimklManager(context) }
    val progressStore = remember { com.vidlab.app.data.prefs.WatchProgressStore(context) }
    var useRclone by remember { mutableStateOf(RcloneEngine.isRcloneModeEnabled(context)) }
    var webDavUrl by remember { mutableStateOf(RcloneEngine.getWebDavBaseUrl(context)) }
    var rcloneRemotes by remember { mutableStateOf(RcloneEngine.getConfiguredRemotes(context)) }
    val drawerPrefs = remember { context.getSharedPreferences("vidlab_drawer_prefs", android.content.Context.MODE_PRIVATE) }
    var showMediaSourceSection by remember { mutableStateOf(drawerPrefs.getBoolean("show_media_source_section", true)) }
    var showRcloneRemotes by remember { mutableStateOf(drawerPrefs.getBoolean("show_rclone_remotes", false)) }
    var showPasteConfDialog by remember { mutableStateOf(false) }
    var pasteConfText by remember { mutableStateOf("") }
    var selectedTab by remember { mutableIntStateOf(0) }

    val pickConfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()?.let { content ->
                RcloneEngine.importConfigText(context, content).onSuccess { list ->
                    rcloneRemotes = list
                    useRclone = true
                    Toast.makeText(context, "Imported ${list.size} rclone remote(s)", Toast.LENGTH_SHORT).show()
                }.onFailure { err ->
                    Toast.makeText(context, "Import failed: ${err.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    var isSignedIn by remember { mutableStateOf(simklManager.isSignedIn) }
    var simklUser by remember { mutableStateOf(simklManager.username ?: "Simkl Member") }
    var scrobbleActive by remember { mutableStateOf(simklManager.scrobbleEnabled) }
    var pinSession by remember { mutableStateOf<SimklPinSession?>(null) }
    var isRequestingPin by remember { mutableStateOf(false) }

    var defaultSpeed by remember { mutableFloatStateOf(playerPrefs.defaultSpeed) }
    var seekStep by remember { mutableIntStateOf(playerPrefs.seekStepSeconds) }
    var resizeMode by remember { mutableIntStateOf(playerPrefs.defaultResizeMode) }
    var autoEnglish by remember { mutableStateOf(playerPrefs.autoEnglishTracks) }
    var preferFfmpeg by remember { mutableStateOf(playerPrefs.preferFfmpegAudio) }
    var volGesture by remember { mutableStateOf(playerPrefs.volumeGestureEnabled) }
    var brightGesture by remember { mutableStateOf(playerPrefs.brightnessGestureEnabled) }
    var skipEnabled by remember { mutableStateOf(playerPrefs.skipSegmentsEnabled) }
    var autoSkip by remember { mutableStateOf(playerPrefs.autoSkipSegments) }
    var subSize by remember { mutableFloatStateOf(playerPrefs.subtitleFontSizeSp) }
    var subColorIdx by remember { mutableIntStateOf(playerPrefs.subtitleColorIndex) }
    var subBottomPct by remember { mutableIntStateOf(playerPrefs.subtitleBottomPaddingPct) }
    var subBgBox by remember { mutableStateOf(playerPrefs.subtitleBgBox) }

    val downloadTasks = DownloadTaskManager.tasks
    val activeDownloadCount = downloadTasks.count { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED }

    if (showPasteConfDialog) {
        AlertDialog(
            onDismissRequest = { showPasteConfDialog = false },
            containerColor = SurfaceDark,
            title = { Text("Paste rclone.conf", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = pasteConfText,
                    onValueChange = { pasteConfText = it },
                    placeholder = { Text("[gdrive]\ntype = drive\n...", color = Color(0xFF64748B), fontSize = 12.sp) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentViolet,
                        unfocusedBorderColor = BorderPurple,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth().height(180.dp)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    RcloneEngine.importConfigText(context, pasteConfText).onSuccess { list ->
                        rcloneRemotes = list
                        useRclone = true
                        showPasteConfDialog = false
                        Toast.makeText(context, "Saved ${list.size} remote(s)", Toast.LENGTH_SHORT).show()
                    }.onFailure { err ->
                        Toast.makeText(context, err.message ?: "Invalid config", Toast.LENGTH_LONG).show()
                    }
                }) { Text("Save", color = AccentViolet, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showPasteConfDialog = false }) { Text("Cancel", color = Color(0xFF94A3B8)) }
            }
        )
    }

    ModalDrawerSheet(
        drawerContainerColor = DeepPurpleBg,
        drawerContentColor = Color.White,
        modifier = Modifier.width(330.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 18.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("VidLab", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(if (useRclone) "Rclone Engine • Media Center" else "WebDAV • Media Center", color = Color(0xFF94A3B8), fontSize = 11.sp)
                }
                Surface(color = SurfaceDark, shape = RoundedCornerShape(8.dp), border = BorderStroke(0.8.dp, BorderPurple)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                        Box(modifier = Modifier.size(7.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF4CAF50)))
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(if (useRclone) "Rclone" else "WebDAV", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SurfaceDark).border(0.8.dp, BorderPurple, RoundedCornerShape(12.dp)).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val tabs = listOf("Source", "Recent", if (activeDownloadCount > 0) "Downloads ($activeDownloadCount)" else "Downloads", "Settings")
                tabs.forEachIndexed { index, label ->
                    val isSelected = selectedTab == index
                    Surface(
                        color = if (isSelected) AccentViolet else Color.Transparent,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(if (index == 2 && activeDownloadCount > 0) 1.35f else 1f).clickable { selectedTab = index }
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)) {
                            Text(label, color = if (isSelected) Color(0xFF0A0E17) else Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                when (selectedTab) {
                    1 -> {
                        val rev = com.vidlab.app.data.prefs.WatchProgressStore.revision.intValue
                        val historyList = remember(sections, rev) {
                            progressStore.getWatchHistory(sections.flatMap { it.items }.distinctBy { it.id })
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("RECENT WATCH HISTORY (${historyList.size})", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            if (historyList.isNotEmpty()) {
                                Text("Clear", color = AccentViolet, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { progressStore.clearHistory() })
                            }
                        }
                        if (historyList.isEmpty()) {
                            Surface(color = SurfaceDark, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                                Text("No watch history yet. Play any movie or episode and it will appear here.", color = Color(0xFF94A3B8), fontSize = 12.sp, modifier = Modifier.padding(14.dp))
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (entry in historyList) {
                                    Surface(onClick = { onSelectHistoryItem(entry.item) }, color = SurfaceDark, shape = RoundedCornerShape(12.dp), border = BorderStroke(0.8.dp, BorderPurple), modifier = Modifier.fillMaxWidth()) {
                                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            coil.compose.AsyncImage(
                                                model = entry.item.posterUrl ?: entry.item.backdropUrl,
                                                contentDescription = entry.item.title,
                                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                                modifier = Modifier.size(width = 44.dp, height = 64.dp).clip(RoundedCornerShape(8.dp))
                                            )
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(entry.item.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                val sub = buildString {
                                                    if (entry.episodeLabel != null) append("${entry.episodeLabel} • ")
                                                    if (entry.progress.watched) append("Watched") else append("${(entry.progress.fraction * 100).toInt()}% watched")
                                                }
                                                Text(sub, color = Color(0xFF94A3B8), fontSize = 11.sp)
                                                Spacer(modifier = Modifier.height(6.dp))
                                                LinearProgressIndicator(
                                                    progress = { if (entry.progress.watched) 1f else entry.progress.fraction },
                                                    color = AccentViolet,
                                                    trackColor = BorderPurple,
                                                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp))
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    2 -> {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Download, contentDescription = null, tint = AccentViolet, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("DOWNLOADS (${downloadTasks.size})", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            }
                            if (downloadTasks.any { it.status == DownloadStatus.COMPLETED || it.status == DownloadStatus.FAILED || it.status == DownloadStatus.CANCELLED }) {
                                Text("Clear", color = AccentViolet, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { DownloadTaskManager.clearFinished() })
                            }
                        }
                        Text("Max 3 concurrent downloads • Saved to Downloads/VidLab", color = Color(0xFF64748B), fontSize = 10.sp)
                        if (downloadTasks.isEmpty()) {
                            Surface(color = SurfaceDark, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                                Text("No active downloads. Long-press an episode or use the 3-dot menu to download.", color = Color(0xFF94A3B8), fontSize = 12.sp, modifier = Modifier.padding(14.dp))
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (task in downloadTasks) {
                                    Surface(color = SurfaceDark, shape = RoundedCornerShape(12.dp), border = BorderStroke(0.8.dp, BorderPurple), modifier = Modifier.fillMaxWidth()) {
                                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(task.fileName, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                    Text(task.folderName, color = Color(0xFF94A3B8), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                }
                                                if (task.status == DownloadStatus.QUEUED || task.status == DownloadStatus.DOWNLOADING) {
                                                    IconButton(onClick = { DownloadTaskManager.cancelTask(context, task.id) }, modifier = Modifier.size(26.dp)) {
                                                        Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color(0xFF94A3B8), modifier = Modifier.size(16.dp))
                                                    }
                                                }
                                            }
                                            LinearProgressIndicator(
                                                progress = { task.progressFraction },
                                                color = when (task.status) { DownloadStatus.COMPLETED -> Color(0xFF4CAF50); DownloadStatus.FAILED -> Color(0xFFEF5350); else -> AccentViolet },
                                                trackColor = BorderPurple,
                                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                                            )
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                val dlStr = String.format("%.1f MB", task.bytesDownloaded / (1024.0 * 1024.0))
                                                val totStr = if (task.totalBytes > 0L) String.format(" / %.1f MB", task.totalBytes / (1024.0 * 1024.0)) else ""
                                                Text("$dlStr$totStr", color = Color(0xFF94A3B8), fontSize = 10.sp)
                                                val statusLabel = when (task.status) {
                                                    DownloadStatus.QUEUED -> "Queued"
                                                    DownloadStatus.DOWNLOADING -> "${(task.progressFraction * 100).toInt()}%"
                                                    DownloadStatus.COMPLETED -> "Completed"
                                                    DownloadStatus.FAILED -> "Failed"
                                                    DownloadStatus.CANCELLED -> "Cancelled"
                                                }
                                                Text(statusLabel, color = when (task.status) { DownloadStatus.COMPLETED -> Color(0xFF4CAF50); DownloadStatus.FAILED -> Color(0xFFEF5350); else -> AccentViolet }, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    1 -> {
                        val rev = com.vidlab.app.data.prefs.WatchProgressStore.revision.intValue
                        val historyList = remember(sections, rev) {
                            progressStore.getWatchHistory(sections.flatMap { it.items }.distinctBy { it.id })
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("RECENT WATCH HISTORY (${historyList.size})", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            if (historyList.isNotEmpty()) {
                                Text("Clear", color = AccentViolet, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { progressStore.clearHistory() })
                            }
                        }
                        if (historyList.isEmpty()) {
                            Surface(color = SurfaceDark, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                                Text("No watch history yet. Play any movie or episode and it will appear here.", color = Color(0xFF94A3B8), fontSize = 12.sp, modifier = Modifier.padding(14.dp))
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (entry in historyList) {
                                    Surface(onClick = { onSelectHistoryItem(entry.item) }, color = SurfaceDark, shape = RoundedCornerShape(12.dp), border = BorderStroke(0.8.dp, BorderPurple), modifier = Modifier.fillMaxWidth()) {
                                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            coil.compose.AsyncImage(
                                                model = entry.item.posterUrl ?: entry.item.backdropUrl,
                                                contentDescription = entry.item.title,
                                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                                modifier = Modifier.size(width = 44.dp, height = 64.dp).clip(RoundedCornerShape(8.dp))
                                            )
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(entry.item.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                val sub = buildString {
                                                    if (entry.episodeLabel != null) append("${entry.episodeLabel} • ")
                                                    if (entry.progress.watched) append("Watched") else append("${(entry.progress.fraction * 100).toInt()}% watched")
                                                }
                                                Text(sub, color = Color(0xFF94A3B8), fontSize = 11.sp)
                                                Spacer(modifier = Modifier.height(6.dp))
                                                LinearProgressIndicator(
                                                    progress = { if (entry.progress.watched) 1f else entry.progress.fraction },
                                                    color = AccentViolet,
                                                    trackColor = BorderPurple,
                                                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp))
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    2 -> {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Download, contentDescription = null, tint = AccentViolet, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("DOWNLOADS (${downloadTasks.size})", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            }
                            if (downloadTasks.any { it.status == DownloadStatus.COMPLETED || it.status == DownloadStatus.FAILED || it.status == DownloadStatus.CANCELLED }) {
                                Text("Clear", color = AccentViolet, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { DownloadTaskManager.clearFinished() })
                            }
                        }
                        Text("Max 3 concurrent downloads • Saved to Downloads/VidLab", color = Color(0xFF64748B), fontSize = 10.sp)
                        if (downloadTasks.isEmpty()) {
                            Surface(color = SurfaceDark, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                                Text("No active downloads. Long-press an episode or use the 3-dot menu to download.", color = Color(0xFF94A3B8), fontSize = 12.sp, modifier = Modifier.padding(14.dp))
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (task in downloadTasks) {
                                    Surface(color = SurfaceDark, shape = RoundedCornerShape(12.dp), border = BorderStroke(0.8.dp, BorderPurple), modifier = Modifier.fillMaxWidth()) {
                                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(task.fileName, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                    Text(task.folderName, color = Color(0xFF94A3B8), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                }
                                                if (task.status == DownloadStatus.QUEUED || task.status == DownloadStatus.DOWNLOADING) {
                                                    IconButton(onClick = { DownloadTaskManager.cancelTask(context, task.id) }, modifier = Modifier.size(26.dp)) {
                                                        Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color(0xFF94A3B8), modifier = Modifier.size(16.dp))
                                                    }
                                                }
                                            }
                                            LinearProgressIndicator(
                                                progress = { task.progressFraction },
                                                color = when (task.status) { DownloadStatus.COMPLETED -> Color(0xFF4CAF50); DownloadStatus.FAILED -> Color(0xFFEF5350); else -> AccentViolet },
                                                trackColor = BorderPurple,
                                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                                            )
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                val dlStr = String.format("%.1f MB", task.bytesDownloaded / (1024.0 * 1024.0))
                                                val totStr = if (task.totalBytes > 0L) String.format(" / %.1f MB", task.totalBytes / (1024.0 * 1024.0)) else ""
                                                Text("$dlStr$totStr", color = Color(0xFF94A3B8), fontSize = 10.sp)
                                                val statusLabel = when (task.status) {
                                                    DownloadStatus.QUEUED -> "Queued"
                                                    DownloadStatus.DOWNLOADING -> "${(task.progressFraction * 100).toInt()}%"
                                                    DownloadStatus.COMPLETED -> "Completed"
                                                    DownloadStatus.FAILED -> "Failed"
                                                    DownloadStatus.CANCELLED -> "Cancelled"
                                                }
                                                Text(statusLabel, color = when (task.status) { DownloadStatus.COMPLETED -> Color(0xFF4CAF50); DownloadStatus.FAILED -> Color(0xFFEF5350); else -> AccentViolet }, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    3 -> {
                        Text("PLAYER SETTINGS", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Default Playback Speed", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                    listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { spd ->
                                        val selected = kotlin.math.abs(defaultSpeed - spd) < 0.01f
                                        Surface(color = if (selected) AccentViolet else BorderPurple, shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f).clickable { defaultSpeed = spd; playerPrefs.defaultSpeed = spd }) {
                                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 6.dp)) {
                                                Text("${spd}x", color = if (selected) Color(0xFF0A0E17) else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                                Text("Double-Tap Seek Step", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                    listOf(5, 10, 15, 30).forEach { sec ->
                                        val selected = seekStep == sec
                                        Surface(color = if (selected) AccentViolet else BorderPurple, shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f).clickable { seekStep = sec; playerPrefs.seekStepSeconds = sec }) {
                                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 6.dp)) {
                                                Text("${sec}s", color = if (selected) Color(0xFF0A0E17) else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                                Text("Default Aspect Ratio", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                    listOf(0 to "Fit", 4 to "Zoom", 3 to "Fill").forEach { (mode, label) ->
                                        val selected = resizeMode == mode
                                        Surface(color = if (selected) AccentViolet else BorderPurple, shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f).clickable { resizeMode = mode; playerPrefs.defaultResizeMode = mode }) {
                                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 6.dp)) {
                                                Text(label, color = if (selected) Color(0xFF0A0E17) else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                                DrawerToggleRow("Auto English Audio & Subs", "Prefer EN tracks automatically", autoEnglish) { autoEnglish = it; playerPrefs.autoEnglishTracks = it }
                                DrawerToggleRow("Prioritize FFmpeg Decoder", "Force software decoding for EAC3/DTS", preferFfmpeg) { preferFfmpeg = it; playerPrefs.preferFfmpegAudio = it }
                                DrawerToggleRow("Volume Swipe Gesture", "Swipe vertically on right side", volGesture) { volGesture = it; playerPrefs.volumeGestureEnabled = it }
                                DrawerToggleRow("Brightness Swipe Gesture", "Swipe vertically on left side", brightGesture) { brightGesture = it; playerPrefs.brightnessGestureEnabled = it }
                            }
                        }

                        Text("SUBTITLE & SKIP SETTINGS", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Subtitle Text Size", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                    listOf(14f, 18f, 22f, 26f).forEach { sz ->
                                        val selected = kotlin.math.abs(subSize - sz) < 0.5f
                                        Surface(color = if (selected) AccentViolet else BorderPurple, shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f).clickable { subSize = sz; playerPrefs.subtitleFontSizeSp = sz }) {
                                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 6.dp)) {
                                                Text("${sz.toInt()}sp", color = if (selected) Color(0xFF0A0E17) else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                                Text("Subtitle Color", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                    listOf("White", "Yellow", "Cyan", "Green").forEachIndexed { idx, label ->
                                        val selected = subColorIdx == idx
                                        Surface(color = if (selected) AccentViolet else BorderPurple, shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f).clickable { subColorIdx = idx; playerPrefs.subtitleColorIndex = idx }) {
                                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 6.dp)) {
                                                Text(label, color = if (selected) Color(0xFF0A0E17) else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                                Text("Bottom Margin", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                    listOf(2, 5, 8, 12).forEach { pct ->
                                        val selected = subBottomPct == pct
                                        Surface(color = if (selected) AccentViolet else BorderPurple, shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f).clickable { subBottomPct = pct; playerPrefs.subtitleBottomPaddingPct = pct }) {
                                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 6.dp)) {
                                                Text("$pct%", color = if (selected) Color(0xFF0A0E17) else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                                DrawerToggleRow("Dark Background Box", "Improve readability on bright scenes", subBgBox) { subBgBox = it; playerPrefs.subtitleBgBox = it }
                                DrawerToggleRow("Show Skip Intro/Outro", "Fetch segments & show skip button", skipEnabled) { skipEnabled = it; playerPrefs.skipSegmentsEnabled = it }
                                DrawerToggleRow("Auto-Skip Intro & Outro", "Jump past segments automatically", autoSkip) { autoSkip = it; playerPrefs.autoSkipSegments = it }
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                defaultSpeed = 1.0f; playerPrefs.defaultSpeed = 1.0f
                                seekStep = 10; playerPrefs.seekStepSeconds = 10
                                resizeMode = 0; playerPrefs.defaultResizeMode = 0
                                autoEnglish = true; playerPrefs.autoEnglishTracks = true
                                preferFfmpeg = false; playerPrefs.preferFfmpegAudio = false
                                volGesture = true; playerPrefs.volumeGestureEnabled = true
                                brightGesture = true; playerPrefs.brightnessGestureEnabled = true
                                subSize = 22f; playerPrefs.subtitleFontSizeSp = 22f
                                subColorIdx = 0; playerPrefs.subtitleColorIndex = 0
                                subBottomPct = 5; playerPrefs.subtitleBottomPaddingPct = 5
                                subBgBox = false; playerPrefs.subtitleBgBox = false
                                skipEnabled = true; playerPrefs.skipSegmentsEnabled = true
                                autoSkip = false; playerPrefs.autoSkipSegments = false
                                Toast.makeText(context, "Settings reset to default", Toast.LENGTH_SHORT).show()
                            },
                            border = BorderStroke(1.dp, BorderPurple),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().height(42.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Reset Settings to Default", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}
@Composable
private fun DrawerToggleRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Color(0xFF94A3B8), fontSize = 10.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AccentViolet))
    }
}
