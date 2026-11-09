package com.vidlab.app.ui.catalog

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val BgDark = Color(0xFF09070F)
private val SurfaceDark = Color(0xFF141120)
private val BorderPurple = Color(0xFF26203B)
private val AccentViolet = Color(0xFF8B5CF6)
private val TextSecondary = Color(0xFF9E95B8)

@Composable
fun AutoRenameScreen(
    state: RenameProgressState,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier
) {
    val progress = if (state.totalCount > 0) {
        state.processedCount.toFloat() / state.totalCount.toFloat()
    } else 0f
    val animatedProgress by animateFloatAsState(targetValue = progress, label = "progress")

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BgDark)
            .statusBarsPadding()
            .padding(20.dp)
    ) {
        // App Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "VidLab Auto-Renamer",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Simkl-Powered Remote Media Organizer",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }

            IconButton(
                onClick = onRestart,
                enabled = !state.isRunning,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(SurfaceDark)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Restart",
                    tint = if (!state.isRunning) AccentViolet else Color.Gray
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Progress Card
        Surface(
            color = SurfaceDark,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(0.5.dp, BorderPurple, RoundedCornerShape(12.dp))
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = state.currentStep,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (state.totalCount > 0) {
                        Text(
                            text = "${state.processedCount}/${state.totalCount}",
                            color = AccentViolet,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (state.isRunning && state.totalCount == 0) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = AccentViolet,
                        trackColor = BorderPurple
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = AccentViolet,
                        trackColor = BorderPurple
                    )
                }

                if (state.currentFile.isNotBlank() && state.isRunning) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = state.currentFile,
                        color = TextSecondary,
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                }

                if (state.isCompleted) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Storage synchronization complete",
                            color = Color(0xFF4CAF50),
                            fontSize = 12.sp
                        )
                    }
                }

                state.error?.let { err ->
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Error: $err",
                        color = Color(0xFFEF5350),
                        fontSize = 12.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Activity Logs
        Text(
            text = "OPERATION LOGS",
            color = AccentViolet,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(SurfaceDark)
                .border(0.5.dp, BorderPurple, RoundedCornerShape(10.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(state.logs) { log ->
                Text(
                    text = log,
                    color = if (log.startsWith("✓")) Color(0xFF81C784) else if (log.startsWith("✗")) Color(0xFFE57373) else TextSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 16.sp
                )
            }
        }
    }
}
