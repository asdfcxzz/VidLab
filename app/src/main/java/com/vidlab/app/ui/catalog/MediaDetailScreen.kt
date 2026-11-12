package com.vidlab.app.ui.catalog

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.vidlab.app.data.model.CastMember
import com.vidlab.app.data.model.MediaFile
import com.vidlab.app.data.model.ScrapedMediaItem
import com.vidlab.app.data.prefs.WatchProgress
import com.vidlab.app.data.prefs.WatchProgressStore
import com.vidlab.app.data.remote.DownloadTaskManager
import android.widget.Toast
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Close

private val DeepPurpleBg = Color(0xFF0A0E17)
private val SurfaceDark = Color(0xFF131A29)
private val BorderPurple = Color(0xFF222E46)
private val AccentViolet = Color(0xFF8B85CF)

@Composable
fun MediaDetailScreen(
    item: ScrapedMediaItem,
    onBack: () -> Unit = {},
    onPlayFile: (MediaFile) -> Unit = {},
    onPlay: (MediaFile) -> Unit = onPlayFile,
    onRematch: (ScrapedMediaItem) -> Unit = {},
    onRenamePermanently: (ScrapedMediaItem) -> Unit = {},
    onBackClick: () -> Unit = onBack,
    onPlayClick: (MediaFile) -> Unit = onPlay,
    onOpenExternalPlayer: ((MediaFile) -> Unit)? = null,
    onRenameClick: () -> Unit = { onRenamePermanently(item) },
    onFixMatchClick: () -> Unit = { onRematch(item) }
) {
    val context = LocalContext.current
    val progressStore = remember { WatchProgressStore(context) }
    val progressRevision by WatchProgressStore.revision

    var selectedSeasonIndex by remember { mutableStateOf(0) }
    var menuExpanded by remember { mutableStateOf(false) }
    var showPlotExpanded by remember { mutableStateOf(false) }
    var showFileDetailsDialog by remember { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }
    val selectedEpisodePaths = remember { mutableStateListOf<String>() }
    var actionTargetFile by remember { mutableStateOf<MediaFile?>(null) }

    val launchExternal: (MediaFile) -> Unit = { file ->
        if (onOpenExternalPlayer != null) {
            onOpenExternalPlayer(file)
        } else {
            com.vidlab.app.data.remote.RcloneEngine.startForegroundServeService(context)
            val streamUri = Uri.parse(com.vidlab.app.data.remote.RcloneEngine.buildStreamUrl(context, file.path))
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(streamUri, "video/*")
                putExtra(Intent.EXTRA_TITLE, file.name)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
        }
    }

    val seasons = item.seasons
    val currentSeason = seasons.getOrNull(selectedSeasonIndex)
    val heroImg = item.backdropUrl ?: item.posterUrl
    val metadata = item.metadata

    val allEpisodes = remember(seasons) { seasons.flatMap { it.episodes } }
    val nextEpisode = remember(allEpisodes, progressRevision) {
        allEpisodes.firstOrNull { ep ->
            val p = progressStore.getProgress(ep.file.path)
            p == null || !p.watched
        } ?: allEpisodes.firstOrNull()
    }
    val primaryTargetFile = if (item.isSeries) (nextEpisode?.file ?: item.primaryFile) else item.primaryFile
    val primaryProgress = remember(primaryTargetFile, progressRevision) {
        progressStore.getProgress(primaryTargetFile.path)
    }

    val primaryButtonLabel = remember(item, nextEpisode, primaryProgress) {
        if (item.isSeries) {
            val epTag = if (nextEpisode != null) {
                "S%02dE%02d".format(nextEpisode.seasonNumber, nextEpisode.episodeNumber)
            } else "S01E01"
            if (primaryProgress != null && !primaryProgress.watched && primaryProgress.positionMs > 3000L) {
                "Resume $epTag"
            } else {
                "Play $epTag"
            }
        } else {
            if (primaryProgress != null && !primaryProgress.watched && primaryProgress.positionMs > 3000L) {
                "Resume Movie"
            } else {
                "Play Movie"
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepPurpleBg)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 40.dp)
        ) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(350.dp)
                ) {
                    if (!heroImg.isNullOrBlank()) {
                        AsyncImage(
                            model = heroImg,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Black.copy(alpha = 0.25f),
                                        DeepPurpleBg.copy(alpha = 0.78f),
                                        DeepPurpleBg
                                    ),
                                    startY = 140f
                                )
                            )
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onBackClick,
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.55f))
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White
                            )
                        }

                        Box {
                            IconButton(
                                onClick = { menuExpanded = true },
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.55f))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "More Options",
                                    tint = Color.White
                                )
                            }

                            MaterialTheme(
                                shapes = MaterialTheme.shapes.copy(extraSmall = RoundedCornerShape(16.dp))
                            ) {
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false },
                                modifier = Modifier
                                    .background(SurfaceDark)
                                    .border(1.dp, BorderPurple, RoundedCornerShape(16.dp))
                            ) {
                                DropdownMenuItem(
                                    leadingIcon = { Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AccentViolet, modifier = Modifier.size(18.dp)) },
                                    text = { Text(if (item.isSeries) "Mark Entire Series Watched" else "Mark Movie as Watched", color = Color.White, fontSize = 13.sp) },
                                    onClick = {
                                        menuExpanded = false
                                        if (item.isSeries && allEpisodes.isNotEmpty()) {
                                            allEpisodes.forEach { progressStore.markWatched(it.file.path, true) }
                                        } else {
                                            progressStore.markWatched(item.primaryFile.path, true)
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    leadingIcon = { Icon(Icons.Default.RestartAlt, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(18.dp)) },
                                    text = { Text(if (item.isSeries) "Reset Series Progress" else "Reset Movie Progress", color = Color.White, fontSize = 13.sp) },
                                    onClick = {
                                        menuExpanded = false
                                        if (item.isSeries && allEpisodes.isNotEmpty()) {
                                            allEpisodes.forEach { progressStore.markWatched(it.file.path, false) }
                                        } else {
                                            progressStore.markWatched(item.primaryFile.path, false)
                                        }
                                    }
                                )
                                HorizontalDivider(color = BorderPurple, thickness = 0.8.dp, modifier = Modifier.padding(vertical = 4.dp))
                                DropdownMenuItem(
                                    leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(18.dp)) },
                                    text = { Text("Rename & Restructure", color = Color.White, fontSize = 13.sp) },
                                    onClick = {
                                        menuExpanded = false
                                        onRenameClick()
                                    }
                                )
                                DropdownMenuItem(
                                    leadingIcon = { Icon(Icons.Default.AutoFixHigh, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(18.dp)) },
                                    text = { Text("Fix Metadata Match", color = Color.White, fontSize = 13.sp) },
                                    onClick = {
                                        menuExpanded = false
                                        onFixMatchClick()
                                    }
                                )
                                HorizontalDivider(color = BorderPurple, thickness = 0.8.dp, modifier = Modifier.padding(vertical = 4.dp))
                                DropdownMenuItem(
                                    leadingIcon = { Icon(Icons.Default.Download, contentDescription = null, tint = AccentViolet, modifier = Modifier.size(18.dp)) },
                                    text = { Text(if (item.isSeries) "Batch Download Current Folder" else "Download Movie", color = Color.White, fontSize = 13.sp) },
                                    onClick = {
                                        menuExpanded = false
                                        val files = if (item.isSeries && allEpisodes.isNotEmpty()) allEpisodes.map { it.file } else listOf(item.primaryFile)
                                        DownloadTaskManager.enqueueBatch(context, files, item.title)
                                        Toast.makeText(context, "Queued ${files.size} file(s) for download", Toast.LENGTH_SHORT).show()
                                    }
                                )
                                DropdownMenuItem(
                                    leadingIcon = { Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(18.dp)) },
                                    text = { Text("Storage & File Info", color = Color.White, fontSize = 13.sp) },
                                    onClick = {
                                        menuExpanded = false
                                        showFileDetailsDialog = true
                                    }
                                )
                            }
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(horizontal = 20.dp, vertical = 14.dp)
                    ) {
                        Text(
                            text = item.title,
                            color = Color.White,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (metadata?.rating != null && metadata.rating > 0.0) {
                                MetaBadge(text = "★ ${String.format("%.1f", metadata.rating)}")
                            }
                            if (!item.releaseYear.isNullOrBlank()) {
                                MetaBadge(text = item.releaseYear!!)
                            }
                            if (item.isSeries) {
                                val watchedCount = remember(allEpisodes, progressRevision) {
                                    allEpisodes.count { progressStore.getProgress(it.file.path)?.watched == true }
                                }
                                MetaBadge(text = "${seasons.size} Season${if (seasons.size > 1) "s" else ""} • $watchedCount/${item.totalEpisodesCount} Watched")
                            } else {
                                val sizeGb = String.format("%.2f GB", item.primaryFile.size / 1024.0 / 1024.0 / 1024.0)
                                MetaBadge(text = "Movie • $sizeGb")
                            }
                        }

                        if (!metadata?.genres.isNullOrEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = metadata!!.genres.joinToString(" • "),
                                color = Color(0xFFCBD5E1),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            item {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = { onPlayClick(primaryTargetFile) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = DeepPurpleBg,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = primaryButtonLabel,
                                color = DeepPurpleBg,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }

                        OutlinedButton(
                            onClick = { launchExternal(primaryTargetFile) },
                            border = BorderStroke(1.dp, BorderPurple),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = SurfaceDark),
                            modifier = Modifier
                                .height(52.dp)
                                .width(56.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.OpenInNew,
                                contentDescription = "External Player",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    if (primaryProgress != null && !primaryProgress.watched && primaryProgress.fraction > 0.01f) {
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { primaryProgress.fraction },
                            color = AccentViolet,
                            trackColor = BorderPurple,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                        )
                    }
                }
            }

            if (!metadata?.overview.isNullOrBlank()) {
                item {
                    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Overview",
                                color = Color.White,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (showPlotExpanded) "Collapse" else "Expand",
                                color = AccentViolet,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clickable { showPlotExpanded = !showPlotExpanded }
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = metadata!!.overview!!,
                            color = Color(0xFFE2E8F0),
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            maxLines = if (showPlotExpanded) Int.MAX_VALUE else 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { showPlotExpanded = !showPlotExpanded }
                        )
                    }
                }
            }

            if (!metadata?.cast.isNullOrEmpty()) {
                item {
                    Column(modifier = Modifier.padding(top = 12.dp, bottom = 6.dp)) {
                        Text(
                            text = "Cast & Crew",
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                        )

                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            items(metadata!!.cast, key = { it.name + (it.role ?: "") }, contentType = { "cast" }) { member ->
                                CastItemCard(member)
                            }
                        }
                    }
                }
            }

            if (item.isSeries && seasons.isNotEmpty()) {
                item {
                    Column(modifier = Modifier.padding(top = 12.dp)) {
                        Text(
                            text = "Seasons & Episodes",
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                        )

                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(seasons.size, key = { seasons[it].seasonNumber }, contentType = { "season_chip" }) { idx ->
                                val s = seasons[idx]
                                val isSelected = idx == selectedSeasonIndex
                                Surface(
                                    onClick = { selectedSeasonIndex = idx },
                                    color = if (isSelected) AccentViolet else SurfaceDark,
                                    shape = RoundedCornerShape(10.dp),
                                    border = if (!isSelected) BorderStroke(1.dp, BorderPurple) else null
                                ) {
                                    Text(
                                        text = "Season ${s.seasonNumber} (${s.episodes.size})",
                                        color = if (isSelected) Color.White else Color(0xFF94A3B8),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }

            if (currentSeason != null) {
                if (selectionMode) {
                    item(key = "multi_select_bar") {
                        Surface(
                            color = SurfaceDark,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, AccentViolet),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("${selectedEpisodePaths.size} selected", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                TextButton(onClick = {
                                    val seasonPaths = currentSeason.episodes.map { it.file.path }
                                    if (seasonPaths.all { it in selectedEpisodePaths }) selectedEpisodePaths.removeAll(seasonPaths)
                                    else seasonPaths.forEach { if (it !in selectedEpisodePaths) selectedEpisodePaths.add(it) }
                                }) { Text("Select All", color = AccentViolet, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                                Button(
                                    onClick = {
                                        val picked = allEpisodes.filter { it.file.path in selectedEpisodePaths }.map { it.file }
                                        if (picked.isNotEmpty()) {
                                            DownloadTaskManager.enqueueBatch(context, picked, item.title)
                                            Toast.makeText(context, "Queued ${picked.size} episode(s)", Toast.LENGTH_SHORT).show()
                                        }
                                        selectedEpisodePaths.clear()
                                        selectionMode = false
                                    },
                                    enabled = selectedEpisodePaths.isNotEmpty(),
                                    colors = ButtonDefaults.buttonColors(containerColor = AccentViolet),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = null, tint = Color(0xFF0A0E17), modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Download", color = Color(0xFF0A0E17), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                                IconButton(onClick = { selectedEpisodePaths.clear(); selectionMode = false }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color(0xFF94A3B8), modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
                items(currentSeason.episodes, key = { it.file.path }, contentType = { "episode" }) { ep ->
                    val sizeStr = if (ep.file.size > 0) {
                        String.format("%.2f GB", ep.file.size / 1024.0 / 1024.0 / 1024.0)
                    } else "WebDAV Stream"

                    val cleanEpisodeName = ep.title
                        .substringAfter("•", ep.title)
                        .replace(Regex("""(?i)^S\d{1,2}E\d{1,3}\s*[-•:]?\s*"""), "")
                        .trim()
                        .ifEmpty { "Episode ${ep.episodeNumber}" }

                    val epProgress = progressStore.getProgress(ep.file.path)

                    val isSelected = ep.file.path in selectedEpisodePaths
                    EpisodeCard(
                        episodeTitle = cleanEpisodeName,
                        seasonNumber = ep.seasonNumber,
                        episodeNumber = ep.episodeNumber,
                        thumbnailUrl = ep.thumbnail,
                        fileSize = sizeStr,
                        progress = epProgress,
                        selectionMode = selectionMode,
                        isSelected = isSelected,
                        onPlayClick = {
                            if (selectionMode) {
                                if (isSelected) selectedEpisodePaths.remove(ep.file.path) else selectedEpisodePaths.add(ep.file.path)
                            } else onPlayClick(ep.file)
                        },
                        onLongClick = {
                            if (selectionMode) {
                                if (isSelected) selectedEpisodePaths.remove(ep.file.path) else selectedEpisodePaths.add(ep.file.path)
                            } else actionTargetFile = ep.file
                        },
                        onToggleWatched = {
                            val nextState = !(epProgress?.watched ?: false)
                            progressStore.markWatched(ep.file.path, nextState)
                        }
                    )
                }
            }
        }

        actionTargetFile?.let { targetFile ->
            AlertDialog(
                onDismissRequest = { actionTargetFile = null },
                containerColor = SurfaceDark,
                title = { Text(targetFile.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(
                            onClick = {
                                selectionMode = true
                                if (targetFile.path !in selectedEpisodePaths) selectedEpisodePaths.add(targetFile.path)
                                actionTargetFile = null
                            },
                            color = DeepPurpleBg, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, BorderPurple), modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(Icons.Default.SelectAll, contentDescription = null, tint = AccentViolet, modifier = Modifier.size(20.dp))
                                Text("Select Episodes", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                        Surface(
                            onClick = {
                                DownloadTaskManager.enqueueFile(context, targetFile, item.title)
                                Toast.makeText(context, "Downloading ${targetFile.name}", Toast.LENGTH_SHORT).show()
                                actionTargetFile = null
                            },
                            color = DeepPurpleBg, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, BorderPurple), modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(Icons.Default.Download, contentDescription = null, tint = AccentViolet, modifier = Modifier.size(20.dp))
                                Text("Download Episode", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                        Surface(
                            onClick = { actionTargetFile = null; launchExternal(targetFile) },
                            color = DeepPurpleBg, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, BorderPurple), modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(Icons.Default.OpenInNew, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(20.dp))
                                Text("Play with External Player", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { actionTargetFile = null }) { Text("Cancel", color = Color(0xFF94A3B8)) } }
            )
        }

        if (showFileDetailsDialog) {
            val allFiles = remember(item) {
                val eps = item.seasons
                    .sortedBy { it.seasonNumber }
                    .flatMap { s -> s.episodes.sortedBy { it.episodeNumber }.map { it.file } }
                if (eps.isNotEmpty()) eps else listOf(item.primaryFile)
            }
            val totalBytes = remember(allFiles) { allFiles.sumOf { it.size } }
            val totalSizeFormatted = remember(totalBytes) {
                val gb = totalBytes / (1024.0 * 1024.0 * 1024.0)
                if (gb >= 1.0) String.format("%.2f GB", gb)
                else String.format("%.2f MB", totalBytes / (1024.0 * 1024.0))
            }
            val rootFolder = remember(allFiles) {
                val firstDir = allFiles.firstOrNull()?.path?.substringBeforeLast('/', "") ?: ""
                if (item.seasons.size > 1) firstDir.substringBeforeLast('/', firstDir) else firstDir
            }

            AlertDialog(
                onDismissRequest = { showFileDetailsDialog = false },
                containerColor = SurfaceDark,
                title = { Text("Storage & File Info", color = Color.White, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Title: ${item.title}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        if (rootFolder.isNotBlank()) {
                            Text("Folder: $rootFolder", color = Color(0xFF94A3B8), fontSize = 12.sp)
                        }
                        if (allFiles.size > 1) {
                            Text(
                                "Summary: ${item.seasons.size} Season(s) • ${allFiles.size} Episodes • $totalSizeFormatted Total",
                                color = AccentViolet,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            HorizontalDivider(color = BorderPurple, thickness = 0.8.dp)
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 240.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(allFiles, key = { it.path }) { f ->
                                    val fSizeStr = if (f.size >= 1024L * 1024L * 1024L) {
                                        String.format("%.2f GB", f.size / (1024.0 * 1024.0 * 1024.0))
                                    } else {
                                        String.format("%.2f MB", f.size / (1024.0 * 1024.0))
                                    }
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(DeepPurpleBg)
                                            .padding(horizontal = 10.dp, vertical = 7.dp)
                                    ) {
                                        Text(f.name, color = Color.White, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(fSizeStr, color = Color(0xFF94A3B8), fontSize = 11.sp)
                                    }
                                }
                            }
                        } else {
                            Text("Path: ${item.primaryFile.path}", color = Color(0xFF94A3B8), fontSize = 12.sp)
                            Text("File Size: $totalSizeFormatted", color = AccentViolet, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showFileDetailsDialog = false }) {
                        Text("Close", color = AccentViolet, fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
    }
}

@Composable
private fun CastItemCard(member: CastMember) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(82.dp)
    ) {
        Box(
            modifier = Modifier
                .size(68.dp)
                .clip(CircleShape)
                .background(SurfaceDark),
            contentAlignment = Alignment.Center
        ) {
            if (!member.profileUrl.isNullOrBlank()) {
                AsyncImage(
                    model = member.profileUrl,
                    contentDescription = member.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = Color(0xFF7B7394),
                    modifier = Modifier.size(34.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = member.name,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 13.sp
        )

        if (member.role.isNotBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = member.role,
                color = Color(0xFF94A3B8),
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 12.sp
            )
        }
    }
}

@Composable
private fun MetaBadge(text: String) {
    Surface(
        color = SurfaceDark.copy(alpha = 0.85f),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, BorderPurple)
    ) {
        Text(
            text = text,
            color = Color(0xFFDCD6EC),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeCard(
    episodeTitle: String,
    seasonNumber: Int,
    episodeNumber: Int,
    thumbnailUrl: String?,
    fileSize: String,
    progress: WatchProgress?,
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    onPlayClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleWatched: () -> Unit
) {
    Surface(
        color = if (isSelected) Color(0xFF1E263D) else SurfaceDark,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(if (isSelected) 1.2.dp else 0.5.dp, if (isSelected) AccentViolet else BorderPurple),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onPlayClick, onLongClick = onLongClick)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onPlayClick() },
                    colors = CheckboxDefaults.colors(checkedColor = AccentViolet, uncheckedColor = Color(0xFF94A3B8)),
                    modifier = Modifier.padding(end = 6.dp)
                )
            }
            Box(
                modifier = Modifier.width(114.dp).height(66.dp).clip(RoundedCornerShape(8.dp)).background(BorderPurple)
            ) {
                if (!thumbnailUrl.isNullOrBlank()) {
                    AsyncImage(model = thumbnailUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                Box(
                    modifier = Modifier.size(28.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.65f)).align(Alignment.Center),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                }
                if (progress != null && progress.fraction > 0.01f && !progress.watched) {
                    Box(modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.6f))) {
                        Box(modifier = Modifier.fillMaxWidth(progress.fraction).fillMaxHeight().background(AccentViolet))
                    }
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f).padding(vertical = 2.dp)) {
                Text(text = episodeTitle, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "S${seasonNumber}E${episodeNumber} • $fileSize", color = Color(0xFF94A3B8), fontSize = 11.sp)
                    if (progress?.watched == true) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "• Watched", color = Color(0xFF4CAF50), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            if (!selectionMode) {
                IconButton(onClick = onToggleWatched, modifier = Modifier.size(34.dp)) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Toggle Watched",
                        tint = if (progress?.watched == true) Color(0xFF4CAF50) else Color(0xFF4A4266),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
