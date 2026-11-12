package com.vidlab.app.ui.catalog

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.vidlab.app.data.remote.RcloneEngine
import com.vidlab.app.data.remote.RcloneRemoteConfig
import java.io.File
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vidlab.app.data.prefs.PlayerPreferences
import com.vidlab.app.data.remote.SimklManager
import com.vidlab.app.data.remote.SimklPinSession
import com.vidlab.app.data.remote.DownloadTaskManager
import com.vidlab.app.data.remote.DownloadStatus
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Close
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val DeepPurpleBg = Color(0xFF100D1C)
private val SurfaceDark = Color(0xFF131A29)
private val BorderPurple = Color(0xFF222E46)
private val AccentViolet = Color(0xFF8B85CF)

@Composable
fun VidLabDrawerContent(
    simklConnected: Boolean,
    onSimklToggle: (Boolean) -> Unit,
    onRescanClick: () -> Unit,
    onRunRenamerClick: () -> Unit,
    onBrowseStorageClick: () -> Unit = {},
    onRecentClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playerPrefs = remember { PlayerPreferences(context) }
    val simklManager = remember { SimklManager(context) }
    var useRclone by remember { mutableStateOf(RcloneEngine.isRcloneModeEnabled(context)) }
    var webDavUrl by remember { mutableStateOf(RcloneEngine.getWebDavBaseUrl(context)) }
    var rcloneRemotes by remember { mutableStateOf(RcloneEngine.getConfiguredRemotes(context)) }
    val drawerPrefs = remember { context.getSharedPreferences("vidlab_drawer_prefs", android.content.Context.MODE_PRIVATE) }
    var showRcloneRemotes by remember { mutableStateOf(drawerPrefs.getBoolean("show_rclone_remotes", true)) }
    var showPasteConfDialog by remember { mutableStateOf(false) }
    var pasteConfText by remember { mutableStateOf("") }

    val pickConfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()?.let { content ->
                RcloneEngine.importConfigText(context, content).onSuccess { list ->
                    rcloneRemotes = list
                    useRclone = true
                    Toast.makeText(context, "Imported ${list.size} rclone remote(s)", Toast.LENGTH_SHORT).show()
                }.onFailure { err ->
                    Toast.makeText(context, "Import failed: ${err.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

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
    var skipEnabled by remember { mutableStateOf(playerPrefs.skipSegmentsEnabled) }
    var autoSkip by remember { mutableStateOf(playerPrefs.autoSkipSegments) }

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
            Text("Media Center & Storage Renamer", color = Color(0xFF94A3B8), fontSize = 12.sp)

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
                                color = Color(0xFF94A3B8),
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
                                Text("Enter PIN on simkl.com/pin:", color = Color(0xFF94A3B8), fontSize = 11.sp)
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
                                        Text("Cancel", color = Color(0xFF94A3B8), fontSize = 11.sp)
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
                                color = Color(0xFF94A3B8),
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
                                    color = Color(0xFFCBD5E1),
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

            Text("MEDIA SOURCE MODE", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Distinct 2-Mode Switcher: Built-in Rclone vs WebDAV Mode
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF0A0E17))
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Surface(
                            color = if (useRclone) AccentViolet else BorderPurple,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    useRclone = true
                                    RcloneEngine.setRcloneModeEnabled(context, true)
                                    RcloneEngine.restartServeIfNeeded(context)
                                }
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 8.dp)) {
                                Text(
                                    "Rclone Mode",
                                    color = if (useRclone) Color(0xFF0A0E17) else Color(0xFF94A3B8),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Surface(
                            color = if (!useRclone) AccentViolet else BorderPurple,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    useRclone = false
                                    RcloneEngine.setRcloneModeEnabled(context, false)
                                    RcloneEngine.stopServer()
                                }
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 8.dp)) {
                                Text(
                                    "WebDAV Mode",
                                    color = if (!useRclone) Color(0xFF0A0E17) else Color(0xFF94A3B8),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    if (useRclone) {
                        Text(
                            "Embedded Rclone scans & streams your chosen remotes directly without Termux.",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = { pickConfLauncher.launch(arrayOf("*/*")) },
                                colors = ButtonDefaults.buttonColors(containerColor = AccentViolet),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Import rclone.conf", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = {
                                    val candidates = listOf(
                                        File("/storage/emulated/0/Download/rclone.conf"),
                                        File("/storage/emulated/0/rclone.conf")
                                    )
                                    val found = candidates.firstOrNull { it.exists() && it.canRead() }
                                    if (found != null) {
                                        RcloneEngine.importConfigText(context, found.readText()).onSuccess { list ->
                                            rcloneRemotes = list
                                            useRclone = true
                                            Toast.makeText(context, "Loaded ${list.size} remote(s)", Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        pasteConfText = if (RcloneEngine.getConfigFile(context).exists()) RcloneEngine.getConfigFile(context).readText() else ""
                                        showPasteConfDialog = true
                                    }
                                },
                                border = BorderStroke(1.dp, BorderPurple),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Paste / Auto-Find", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }

                        if (rcloneRemotes.isNotEmpty()) {
                            HorizontalDivider(color = BorderPurple, thickness = 1.dp)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showRcloneRemotes = !showRcloneRemotes
                                        drawerPrefs.edit().putBoolean("show_rclone_remotes", showRcloneRemotes).apply()
                                    }
                                    .padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val activeCount = rcloneRemotes.count { it.enabled }
                                Text(
                                    "Remotes ($activeCount/${rcloneRemotes.size} active)",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Surface(
                                    color = BorderPurple,
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        if (showRcloneRemotes) "Hide" else "Show",
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }
                            if (showRcloneRemotes) {
                            rcloneRemotes.forEachIndexed { idx, remote ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (remote.enabled) BorderPurple.copy(alpha = 0.55f) else Color.Transparent)
                                        .clickable {
                                            val updated = rcloneRemotes.toMutableList()
                                            updated[idx] = remote.copy(enabled = !remote.enabled)
                                            rcloneRemotes = updated
                                            RcloneEngine.saveConfiguredRemotes(context, updated)
                                            RcloneEngine.restartServeIfNeeded(context)
                                        }
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("${remote.name}:", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                        Text("type = ${remote.type}", color = Color(0xFF94A3B8), fontSize = 10.sp)
                                    }
                                    Checkbox(
                                        checked = remote.enabled,
                                        onCheckedChange = { checked ->
                                            val updated = rcloneRemotes.toMutableList()
                                            updated[idx] = remote.copy(enabled = checked)
                                            rcloneRemotes = updated
                                            RcloneEngine.saveConfiguredRemotes(context, updated)
                                            RcloneEngine.restartServeIfNeeded(context)
                                        },
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = AccentViolet,
                                            checkmarkColor = Color.White,
                                            uncheckedColor = Color(0xFF94A3B8)
                                        )
                                    )
                                }
                            }
                            }
                        } else {
                            Text(
                                "No rclone.conf imported yet. Import or paste your config above.",
                                color = Color(0xFF94A3B8),
                                fontSize = 11.sp
                            )
                        }
                    } else {
                        Text(
                            "Connect to an external WebDAV server (e.g., Termux rclone serve webdav or OpenList).",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )
                        OutlinedTextField(
                            value = webDavUrl,
                            onValueChange = {
                                webDavUrl = it
                                RcloneEngine.setWebDavBaseUrl(context, it)
                            },
                            singleLine = true,
                            label = { Text("WebDAV Server URL", color = Color(0xFF94A3B8), fontSize = 11.sp) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = AccentViolet,
                                unfocusedBorderColor = BorderPurple
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Button(
                        onClick = onRescanClick,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentViolet),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().height(40.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            if (useRclone) "Scan Selected Rclone Remotes" else "Scan WebDAV Server",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (showPasteConfDialog) {
                AlertDialog(
                    onDismissRequest = { showPasteConfDialog = false },
                    containerColor = SurfaceDark,
                    title = { Text("Paste rclone.conf", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                    text = {
                        OutlinedTextField(
                            value = pasteConfText,
                            onValueChange = { pasteConfText = it },
                            placeholder = { Text("[vault]\\ntype = combine\\n...", color = Color(0xFF94A3B8), fontSize = 12.sp) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = AccentViolet,
                                unfocusedBorderColor = BorderPurple
                            ),
                            modifier = Modifier.fillMaxWidth().height(200.dp)
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            RcloneEngine.importConfigText(context, pasteConfText).onSuccess { list ->
                                rcloneRemotes = list
                                useRclone = true
                                showPasteConfDialog = false
                                Toast.makeText(context, "Saved ${list.size} remote(s)", Toast.LENGTH_SHORT).show()
                            }.onFailure { err ->
                                Toast.makeText(context, err.message ?: "Invalid config", Toast.LENGTH_LONG).show()
                            }
                        }) {
                            Text("Save", color = AccentViolet, fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showPasteConfDialog = false }) {
                            Text("Cancel", color = Color(0xFF94A3B8))
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text("LIBRARY & ORGANIZATION", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(modifier = Modifier.height(8.dp))

            DrawerActionButton(
                icon = Icons.Default.PlayArrow,
                title = "Recent",
                subtitle = "Watch history & continue watching",
                onClick = onRecentClick
            )
            DrawerActionButton(
                icon = Icons.Default.Refresh,
                title = "Rescan Library Catalog",
                subtitle = "Scan chosen rclone remotes & update metadata",
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

            Text("PLAYER SETTINGS", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
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
                            Text("Prefer EN tracks automatically", color = Color(0xFF94A3B8), fontSize = 11.sp)
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
                            Text("Force software decoding for EAC3/DTS", color = Color(0xFF94A3B8), fontSize = 11.sp)
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
                            Text("Swipe vertically on right side for volume", color = Color(0xFF94A3B8), fontSize = 11.sp)
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
                            Text("Swipe vertically on left side for brightness", color = Color(0xFF94A3B8), fontSize = 11.sp)
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

            Text("SUBTITLE SETTINGS", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
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
                            Text("Improve readability on bright scenes", color = Color(0xFF94A3B8), fontSize = 11.sp)
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

            Spacer(modifier = Modifier.height(20.dp))

            Text("SKIP INTRO & OUTRO", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Show Skip Intro/Outro", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text("Fetch segments & show skip button", color = Color(0xFF94A3B8), fontSize = 11.sp)
                        }
                        Switch(
                            checked = skipEnabled,
                            onCheckedChange = {
                                skipEnabled = it
                                playerPrefs.skipSegmentsEnabled = it
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AccentViolet)
                        )
                    }

                    HorizontalDivider(color = BorderPurple, thickness = 1.dp)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto-Skip Intro & Outro", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text("Jump past segments automatically", color = Color(0xFF94A3B8), fontSize = 11.sp)
                        }
                        Switch(
                            checked = autoSkip,
                            enabled = skipEnabled,
                            onCheckedChange = {
                                autoSkip = it
                                playerPrefs.autoSkipSegments = it
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
                    skipEnabled = playerPrefs.skipSegmentsEnabled
                    autoSkip = playerPrefs.autoSkipSegments
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
            Spacer(modifier = Modifier.height(14.dp))

            val downloadTasks = DownloadTaskManager.tasks
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Download, contentDescription = null, tint = AccentViolet, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("TASK PROGRESS (${downloadTasks.size})", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                if (downloadTasks.any { it.status == DownloadStatus.COMPLETED || it.status == DownloadStatus.FAILED || it.status == DownloadStatus.CANCELLED }) {
                    Text("Clear", color = AccentViolet, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { DownloadTaskManager.clearFinished() })
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            if (downloadTasks.isEmpty()) {
                Surface(color = SurfaceDark, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "No active downloads. Long-press an episode or use the 3-dot menu to download.",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (task in downloadTasks) {
                        Surface(color = SurfaceDark, shape = RoundedCornerShape(12.dp), border = BorderStroke(0.8.dp, BorderPurple), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(task.fileName, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(task.folderName, color = Color(0xFF94A3B8), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (task.status == DownloadStatus.QUEUED || task.status == DownloadStatus.DOWNLOADING) {
                                        IconButton(onClick = { DownloadTaskManager.cancelTask(context, task.id) }, modifier = Modifier.size(26.dp)) {
                                            Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color(0xFF94A3B8), modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                                LinearProgressIndicator(
                                    progress = { task.progressFraction },
                                    color = when (task.status) {
                                        DownloadStatus.COMPLETED -> Color(0xFF4CAF50)
                                        DownloadStatus.FAILED -> Color(0xFFEF5350)
                                        else -> AccentViolet
                                    },
                                    trackColor = BorderPurple,
                                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                                )
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    val dlStr = String.format("%.1f MB", task.bytesDownloaded / (1024.0 * 1024.0))
                                    val totStr = if (task.totalBytes > 0L) String.format(" / %.1f MB", task.totalBytes / (1024.0 * 1024.0)) else ""
                                    Text("$dlStr$totStr", color = Color(0xFF94A3B8), fontSize = 10.sp)
                                    val statusLabel = when (task.status) {
                                        DownloadStatus.QUEUED -> "Queued"
                                        DownloadStatus.DOWNLOADING -> "${(task.progressFraction * 100).toInt()}%"
                                        DownloadStatus.COMPLETED -> "Completed"
                                        DownloadStatus.FAILED -> "Failed"
                                        DownloadStatus.CANCELLED -> "Cancelled"
                                    }
                                    Text(
                                        text = statusLabel,
                                        color = when (task.status) {
                                            DownloadStatus.COMPLETED -> Color(0xFF4CAF50)
                                            DownloadStatus.FAILED -> Color(0xFFEF5350)
                                            else -> AccentViolet
                                        },
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = BorderPurple, thickness = 1.dp)
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Engine v2.5 • Embedded Rclone", color = Color(0xFF94A3B8), fontSize = 11.sp)
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
                Text(text = subtitle, color = Color(0xFF94A3B8), fontSize = 11.sp)
            }
        }
    }
}
