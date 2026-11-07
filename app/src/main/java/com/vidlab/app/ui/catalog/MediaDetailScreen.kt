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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
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
private val SurfaceDarkPurple = Color(0xFF141120)
private val BorderPurple = Color(0xFF26203B)
private val AccentViolet = Color(0xFF8B5CF6)
private val SecondaryText = Color(0xFF9E95B8)

@Composable
fun MediaDetailScreen(
    item: ScrapedMediaItem,
    onBack: () -> Unit,
    onPlayFile: (MediaFile) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedSeasonIndex by remember { mutableIntStateOf(0) }
    val currentSeason = item.seasons.getOrNull(selectedSeasonIndex)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DeepPurpleBg)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 48.dp)
        ) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(350.dp)
                ) {
                    val bannerUrl = item.backdropUrl ?: item.posterUrl
                    if (!bannerUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = bannerUrl,
                            contentDescription = item.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Brush.verticalGradient(listOf(Color(0xFF201A33), DeepPurpleBg)))
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color(0x88000000), Color.Transparent, DeepPurpleBg),
                                    startY = 0f,
                                    endY = 950f
                                )
                            )
                    )

                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .statusBarsPadding()
                            .padding(start = 16.dp, top = 8.dp)
                            .size(36.dp)
                            .background(Color(0x77000000), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                ) {
                    Text(
                        text = item.title,
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item.metadata?.rating?.let { r ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(imageVector = Icons.Default.Star, contentDescription = "Rating", tint = Color(0xFFFFC107), modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(text = r, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        val year = item.releaseYear ?: item.metadata?.releaseYear
                        if (!year.isNullOrBlank()) {
                            Text(text = year, color = SecondaryText, fontSize = 12.sp)
                        }

                        if (item.isSeries && item.seasons.isNotEmpty()) {
                            Text(
                                text = "${item.seasons.size} Season${if (item.seasons.size > 1) "s" else ""} • ${item.totalEpisodesCount} Eps",
                                color = SecondaryText,
                                fontSize = 12.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            val epToPlay = currentSeason?.episodes?.firstOrNull()?.file ?: item.primaryFile
                            onPlayFile(epToPlay)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().height(46.dp)
                    ) {
                        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Play", tint = Color.Black, modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "Play", color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    val desc = item.metadata?.description
                    if (!desc.isNullOrBlank()) {
                        Text(text = "Overview", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = desc,
                            color = Color(0xFFD6D1E8),
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                    }
                }
            }

            if (item.isSeries && item.seasons.isNotEmpty()) {
                item {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if (item.seasons.size > 1) {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(bottom = 12.dp)
                            ) {
                                items(item.seasons.withIndex().toList()) { (index, s) ->
                                    val isSelected = selectedSeasonIndex == index
                                    Surface(
                                        color = if (isSelected) AccentViolet else SurfaceDarkPurple,
                                        shape = RoundedCornerShape(6.dp),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .border(1.dp, if (isSelected) AccentViolet else BorderPurple, RoundedCornerShape(6.dp))
                                            .clickable { selectedSeasonIndex = index }
                                    ) {
                                        Text(
                                            text = "Season ${s.seasonNumber}",
                                            color = if (isSelected) Color.White else SecondaryText,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }
                        } else {
                            Text(
                                text = "Episodes",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                            )
                        }

                        val episodes = currentSeason?.episodes ?: emptyList()
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(episodes) { ep ->
                                Column(
                                    modifier = Modifier.width(175.dp).clickable { onPlayFile(ep.file) }
                                ) {
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(100.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .border(0.5.dp, BorderPurple, RoundedCornerShape(8.dp)),
                                        colors = CardDefaults.cardColors(containerColor = SurfaceDarkPurple)
                                    ) {
                                        Box(modifier = Modifier.fillMaxSize()) {
                                            val thumbUrl = ep.thumbnail ?: item.backdropUrl ?: item.posterUrl
                                            if (!thumbUrl.isNullOrBlank()) {
                                                AsyncImage(
                                                    model = thumbUrl,
                                                    contentDescription = ep.title,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                            }
                                            Box(
                                                modifier = Modifier.fillMaxSize().background(Color(0x33000000)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(28.dp))
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "${ep.episodeNumber}. ${ep.title}",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
