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
import com.vidlab.app.data.model.MediaFeedSection
import com.vidlab.app.data.model.ScrapedMediaItem
import kotlinx.coroutines.delay

private val DeepPurpleBg = Color(0xFF0A0E17)
private val SurfaceDark = Color(0xFF131A29)
private val BorderPurple = Color(0xFF222E46)
private val AccentViolet = Color(0xFF8B85CF)
private val SecondaryText = Color(0xFF94A3B8)

@Composable
fun LastWatchedHero(
    item: ScrapedMediaItem,
    onWatchNow: (ScrapedMediaItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val bgUrl = item.backdropUrl ?: item.posterUrl

    Box(modifier = modifier.fillMaxWidth().height(510.dp)) {
        if (!bgUrl.isNullOrBlank()) {
            AsyncImage(
                model = bgUrl,
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
                        endY = 1100f
                    )
                )
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 28.dp, start = 20.dp, end = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = item.title,
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
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
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFFFFF)),
                shape = CircleShape,
                modifier = Modifier.width(185.dp).height(42.dp)
            ) {
                Text(text = "Continue watching", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
                    AsyncImage(
                        model = posterUrl,
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
    val progressStore = remember { com.vidlab.app.data.prefs.WatchProgressStore(context) }
    val progressRev = com.vidlab.app.data.prefs.WatchProgressStore.revision.intValue
    val heroItem = remember(sections, progressRev) {
        val allItems = sections.flatMap { it.items }.distinctBy { it.id }
        progressStore.getLastWatchedItem(allItems)
            ?: allItems.firstOrNull { !it.backdropUrl.isNullOrBlank() }
            ?: allItems.firstOrNull()
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
        LazyColumn(
            contentPadding = PaddingValues(
                top = if (isSearchActive) 136.dp else 0.dp,
                bottom = 32.dp
            ),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            if (!isSearchActive && heroItem != null) {
                item {
                    LastWatchedHero(
                        item = heroItem,
                        onWatchNow = { onSelectItem(it) }
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
                    Text(
                        if (!errorMessage.isNullOrBlank()) "Scan Error" else "No Media Loaded",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        errorMessage ?: "Open the menu to select Rclone remotes or WebDAV mode, then tap Rescan.",
                        color = if (!errorMessage.isNullOrBlank()) Color(0xFFFF8A80) else SecondaryText,
                        fontSize = 12.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Button(
                        onClick = onRescan,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentViolet),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Rescan Now", color = Color.White, fontWeight = FontWeight.Bold)
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
