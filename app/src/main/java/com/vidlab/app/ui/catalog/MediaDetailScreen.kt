package com.vidlab.app.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.vidlab.app.data.model.MediaFile
import com.vidlab.app.data.model.ScrapedMediaItem

private val DeepPurpleBg = Color(0xFF09070F)
private val SurfaceDark = Color(0xFF141120)
private val BorderPurple = Color(0xFF26203B)
private val AccentViolet = Color(0xFF8B5CF6)
private val SecondaryText = Color(0xFF9E95B8)

@Composable
fun MediaDetailScreen(
    item: ScrapedMediaItem,
    onBack: () -> Unit,
    onPlayFile: (MediaFile) -> Unit,
    onRematch: (ScrapedMediaItem) -> Unit,
    onRenamePermanently: (ScrapedMediaItem) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedSeasonIndex by remember { mutableIntStateOf(0) }
    val currentSeason = item.seasons.getOrNull(selectedSeasonIndex)

    Box(modifier = modifier.fillMaxSize().background(DeepPurpleBg)) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                Box(modifier = Modifier.fillMaxWidth().height(380.dp)) {
                    val heroImg = item.backdropUrl ?: item.posterUrl
                    if (!heroImg.isNullOrBlank()) {
                        AsyncImage(
                            model = heroImg,
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
                                    colors = listOf(Color(0x5509070F), Color.Transparent, DeepPurpleBg),
                                    startY = 0f,
                                    endY = 1000f
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
                            onClick = onBack,
                            modifier = Modifier.size(40.dp).background(Color(0x66000000), CircleShape)
                        ) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }

                        IconButton(
                            onClick = { onRematch(item) },
                            modifier = Modifier.size(40.dp).background(Color(0x66000000), CircleShape)
                        ) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Fix Match", tint = Color.White)
                        }
                    }

                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = item.title,
                            color = Color.White,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        val metaSub = buildString {
                            item.releaseYear?.let { append(it) }
                            if (item.isSeries && item.seasons.isNotEmpty()) {
                                append(" • ${item.seasons.size} Seasons • ${item.totalEpisodesCount} Eps")
                            }
                        }
                        Text(text = metaSub, color = SecondaryText, fontSize = 12.sp)
                    }
                }
            }

            // Play & Permanent Rename Row
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { onPlayFile(item.primaryFile) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Play", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = { onRenamePermanently(item) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F1A30)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .border(1.dp, BorderPurple, RoundedCornerShape(8.dp))
                    ) {
                        Icon(Icons.Default.DriveFileRenameOutline, contentDescription = null, tint = AccentViolet)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Rename Files", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            if (item.isSeries && item.seasons.isNotEmpty()) {
                item {
                    Column(modifier = Modifier.padding(top = 12.dp)) {
                        Text(
                            text = "Seasons & Episodes",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 20.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(item.seasons.indices.toList()) { idx ->
                                val s = item.seasons[idx]
                                val isSelected = idx == selectedSeasonIndex
                                Surface(
                                    color = if (isSelected) AccentViolet else SurfaceDark,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .border(0.5.dp, BorderPurple, RoundedCornerShape(8.dp))
                                        .clickable { selectedSeasonIndex = idx }
                                ) {
                                    Text(
                                        text = "Season ${s.seasonNumber}",
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                currentSeason?.episodes?.let { eps ->
                    items(eps) { ep ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPlayFile(ep.file) }
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(110.dp)
                                    .height(65.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(SurfaceDark)
                            ) {
                                if (!ep.thumbnail.isNullOrBlank()) {
                                    AsyncImage(
                                        model = ep.thumbnail,
                                        contentDescription = ep.title,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color(0x33000000)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                                }
                            }

                            Spacer(modifier = Modifier.width(14.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = ep.title,
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Season ${ep.seasonNumber} • Episode ${ep.episodeNumber}",
                                    color = SecondaryText,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
