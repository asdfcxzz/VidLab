package com.vidlab.app.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vidlab.app.data.prefs.PlayerPreferences

private val DeepPurpleBg = Color(0xFF100D1C)
private val SurfaceDark = Color(0xFF181428)
private val BorderPurple = Color(0xFF26203B)
private val AccentViolet = Color(0xFF8B85CF)

@Composable
fun VidLabDrawerContent(
    simklConnected: Boolean,
    onSimklToggle: (Boolean) -> Unit,
    onRescanClick: () -> Unit,
    onRunRenamerClick: () -> Unit,
    onBrowseStorageClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val playerPrefs = remember { PlayerPreferences(context) }

    var defaultSpeed by remember { mutableFloatStateOf(playerPrefs.defaultSpeed) }
    var seekStep by remember { mutableIntStateOf(playerPrefs.seekStepSeconds) }
    var resizeMode by remember { mutableIntStateOf(playerPrefs.defaultResizeMode) }
    var autoEnglish by remember { mutableStateOf(playerPrefs.autoEnglishTracks) }
    var preferFfmpeg by remember { mutableStateOf(playerPrefs.preferFfmpegAudio) }

    val speedOptions = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    val seekOptions = listOf(5, 10, 15, 30)
    val aspectLabels = listOf("Fit", "Zoom", "Fill")

    ModalDrawerSheet(
        drawerContainerColor = DeepPurpleBg,
        drawerContentColor = Color.White,
        modifier = Modifier.width(320.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            Text("VidLab", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("Media Center & Storage Renamer", color = Color(0xFF9E95B8), fontSize = 12.sp)

            Spacer(modifier = Modifier.height(18.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Simkl Scrobbler", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(if (simklConnected) "Active & syncing" else "Disabled", color = Color(0xFF9E95B8), fontSize = 11.sp)
                    }
                    Switch(
                        checked = simklConnected,
                        onCheckedChange = onSimklToggle,
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AccentViolet)
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text("LIBRARY & ORGANIZATION", color = Color(0xFF9E95B8), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(modifier = Modifier.height(8.dp))

            DrawerActionButton(
                icon = Icons.Default.Refresh,
                title = "Rescan Library Catalog",
                subtitle = "Crawl WebDAV & update metadata",
                onClick = onRescanClick
            )
            Spacer(modifier = Modifier.height(8.dp))
            DrawerActionButton(
                icon = Icons.Default.DriveFileRenameOutline,
                title = "Run Storage Auto-Renamer",
                subtitle = "Organize files into 3-tier folders",
                onClick = onRunRenamerClick
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text("PLAYER SETTINGS", color = Color(0xFF9E95B8), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Default Speed Selector
                    Column {
                        Text("Default Playback Speed", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            speedOptions.forEach { spd ->
                                val selected = defaultSpeed == spd
                                Surface(
                                    color = if (selected) AccentViolet else BorderPurple,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.clickable {
                                        defaultSpeed = spd
                                        playerPrefs.defaultSpeed = spd
                                    }
                                ) {
                                    Text(
                                        text = "${spd}x",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Double-Tap Seek Interval
                    Column {
                        Text("Double-Tap Seek Step", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            seekOptions.forEach { sec ->
                                val selected = seekStep == sec
                                Surface(
                                    color = if (selected) AccentViolet else BorderPurple,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.clickable {
                                        seekStep = sec
                                        playerPrefs.seekStepSeconds = sec
                                    }
                                ) {
                                    Text(
                                        text = "${sec}s",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Default Aspect Ratio
                    Column {
                        Text("Default Aspect Ratio", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            aspectLabels.forEachIndexed { idx, label ->
                                val selected = resizeMode == idx
                                Surface(
                                    color = if (selected) AccentViolet else BorderPurple,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.clickable {
                                        resizeMode = idx
                                        playerPrefs.defaultResizeMode = idx
                                    }
                                ) {
                                    Text(
                                        text = label,
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = BorderPurple, thickness = 1.dp)

                    // Auto English Audio & Subs
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto English Audio & Subs", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text("Prefer EN tracks automatically", color = Color(0xFF9E95B8), fontSize = 11.sp)
                        }
                        Switch(
                            checked = autoEnglish,
                            onCheckedChange = {
                                autoEnglish = it
                                playerPrefs.autoEnglishTracks = it
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AccentViolet)
                        )
                    }

                    // Prioritize FFmpeg Audio Decoder
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Prioritize FFmpeg Decoder", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text("Force software decoding for EAC3/DTS", color = Color(0xFF9E95B8), fontSize = 11.sp)
                        }
                        Switch(
                            checked = preferFfmpeg,
                            onCheckedChange = {
                                preferFfmpeg = it
                                playerPrefs.preferFfmpegAudio = it
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AccentViolet)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            HorizontalDivider(color = BorderPurple, thickness = 1.dp)
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Engine v2.4 • rclone WebDAV", color = Color(0xFF9E95B8), fontSize = 11.sp)
                Box(modifier = Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF4CAF50)))
            }
        }
    }
}

@Composable
private fun DrawerActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = SurfaceDark,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(BorderPurple),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = AccentViolet, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(text = title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(text = subtitle, color = Color(0xFF9E95B8), fontSize = 11.sp)
            }
        }
    }
}
