package com.vidlab.app.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    ModalDrawerSheet(
        drawerContainerColor = DeepPurpleBg,
        drawerContentColor = Color.White,
        modifier = Modifier.width(310.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
        ) {
            Text(
                "VidLab",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Media Center & Storage Renamer",
                color = Color(0xFF9E95B8),
                fontSize = 12.sp
            )

            Spacer(modifier = Modifier.height(24.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            "Simkl Scrobbler",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            if (simklConnected) "Active & syncing" else "Disabled",
                            color = Color(0xFF9E95B8),
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = simklConnected,
                        onCheckedChange = onSimklToggle,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = AccentViolet
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                "LIBRARY & ORGANIZATION",
                color = Color(0xFF9E95B8),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(10.dp))

            DrawerActionButton(
                icon = Icons.Default.Refresh,
                title = "Rescan Library Catalog",
                subtitle = "Crawl WebDAV & update metadata",
                onClick = onRescanClick
            )

            Spacer(modifier = Modifier.height(10.dp))

            DrawerActionButton(
                icon = Icons.Default.DriveFileRenameOutline,
                title = "Run Storage Auto-Renamer",
                subtitle = "Organize files into 3-tier folders",
                onClick = onRunRenamerClick
            )

            Spacer(modifier = Modifier.weight(1f))

            HorizontalDivider(color = BorderPurple, thickness = 1.dp)
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Engine v2.4 • rclone WebDAV",
                    color = Color(0xFF9E95B8),
                    fontSize = 11.sp
                )
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF4CAF50))
                )
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
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(BorderPurple),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = AccentViolet,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = subtitle,
                    color = Color(0xFF9E95B8),
                    fontSize = 11.sp
                )
            }
        }
    }
}
