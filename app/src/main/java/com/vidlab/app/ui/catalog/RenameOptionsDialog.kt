package com.vidlab.app.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.vidlab.app.data.model.ScrapedMediaItem
import com.vidlab.app.data.model.TmdbSearchResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RenameOptionsDialog(
    item: ScrapedMediaItem,
    searchResults: List<TmdbSearchResult>,
    onSearch: (query: String) -> Unit,
    onSelectMatch: (candidate: TmdbSearchResult) -> Unit,
    onManualApply: (title: String, year: String?) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(if (item.isSeries) 1 else 0) } // 0: Movie, 1: Show, 2: Manual
    var queryText by remember { mutableStateOf(item.title) }
    var manualTitle by remember { mutableStateOf(item.title) }
    var manualYear by remember { mutableStateOf(item.releaseYear ?: "") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF141120)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text(
                text = "Rename & Match Options",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Navigation Tabs: Movies / TV Shows / Manual
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color(0xFF1D182E),
                contentColor = Color(0xFF8B5CF6)
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = {
                        selectedTab = 0
                        onSearch(queryText)
                    },
                    text = { Text("Movie", color = if (selectedTab == 0) Color(0xFF8B5CF6) else Color(0xFF9E95B8)) },
                    icon = { Icon(Icons.Default.Movie, contentDescription = null, tint = if (selectedTab == 0) Color(0xFF8B5CF6) else Color(0xFF9E95B8)) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = {
                        selectedTab = 1
                        onSearch(queryText)
                    },
                    text = { Text("TV Show", color = if (selectedTab == 1) Color(0xFF8B5CF6) else Color(0xFF9E95B8)) },
                    icon = { Icon(Icons.Default.Tv, contentDescription = null, tint = if (selectedTab == 1) Color(0xFF8B5CF6) else Color(0xFF9E95B8)) }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("Manual", color = if (selectedTab == 2) Color(0xFF8B5CF6) else Color(0xFF9E95B8)) },
                    icon = { Icon(Icons.Default.Edit, contentDescription = null, tint = if (selectedTab == 2) Color(0xFF8B5CF6) else Color(0xFF9E95B8)) }
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (selectedTab == 0 || selectedTab == 1) {
                // Search Field
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = queryText,
                        onValueChange = { queryText = it },
                        placeholder = { Text(if (selectedTab == 0) "Search movie title..." else "Search show title...", color = Color.Gray) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF1B162B),
                            unfocusedContainerColor = Color(0xFF1B162B),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = { onSearch(queryText) },
                        modifier = Modifier.background(Color(0xFF8B5CF6), RoundedCornerShape(8.dp))
                    ) {
                        Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.White)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                val filterType = if (selectedTab == 0) "movie" else "tv"
                val filteredResults = searchResults.filter { it.mediaType.equals(filterType, ignoreCase = true) }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredResults) { res ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF1B162B))
                                .clickable { onSelectMatch(res) }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (res.posterUrl != null) {
                                AsyncImage(
                                    model = res.posterUrl,
                                    contentDescription = null,
                                    modifier = Modifier.size(45.dp, 65.dp).clip(RoundedCornerShape(4.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Box(modifier = Modifier.size(45.dp, 65.dp).background(Color(0xFF2C2442), RoundedCornerShape(4.dp)))
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(res.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text("${res.mediaType.uppercase()} • ${res.year ?: "Unknown"}", color = Color(0xFF9E95B8), fontSize = 11.sp)
                            }
                        }
                    }
                }
            } else {
                // Manual Renaming Mode
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text("Exact Title:", color = Color(0xFF9E95B8), fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    TextField(
                        value = manualTitle,
                        onValueChange = { manualTitle = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF1B162B),
                            unfocusedContainerColor = Color(0xFF1B162B),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("Release Year (e.g. 2026):", color = Color(0xFF9E95B8), fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    TextField(
                        value = manualYear,
                        onValueChange = { manualYear = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF1B162B),
                            unfocusedContainerColor = Color(0xFF1B162B),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { onManualApply(manualTitle.trim(), manualYear.trim().takeIf { it.isNotBlank() }) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text("Apply & Generate Operations", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
