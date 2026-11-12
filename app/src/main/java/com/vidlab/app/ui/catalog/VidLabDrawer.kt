package com.vidlab.app.ui.catalog

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
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
import com.vidlab.app.data.remote.SimklManager
import com.vidlab.app.data.remote.SimklPinSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    val scope = rememberCoroutineScope()
    val playerPrefs = remember { PlayerPreferences(context) }
    val simklManager = remember { SimklManager(context) }

    var isSignedIn by remember { mutableStateOf(simklManager.isSignedIn) }
    var simklUser by remember { mutableStateOf(simklManager.username ?: "Simkl Member") }
    var scrobbleActive by remember { mutableStateOf(simklManager.scrobbleEnabled) }
    var pinSession by remember { mutableStateOf<SimklPinSession?>(null) }
    var isRequestingPin by remember { mutableStateOf(false) }

    var defaultSpeed by remember { mutableFloatStateOf(playerPrefs.defaultSpeed) }
    var seekStep by remember { mutableIntStateOf(playerPrefs.seekStepSeconds) }
    var resizeMode by remember { mutableIntStateOf(playerPrefs.defaultResizeMode) }
    var autoEnglish by remember { mutableStateOf(playerPrefs.autoEnglishTracks) }
    var preferFfmpeg by remember { mutableStateOf(playerPrefs.preferFfmpegAudio) }
    var volGesture by remember { mutableStateOf(playerPrefs.volumeGestureEnabled) }
    var brightGesture by remember { mutableStateOf(playerPrefs.brightnessGestureEnabled) }

    var subSize by remember { mutableFloatStateOf(playerPrefs.subtitleFontSizeSp) }
    var subColorIdx by remember { mutableIntStateOf(playerPrefs.subtitleColorIndex) }
    var subBgBox by remember { mutableStateOf(playerPrefs.subtitleBgBox) }
    var subBottomPct by remember { mutableIntStateOf(playerPrefs.subtitleBottomPaddingPct) }

    val speedOptions = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    val seekOptions = listOf(5, 10, 15, 30)
    val aspectLabels = listOf("Fit", "Zoom", "Fill")
    val subSizeOptions = listOf(14f, 18f, 22f, 26f)
    val subColorLabels = listOf("White", "Yellow", "Cyan", "Green")
    val subMarginOptions = listOf(2, 4, 6, 10)

    LaunchedEffect(pinSession) {
        val activePin = pinSession ?: return@LaunchedEffect
        val stepMs = (activePin.interval.coerceAtLeast(5)) * 1000L
        repeat(60) {
            delay(stepMs)
            val verified = simklManager.pollPinStatus(activePin).getOrDefault(false)
            if (verified) {
                isSignedIn = true
                simklUser = simklManager.username ?: "Simkl Member"
                pinSession = null
                onSimklToggle(true)
                Toast.makeText(context, "Signed in to Simkl as $simklUser", Toast.LENGTH_SHORT).show()
                return@LaunchedEffect
            }
        }
    }

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
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Simkl Integration", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                if (isSignedIn) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(15.dp))
                                }
                            }
                            Text(
                                if (isSignedIn) "Signed in • $simklUser" else "Sign in to sync watch history",
                                color = Color(0xFF9E95B8),
                                fontSize = 11.sp
                            )
                        }
                        if (isSignedIn) {
                            Switch(
                                checked = scrobbleActive,
                                onCheckedChange = {
                                    scrobbleActive = it
                                    simklManager.scrobbleEnabled = it
                                    onSimklToggle(it)
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AccentViolet)
                            )
                        }
                    }

                    if (!isSignedIn) {
                        if (pinSession == null) {
                            Button(
                                onClick = {
                                    isRequestingPin = true
                                    scope.launch {
                                        val res = simklManager.requestPinCode()
                                        isRequestingPin = false
                                        res.onSuccess { pinSession = it }
                                        res.onFailure {
                                            Toast.makeText(context, "Simkl error: ${it.message ?: "Network error"}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                },
                                enabled = !isRequestingPin,
                                colors = ButtonDefaults.buttonColors(containerColor = AccentViolet),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth().height(38.dp)
                            ) {
                                Text(
                                    if (isRequestingPin) "Generating PIN..." else "Sign in with Simkl",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        } else {
                            val session = pinSession!!
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(BorderPurple)
                                    .padding(10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("Enter PIN on simkl.com/pin:", color = Color(0xFF9E95B8), fontSize = 11.sp)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(session.userCode, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = {
                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(session.verificationUrl))
                                            runCatching { context.startActivity(intent) }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = AccentViolet),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                    ) {
                                        Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Open Simkl", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                    TextButton(onClick = { pinSession = null }) {
                                        Text("Cancel", color = Color(0xFF9E95B8), fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    } else {
                        HorizontalDivider(color = BorderPurple, thickness = 1.dp)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (scrobbleActive) "Auto-scrobble at 80% ON" else "Auto-scrobble paused",
                                color = Color(0xFF9E95B8),
                                fontSize = 11.sp
                            )
                            Surface(
                                color = BorderPurple.copy(alpha = 0.75f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.clickable {
                                    simklManager.signOut()
                                    isSignedIn = false
                                    pinSession = null
                                    onSimklToggle(false)
                                }
                            ) {
                                Text(
                                    "Sign Out",
                                    color = Color(0xFFB5ADD1),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
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
                                    Text("${spd}x", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
                                }
                            }
                        }
                    }

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
                                    Text("${sec}s", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                                }
                            }
                        }
                    }

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
                                    Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = BorderPurple, thickness = 1.dp)

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

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Volume Swipe Gesture", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text("Swipe vertically on right side for volume", color = Color(0xFF9E95B8), fontSize = 11.sp)
                        }
                        Switch(
                            checked = volGesture,
                            onCheckedChange = {
                                volGesture = it
                                playerPrefs.volumeGestureEnabled = it
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AccentViolet)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Brightness Swipe Gesture", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text("Swipe vertically on left side for brightness", color = Color(0xFF9E95B8), fontSize = 11.sp)
                        }
                        Switch(
                            checked = brightGesture,
                            onCheckedChange = {
                                brightGesture = it
                                playerPrefs.brightnessGestureEnabled = it
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AccentViolet)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text("SUBTITLE SETTINGS", color = Color(0xFF9E95B8), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column {
                        Text("Subtitle Text Size", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            subSizeOptions.forEach { sz ->
                                val selected = subSize == sz
                                Surface(
                                    color = if (selected) AccentViolet else BorderPurple,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.clickable {
                                        subSize = sz
                                        playerPrefs.subtitleFontSizeSp = sz
                                    }
                                ) {
                                    Text("${sz.toInt()}sp", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
                                }
                            }
                        }
                    }

                    Column {
                        Text("Subtitle Color", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            subColorLabels.forEachIndexed { idx, label ->
                                val selected = subColorIdx == idx
                                Surface(
                                    color = if (selected) AccentViolet else BorderPurple,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.clickable {
                                        subColorIdx = idx
                                        playerPrefs.subtitleColorIndex = idx
                                    }
                                ) {
                                    Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
                                }
                            }
                        }
                    }

                    Column {
                        Text("Bottom Margin", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            subMarginOptions.forEach { pct ->
                                val selected = subBottomPct == pct
                                Surface(
                                    color = if (selected) AccentViolet else BorderPurple,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.clickable {
                                        subBottomPct = pct
                                        playerPrefs.subtitleBottomPaddingPct = pct
                                    }
                                ) {
                                    Text("${pct}%", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = BorderPurple, thickness = 1.dp)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Dark Background Box", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text("Improve readability on bright scenes", color = Color(0xFF9E95B8), fontSize = 11.sp)
                        }
                        Switch(
                            checked = subBgBox,
                            onCheckedChange = {
                                subBgBox = it
                                playerPrefs.subtitleBgBox = it
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AccentViolet)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            OutlinedButton(
                onClick = {
                    playerPrefs.resetToDefaults()
                    defaultSpeed = playerPrefs.defaultSpeed
                    seekStep = playerPrefs.seekStepSeconds
                    resizeMode = playerPrefs.defaultResizeMode
                    autoEnglish = playerPrefs.autoEnglishTracks
                    preferFfmpeg = playerPrefs.preferFfmpegAudio
                    volGesture = playerPrefs.volumeGestureEnabled
                    brightGesture = playerPrefs.brightnessGestureEnabled
                    subSize = playerPrefs.subtitleFontSizeSp
                    subColorIdx = playerPrefs.subtitleColorIndex
                    subBgBox = playerPrefs.subtitleBgBox
                    subBottomPct = playerPrefs.subtitleBottomPaddingPct
                    Toast.makeText(context, "All player & subtitle settings reset to default", Toast.LENGTH_SHORT).show()
                },
                border = BorderStroke(1.dp, BorderPurple),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(44.dp)
            ) {
                Icon(Icons.Default.RestartAlt, contentDescription = null, tint = AccentViolet, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Reset Settings to Default", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }

            Spacer(modifier = Modifier.height(16.dp))
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
