package com.vidlab.app.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.vidlab.app.data.model.AmbiguousPrompt
import com.vidlab.app.data.model.TmdbSearchResult

@Composable
fun AmbiguousMatchDialog(
    prompt: AmbiguousPrompt,
    onSelectMatch: (TmdbSearchResult) -> Unit,
    onDismiss: () -> Unit
) {
    if (prompt.candidates.isEmpty()) {
        androidx.compose.runtime.LaunchedEffect(prompt) { onDismiss() }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Select Match: ${prompt.rawName}",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Multiple matches found. Select the correct title to rename and fetch artwork:",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(10.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                    items(prompt.candidates) { cand ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectMatch(cand) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (cand.posterUrl != null) {
                                AsyncImage(
                                    model = cand.posterUrl,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(40.dp, 58.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp, 58.dp)
                                        .background(Color(0xFF2C2442), RoundedCornerShape(4.dp))
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(cand.title, color = Color.White, fontSize = 14.sp)
                                Text(
                                    text = "${cand.mediaType.uppercase()} •${cand.year ?: ""}",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Skip", color = Color.Gray)
            }
        },
        containerColor = Color(0xFF131A29)
    )
}
