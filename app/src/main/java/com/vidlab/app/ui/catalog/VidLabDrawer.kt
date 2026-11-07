package com.vidlab.app.ui.catalog

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun VidLabDrawerContent(
    simklConnected: Boolean,
    onSimklToggle: (Boolean) -> Unit,
    onRescanClick: () -> Unit,
    onBrowseStorageClick: () -> Unit
) {
    ModalDrawerSheet(
        drawerContainerColor = Color(0xFF100D1C),
        modifier = Modifier.width(300.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            Text("VidLab", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Media Center", color = Color(0xFF9E95B8), fontSize = 12.sp)

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider(color = Color(0xFF26203B))
            Spacer(modifier = Modifier.height(20.dp))

            Text("INTEGRATIONS", color = Color(0xFF8B5CF6), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(10.dp))

            Surface(
                color = Color(0xFF171324),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(0.5.dp, Color(0xFF26203B), RoundedCornerShape(10.dp))
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Simkl Scrobbler", color = Color.White, fontSize = 14.sp)
                        Text(
                            text = if (simklConnected) "Active" else "Disabled",
                            color = if (simklConnected) Color(0xFF4CAF50) else Color(0xFF9E95B8),
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = simklConnected,
                        onCheckedChange = onSimklToggle,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF8B5CF6)
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            NavigationDrawerItem(
                label = { Text("Rescan WebDAV Library", color = Color.White) },
                icon = { Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.White) },
                selected = false,
                onClick = onRescanClick,
                colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent)
            )

            NavigationDrawerItem(
                label = { Text("Browse File Storage", color = Color.White) },
                icon = { Icon(Icons.Default.Folder, contentDescription = null, tint = Color.White) },
                selected = false,
                onClick = onBrowseStorageClick,
                colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent)
            )
        }
    }
}
