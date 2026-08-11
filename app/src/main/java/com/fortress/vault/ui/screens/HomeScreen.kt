package com.fortress.vault.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.fortress.vault.core.VaultManager
import com.fortress.vault.ui.theme.BrassPrimary
import com.fortress.vault.ui.theme.CountdownStyle
import com.fortress.vault.ui.theme.EmberRed
import com.fortress.vault.ui.theme.MossUnlocked
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(onSealVault: () -> Unit, onEmergencyUnlock: () -> Unit) {
    val context = LocalContext.current
    var sealed by remember { mutableStateOf(VaultManager.isSealed(context)) }
    var remainingLabel by remember { mutableStateOf(VaultManager.remainingTimeLabel(context)) }
    val blockedApps = remember(sealed) { VaultManager.blockedPackages(context) }

    // Light client-side refresh; the real enforcement loop lives in SentinelService.
    LaunchedEffect(Unit) {
        while (true) {
            sealed = VaultManager.isSealed(context)
            remainingLabel = VaultManager.remainingTimeLabel(context)
            delay(60_000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))
        StatusBadge(sealed = sealed)
        Spacer(Modifier.height(24.dp))

        if (sealed) {
            Text(
                remainingLabel,
                style = CountdownStyle,
                color = BrassPrimary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "remaining until unlock",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(32.dp))
            BlockedAppsCard(blockedApps)

            Spacer(Modifier.weight(1f))
            TextButton(onClick = onEmergencyUnlock) {
                Text("Emergency Unlock", color = EmberRed)
            }
        } else {
            Text(
                "No apps are currently sealed.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.weight(1f))
            Button(
                onClick = onSealVault,
                colors = ButtonDefaults.buttonColors(containerColor = BrassPrimary),
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text("Seal The Vault", color = MaterialTheme.colorScheme.background, style = MaterialTheme.typography.titleMedium)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StatusBadge(sealed: Boolean) {
    val color = if (sealed) BrassPrimary else MossUnlocked
    val icon = if (sealed) Icons.Filled.Lock else Icons.Filled.LockOpen
    val label = if (sealed) "SEALED" else "OPEN"

    Box(
        modifier = Modifier
            .size(120.dp)
            .background(color.copy(alpha = 0.12f), shape = CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = color)
        }
    }
}

@Composable
private fun BlockedAppsCard(packages: Set<String>) {
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "FROZEN",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            if (packages.isEmpty()) {
                Text("No apps recorded.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                packages.forEach { pkg ->
                    FrozenAppRow(context, pkg)
                }
            }
        }
    }
}

@Composable
private fun FrozenAppRow(context: android.content.Context, packageName: String) {
    // A frozen app is hidden (setApplicationHidden), so PackageManager may
    // throw NameNotFoundException resolving its label/icon depending on OS
    // version — fall back to the raw package name rather than crash the row.
    val (label, iconBitmap) = remember(packageName) {
        val pm = context.packageManager
        val resolvedLabel = runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)
        val icon = runCatching {
            pm.getApplicationIcon(packageName).toBitmap().asImageBitmap()
        }.getOrNull()
        resolvedLabel to icon
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (iconBitmap != null) {
            Image(
                bitmap = iconBitmap,
                contentDescription = label,
                modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp))
            )
        } else {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
