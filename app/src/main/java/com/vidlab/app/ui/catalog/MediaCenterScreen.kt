package com.vidlab.app.ui.catalog

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import com.vidlab.app.data.model.MediaFile
import com.vidlab.app.data.model.ScrapedMediaItem

private val DeepPurpleBg = Color(0xFF09070F)
private val SurfaceDark = Color(0xFF141120)
private val BorderPurple = Color(0xFF26203B)
private val AccentViolet = Color(0xFF8B5CF6)
private val SecondaryText = Color(0xFF9E95B8)

@Composable
fun HeroBillboard(
    featuredItem: ScrapedMediaItem,
    onWatchNow: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(440.dp)
    ) {
        val bgUrl = featuredItem.backdropUrl ?: featuredItem.posterUrl
        if (!bgUrl.isNullOrBlank()) {
            AsyncImage(
                model = bgUrl,
                contentDescription = featuredItem.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0x8809070F), Color.Transparent, DeepPurpleBg),
                        startY = 0f,
                        endY = 1100f
                    )
                )
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp, start = 20.dp, end = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = featuredItem.title,
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(10.dp))

            val genres = featuredItem.metadata?.genres?.take(3) ?: emptyList()
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
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
            }

            Button(
                onClick = onWatchNow,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE8DEF8)),
                shape = CircleShape,
                modifier = Modifier
                    .width(160.dp)
                    .height(44.dp)
            ) {
                Text(
                    text = "Watch now",
                    color = Color.Black,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun MediaCenterCard(
    item: ScrapedMediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(124.dp)
            .clickable { onClick() }
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
                            .background(Brush.verticalGradient(listOf(Color(0xFF221A36), Color(0xFF100D1C)))),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = item.title.take(2).uppercase(),
                            color = AccentViolet.copy(alpha = 0.6f),
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold
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
    onOpenFileSource: () -> Unit,
    onRescan: () -> Unit,
    onPlayFile: (MediaFile) -> Unit,
    onSelectItem: (ScrapedMediaItem) -> Unit,
    modifier: Modifier = Modifier
) {
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    val featuredItem = remember(sections) {
        sections.flatMap { it.items }.firstOrNull { !it.backdropUrl.isNullOrBlank() }
            ?: sections.firstOrNull()?.items?.firstOrNull()
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

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Transparent)
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onOpenFileSource,
                        modifier = Modifier.size(38.dp).background(Color(0x5509070F), CircleShape)
                    ) {
                        Icon(imageVector = Icons.Default.Menu, contentDescription = "Source Menu", tint = Color.White, modifier = Modifier.size(22.dp))
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(
                            onClick = {
                                isSearchActive = !isSearchActive
                                if (!isSearchActive) searchQuery = ""
                            },
                            modifier = Modifier.size(38.dp).background(Color(0x5509070F), CircleShape)
                        ) {
                            Icon(imageVector = if (isSearchActive) Icons.Default.Close else Icons.Default.Search, contentDescription = "Search", tint = Color.White, modifier = Modifier.size(20.dp))
                        }

                        IconButton(
                            onClick = onRescan,
                            modifier = Modifier.size(38.dp).background(Color(0x5509070F), CircleShape)
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = AccentViolet)
                            } else {
                                Icon(imageVector = Icons.Default.Refresh, contentDescription = "Rescan", tint = Color.White, modifier = Modifier.size(18.dp))
                            }
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
                        placeholder = { Text("Search title, series, anime...", color = SecondaryText, fontSize = 13.sp) },
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
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.dp, BorderPurple, RoundedCornerShape(10.dp))
                    )
                }
            }
        },
        bottomBar = {
            Surface(
                color = Color(0xF013111C),
                shape = CircleShape,
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 10.dp)
                    .border(1.dp, BorderPurple, CircleShape)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(imageVector = Icons.Default.Home, contentDescription = "Home", tint = Color.White, modifier = Modifier.size(20.dp))
                        Text("Home", color = Color.White, fontSize = 9.sp)
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable { isSearchActive = true }
                    ) {
                        Icon(imageVector = Icons.Default.Search, contentDescription = "Search", tint = SecondaryText, modifier = Modifier.size(20.dp))
                        Text("Search", color = SecondaryText, fontSize = 9.sp)
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable { onOpenFileSource() }
                    ) {
                        Icon(imageVector = Icons.Default.Folder, contentDescription = "Library", tint = SecondaryText, modifier = Modifier.size(20.dp))
                        Text("Library", color = SecondaryText, fontSize = 9.sp)
                    }
                }
            }
        },
        containerColor = DeepPurpleBg,
        modifier = modifier
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = padding.calculateBottomPadding())
        ) {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                if (!isSearchActive && featuredItem != null) {
                    item {
                        HeroBillboard(
                            featuredItem = featuredItem,
                            onWatchNow = { onSelectItem(featuredItem) }
                        )
                    }
                }

                items(filteredSections) { section ->
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = section.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(section.items) { item ->
                                MediaCenterCard(item = item, onClick = { onSelectItem(item) })
                            }
                        }
                    }
                }
            }
        }
    }
}
