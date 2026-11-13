package com.vidlab.app.ui.catalog

import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import com.vidlab.app.data.model.MediaFeedSection
import com.vidlab.app.data.model.ScrapedMediaItem
import kotlinx.coroutines.delay

private val DeepPurpleBg = Color(0xFF0A0E17)
private val SurfaceDark = Color(0xFF131A29)
private val BorderPurple = Color(0xFF222E46)
private val AccentViolet = Color(0xFF8B85CF)
private val SecondaryText = Color(0xFF94A3B8)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HeroCarousel(
    items: List<ScrapedMediaItem>,
    onWatchNow: (ScrapedMediaItem) -> Unit,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return
    val pagerState = rememberPagerState(pageCount = { items.size })

    LaunchedEffect(items.size) {
        if (items.size > 1) {
            while (true) {
                delay(5000L)
                val next = (pagerState.currentPage + 1) % items.size
                pagerState.animateScrollToPage(next)
            }
        }
    }

    Box(modifier = modifier.fillMaxWidth().height(490.dp)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val item = items[page]
            val bgUrl = item.backdropUrl ?: item.posterUrl

            Box(modifier = Modifier.fillMaxSize()) {
                if (!bgUrl.isNullOrBlank()) {
                    val heroCtx = androidx.compose.ui.platform.LocalContext.current
                    val heroReq = remember(bgUrl) {
                        ImageRequest.Builder(heroCtx)
                            .data(bgUrl)
                            .size(1080, 640)
                            .precision(Precision.INEXACT)
                            .bitmapConfig(android.graphics.Bitmap.Config.RGB_565)
                            .crossfade(false)
                            .build()
                    }
                    AsyncImage(
                        model = heroReq,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color(0x6609070F), Color.Transparent, DeepPurpleBg),
                                startY = 0f,
                                endY = 1050f
                            )
                        )
                )

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 34.dp, start = 20.dp, end = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = item.title,
                        color = Color.White,
                        fontSize = 23.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    val genres = item.metadata?.genres?.take(3) ?: emptyList()
                    if (genres.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            genres.forEach { genre ->
                                Surface(
                                    color = Color(0x661E1A2E),
                                    shape = CircleShape,
                                    modifier = Modifier.border(0.5.dp, BorderPurple, CircleShape)
                                ) {
                                    Text(
                                        text = genre,
                                        color = Color(0xFFDDD8EC),
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    Button(
                        onClick = { onWatchNow(item) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                        shape = CircleShape,
                        modifier = Modifier.width(175.dp).height(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "Watch Now", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        if (items.size > 1) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(items.size) { index ->
                    val selected = pagerState.currentPage == index
                    Box(
                        modifier = Modifier
                            .height(5.dp)
                            .width(if (selected) 18.dp else 5.dp)
                            .clip(CircleShape)
                            .background(if (selected) AccentViolet else Color.White.copy(alpha = 0.35f))
                    )
                }
            }
        }
    }
}

@Composable
fun ResumePlaybackBar(
    item: ScrapedMediaItem,
    episodeLabel: String?,
    onResume: (ScrapedMediaItem) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = { onResume(item) },
        color = SurfaceDark,
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderPurple),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.width(44.dp).height(62.dp)
            ) {
                val thumbUrl = item.posterUrl ?: item.backdropUrl
                if (!thumbUrl.isNullOrBlank()) {
                    val resCtx = androidx.compose.ui.platform.LocalContext.current
                    val resReq = remember(thumbUrl) {
                        ImageRequest.Builder(resCtx)
                            .data(thumbUrl)
                            .size(140, 200)
                            .precision(Precision.INEXACT)
                            .bitmapConfig(android.graphics.Bitmap.Config.RGB_565)
                            .crossfade(false)
                            .build()
                    }
                    AsyncImage(
                        model = resReq,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize().background(DeepPurpleBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Movie, contentDescription = null, tint = AccentViolet, modifier = Modifier.size(20.dp))
                    }
                }
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "CONTINUE WATCHING",
                    color = AccentViolet,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )
                Text(
                    text = item.title,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!episodeLabel.isNullOrBlank()) {
                    Text(
                        text = episodeLabel,
                        color = SecondaryText,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Button(
                onClick = { onResume(item) },
                colors = ButtonDefaults.buttonColors(containerColor = AccentViolet),
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color(0xFF0A0E17), modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Resume", color = Color(0xFF0A0E17), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaCenterCard(
    item: ScrapedMediaItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(124.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(182.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(0.5.dp, BorderPurple, RoundedCornerShape(8.dp)),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                val posterUrl = item.posterUrl ?: item.backdropUrl
                if (!posterUrl.isNullOrBlank()) {
                    val cardCtx = androidx.compose.ui.platform.LocalContext.current
                    val cardReq = remember(posterUrl) {
                        ImageRequest.Builder(cardCtx)
                            .data(posterUrl)
                            .size(360, 540)
                            .precision(Precision.INEXACT)
                            .bitmapConfig(android.graphics.Bitmap.Config.RGB_565)
                            .crossfade(false)
                            .build()
                    }
                    AsyncImage(
                        model = cardReq,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Brush.verticalGradient(listOf(Color(0xFF221A36), Color(0xFF100D1C))))
                            .padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = item.title,
                            color = AccentViolet.copy(alpha = 0.8f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (item.isSeries && item.seasons.isNotEmpty()) {
                    Surface(
                        color = Color(0xEB0C0A14),
                        shape = RoundedCornerShape(topStart = 6.dp),
                        modifier = Modifier.align(Alignment.BottomEnd)
                    ) {
                        val countText = if (item.seasons.size > 1) "${item.seasons.size}S • ${item.totalEpisodesCount} eps" else "${item.totalEpisodesCount} eps"
                        Text(
                            text = countText,
                            color = Color(0xFFE2DCF8),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = item.title,
            color = Color(0xFFF3F0FA),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        val subtitle = if (item.isSeries && item.seasons.size > 1) "${item.seasons.size} Seasons" else item.releaseYear ?: ""
        if (subtitle.isNotBlank()) {
            Text(text = subtitle, color = SecondaryText, fontSize = 11.sp)
        }
    }
}

@Composable
fun MediaCenterScreen(
    sections: List<MediaFeedSection>,
    isLoading: Boolean,
    errorMessage: String?,
    onOpenDrawer: () -> Unit,
    onRescan: () -> Unit,
    onSelectItem: (ScrapedMediaItem) -> Unit,
    onRematchItem: (ScrapedMediaItem) -> Unit,
    modifier: Modifier = Modifier
) {
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    val context = androidx.compose.ui.platform.LocalContext.current
    var pendingSelectRemotes by remember { mutableStateOf<List<com.vidlab.app.data.remote.RcloneRemoteConfig>?>(null) }
    val topImportPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()?.let { content ->
                com.vidlab.app.data.remote.RcloneEngine.importConfigText(context, content).onSuccess { list ->
                    pendingSelectRemotes = list
                }.onFailure { err ->
                    android.widget.Toast.makeText(context, "Import failed: ${err.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    pendingSelectRemotes?.let { list ->
        AlertDialog(
            onDismissRequest = {
                pendingSelectRemotes = null
                onRescan()
            },
            containerColor = SurfaceDark,
            title = { Text("Select Remotes to Use", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Choose which remotes VidLab should scan and stream from:", color = SecondaryText, fontSize = 12.sp)
                    list.forEachIndexed { idx, rem ->
                        Surface(
                            color = DeepPurpleBg,
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(0.8.dp, if (rem.enabled) AccentViolet else BorderPurple),
                            onClick = {
                                pendingSelectRemotes = list.mapIndexed { i, r -> if (i == idx) r.copy(enabled = !r.enabled) else r }
                            }
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(rem.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    Text("Type: ${rem.type}", color = SecondaryText, fontSize = 11.sp)
                                }
                                Checkbox(
                                    checked = rem.enabled,
                                    onCheckedChange = { checked ->
                                        pendingSelectRemotes = list.mapIndexed { i, r -> if (i == idx) r.copy(enabled = checked) else r }
                                    },
                                    colors = CheckboxDefaults.colors(checkedColor = AccentViolet, uncheckedColor = SecondaryText)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    com.vidlab.app.data.remote.RcloneEngine.saveConfiguredRemotes(context, list)
                    com.vidlab.app.data.remote.RcloneEngine.setRcloneModeEnabled(context, true)
                    com.vidlab.app.data.remote.RcloneEngine.restartServeIfNeeded(context)
                    pendingSelectRemotes = null
                    onRescan()
                }) { Text("Save & Scan", color = AccentViolet, fontWeight = FontWeight.Bold) }
            }
        )
    }
    val progressStore = remember { com.vidlab.app.data.prefs.WatchProgressStore(context) }
    val heroItem by remember(sections) {
        derivedStateOf {
            @Suppress("UNUSED_VARIABLE")
            val rev = com.vidlab.app.data.prefs.WatchProgressStore.revision.intValue
            val allItems = sections.flatMap { it.items }.distinctBy { it.id }
            progressStore.getLastWatchedItem(allItems)
                ?: allItems.firstOrNull { !it.backdropUrl.isNullOrBlank() }
                ?: allItems.firstOrNull()
        }
    }

    val filteredSections = remember(sections, searchQuery) {
        if (searchQuery.isBlank()) sections
        else {
            sections.mapNotNull { sec ->
                val matched = sec.items.filter { it.title.contains(searchQuery, ignoreCase = true) }
                if (matched.isNotEmpty()) sec.copy(items = matched) else null
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DeepPurpleBg)
    ) {
        val carouselItems = remember(sections) {
            sections.flatMap { it.items }.distinctBy { it.id }.take(6)
        }
        val resumeEntry = remember(sections) {
            val allItems = sections.flatMap { it.items }
            var found: Pair<ScrapedMediaItem, String>? = null
            for (item in allItems) {
                val ep = item.seasons.flatMap { it.episodes }.firstOrNull { ep ->
                    val p = progressStore.getProgress(ep.file.path)
                    p != null && !p.watched && p.positionMs > 0L
                }
                if (ep != null) {
                    val label = if (item.isSeries) {
                        String.format("S%02dE%02d • %s", ep.seasonNumber, ep.episodeNumber, ep.title)
                    } else {
                        "Movie"
                    }
                    found = (item to label)
                    break
                }
            }
            found
        }
        LazyColumn(
            contentPadding = PaddingValues(
                top = if (isSearchActive) 136.dp else 0.dp,
                bottom = 32.dp
            ),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            if (!isSearchActive && carouselItems.isNotEmpty()) {
                item(key = "hero_carousel") {
                    HeroCarousel(
                        items = carouselItems,
                        onWatchNow = { onSelectItem(it) }
                    )
                }
            }
            if (!isSearchActive && resumeEntry != null) {
                item(key = "resume_playback_bar") {
                    ResumePlaybackBar(
                        item = resumeEntry.first,
                        episodeLabel = resumeEntry.second,
                        onResume = { onSelectItem(it) }
                    )
                }
            }

                items(filteredSections, key = { it.title }, contentType = { "section" }) { section ->
                    Column {
                        Text(
                            text = section.title,
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(section.items, key = { it.id }, contentType = { "poster" }) { item ->
                                MediaCenterCard(
                                    item = item,
                                    onClick = { onSelectItem(item) },
                                    onLongClick = { onRematchItem(item) }
                                )
                            }
                        }
                    }
                }
            }
        if (sections.isEmpty() && !isSearchActive) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(color = AccentViolet, strokeWidth = 3.dp)
                    Text(
                        "Scanning media library...",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Fetching files & matching metadata",
                        color = SecondaryText,
                        fontSize = 12.sp
                    )
                } else {
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    val isConfigured = remember(sections, errorMessage) {
                        com.vidlab.app.data.remote.RcloneEngine.hasAnyConfiguredSource(ctx)
                    }
                    var showPasteDialog by remember { mutableStateOf(false) }
                    var pasteText by remember { mutableStateOf("") }
                    var showWebDavDialog by remember { mutableStateOf(false) }
                    var webDavInput by remember { mutableStateOf("http://127.0.0.1:8080") }



                    if (showPasteDialog) {
                        AlertDialog(
                            onDismissRequest = { showPasteDialog = false },
                            containerColor = SurfaceDark,
                            title = { Text("Paste rclone.conf", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                            text = {
                                OutlinedTextField(
                                    value = pasteText,
                                    onValueChange = { pasteText = it },
                                    placeholder = { Text("[gdrive]\ntype = drive\n...", color = SecondaryText, fontSize = 12.sp) },
                                    modifier = Modifier.fillMaxWidth().height(170.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        focusedBorderColor = AccentViolet,
                                        unfocusedBorderColor = BorderPurple
                                    )
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    com.vidlab.app.data.remote.RcloneEngine.importConfigText(ctx, pasteText).onSuccess { list ->
                                        showPasteDialog = false
                                        pasteText = ""
                                        pendingSelectRemotes = list
                                    }.onFailure { err ->
                                        android.widget.Toast.makeText(ctx, "Invalid config: ${err.message}", android.widget.Toast.LENGTH_LONG).show()
                                    }
                                }) { Text("Save & Scan", color = AccentViolet, fontWeight = FontWeight.Bold) }
                            },
                            dismissButton = {
                                TextButton(onClick = { showPasteDialog = false }) { Text("Cancel", color = SecondaryText) }
                            }
                        )
                    }

                    if (showWebDavDialog) {
                        AlertDialog(
                            onDismissRequest = { showWebDavDialog = false },
                            containerColor = SurfaceDark,
                            title = { Text("Connect WebDAV Server", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Enter the URL of your local or remote WebDAV media server.", color = SecondaryText, fontSize = 12.sp)
                                    OutlinedTextField(
                                        value = webDavInput,
                                        onValueChange = { webDavInput = it },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White,
                                            focusedBorderColor = AccentViolet,
                                            unfocusedBorderColor = BorderPurple
                                        )
                                    )
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    com.vidlab.app.data.remote.RcloneEngine.setWebDavBaseUrl(ctx, webDavInput)
                                    com.vidlab.app.data.remote.RcloneEngine.setRcloneModeEnabled(ctx, false)
                                    showWebDavDialog = false
                                    onRescan()
                                }) { Text("Connect & Scan", color = AccentViolet, fontWeight = FontWeight.Bold) }
                            },
                            dismissButton = {
                                TextButton(onClick = { showWebDavDialog = false }) { Text("Cancel", color = SecondaryText) }
                            }
                        )
                    }

                    if (!isConfigured && errorMessage.isNullOrBlank()) {
                        Surface(
                            color = SurfaceDark,
                            shape = RoundedCornerShape(16.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, BorderPurple),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text("Welcome to VidLab", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    "Stream your personal cloud drives and WebDAV servers with automatic posters, episode tracking, and hardware/software playback.",
                                    color = SecondaryText,
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("1. Connect a cloud remote via rclone.conf or a WebDAV URL.", color = Color(0xFFCBD5E1), fontSize = 12.sp)
                                    Text("2. Movies, shows, and anime are matched automatically from any folder layout.", color = Color(0xFFCBD5E1), fontSize = 12.sp)
                                    Text("3. Open the top-left menu anytime to switch sources, decoders, or subtitles.", color = Color(0xFFCBD5E1), fontSize = 12.sp)
                                }
                                Button(
                                    onClick = { topImportPicker.launch(arrayOf("*/*")) },
                                    colors = ButtonDefaults.buttonColors(containerColor = AccentViolet),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Import rclone.conf File", color = Color(0xFF0A0E17), fontWeight = FontWeight.Bold)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    OutlinedButton(
                                        onClick = { showPasteDialog = true },
                                        shape = RoundedCornerShape(10.dp),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderPurple),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Paste Config", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                    OutlinedButton(
                                        onClick = { showWebDavDialog = true },
                                        shape = RoundedCornerShape(10.dp),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderPurple),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("WebDAV URL", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                    } else {
                        Text(
                            if (!errorMessage.isNullOrBlank()) "Connection Failed" else "No Media Found",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            errorMessage ?: "No supported video files were found in the selected source.",
                            color = if (!errorMessage.isNullOrBlank()) Color(0xFFFF8A80) else SecondaryText,
                            fontSize = 12.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = onRescan,
                                colors = ButtonDefaults.buttonColors(containerColor = AccentViolet),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Rescan", color = Color(0xFF0A0E17), fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = { topImportPicker.launch(arrayOf("*/*")) },
                                shape = RoundedCornerShape(10.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, BorderPurple)
                            ) {
                                Text("Import Config", color = Color.White, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        // Subtle top gradient scrim so floating Hamburger & Search buttons stay crisp over bright posters
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xCC0A0E17), Color.Transparent)
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .statusBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = Color(0x880A0E17),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.border(0.8.dp, BorderPurple, RoundedCornerShape(14.dp))
                ) {
                    IconButton(onClick = onOpenDrawer, modifier = Modifier.size(52.dp)) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "Menu",
                            tint = Color.White,
                            modifier = Modifier.size(27.dp)
                        )
                    }
                }

                Surface(
                    color = Color(0x880A0E17),
                    shape = CircleShape,
                    modifier = Modifier.border(0.8.dp, BorderPurple, CircleShape)
                ) {
                    IconButton(
                        onClick = {
                            isSearchActive = !isSearchActive
                            if (!isSearchActive) searchQuery = ""
                        },
                        modifier = Modifier.size(52.dp)
                    ) {
                        Icon(
                            imageVector = if (isSearchActive) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = "Search",
                            tint = Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = isSearchActive,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search title, series, anime...", color = SecondaryText, fontSize = 14.sp) },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = SurfaceDark,
                        unfocusedContainerColor = SurfaceDark,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = AccentViolet,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, BorderPurple, RoundedCornerShape(14.dp))
                )
            }
        }
    }
}
