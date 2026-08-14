package com.fortress.vault.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.fortress.vault.core.Seal
import com.fortress.vault.core.VaultManager
import com.fortress.vault.ui.theme.BrassPrimary
import com.fortress.vault.ui.theme.CountdownStyle
import com.fortress.vault.ui.theme.EmberRed
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(onSealVault: () -> Unit, onEmergencyUnlock: (String) -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var seals by remember { mutableStateOf(VaultManager.activeSeals(context)) }
    var extendTargetSealId by remember { mutableStateOf<String?>(null) }

    // Local-only tick, no network call — TimeKeeper's estimate is derived
    // from the monotonic elapsed-realtime clock, so this refresh stays
    // correct even if the user just changed the device's date.
    LaunchedEffect(Unit) {
        while (true) {
            seals = VaultManager.activeSeals(context)
            delay(30_000)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onSealVault,
                containerColor = BrassPrimary,
                contentColor = MaterialTheme.colorScheme.background,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New Seal") }
            )
        }
    ) { padding ->
        if (seals.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(16.dp))
                Text("Nothing sealed right now.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp),
                contentPadding = PaddingValues(top = 20.dp, bottom = 96.dp)
            ) {
                items(seals, key = { it.id }) { seal ->
                    SealCard(
                        seal = seal,
                        onExtend = { extendTargetSealId = seal.id },
                        onEmergencyUnlock = { onEmergencyUnlock(seal.id) }
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }

    val extendTarget = extendTargetSealId
    if (extendTarget != null) {
        ExtendSealDialog(
            onDismiss = { extendTargetSealId = null },
            onConfirm = { extraDays ->
                coroutineScope.launch {
                    VaultManager.extendSeal(context, extendTarget, extraDays)
                    seals = VaultManager.activeSeals(context)
                }
                extendTargetSealId = null
            }
        )
    }
}

@Composable
private fun SealCard(seal: Seal, onExtend: () -> Unit, onEmergencyUnlock: () -> Unit) {
    val context = LocalContext.current
    var remainingLabel by remember(seal.id) { mutableStateOf(VaultManager.remainingLabelFor(context, seal)) }

    LaunchedEffect(seal.id) {
        while (true) {
            remainingLabel = VaultManager.remainingLabelFor(context, seal)
            delay(30_000)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(BrassPrimary.copy(alpha = 0.12f), shape = CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = BrassPrimary, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(remainingLabel, style = CountdownStyle.copy(fontSize = 28.sp), color = BrassPrimary)
                    Text(
                        "${seal.packages.size} app${if (seal.packages.size == 1) "" else "s"} sealed",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            AppIconRow(context, seal.packages)

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(onClick = onExtend) {
                    Text("Add time", color = BrassPrimary)
                }
                TextButton(onClick = onEmergencyUnlock) {
                    Text("Emergency unlock", color = EmberRed)
                }
            }
        }
    }
}

@Composable
private fun AppIconRow(context: android.content.Context, packages: Set<String>) {
    Row {
        packages.take(8).forEach { pkg ->
            val icon = remember(pkg) {
                runCatching { context.packageManager.getApplicationIcon(pkg).toBitmap().asImageBitmap() }.getOrNull()
            }
            if (icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = pkg,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
            }
        }
        if (packages.size > 8) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Text("+${packages.size - 8}", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ExtendSealDialog(onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var extraDays by remember { mutableStateOf(7) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add time to this seal") },
        text = {
            Column {
                Text("+$extraDays days", style = MaterialTheme.typography.headlineMedium, color = BrassPrimary)
                Slider(
                    value = extraDays.toFloat(),
                    onValueChange = { extraDays = it.toInt() },
                    valueRange = 1f..60f,
                    colors = SliderDefaults.colors(thumbColor = BrassPrimary, activeTrackColor = BrassPrimary)
                )
                Text(
                    "This only extends the countdown — the seal can't be shortened, only lengthened.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(extraDays) }) { Text("Add time", color = BrassPrimary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
