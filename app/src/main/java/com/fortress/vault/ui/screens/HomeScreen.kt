package com.fortress.vault.ui.screens

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.fortress.vault.core.MAX_SEAL_DURATION_DAYS
import com.fortress.vault.core.MIN_SEAL_DURATION_DAYS
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
    var detailsSealId by remember { mutableStateOf<String?>(null) }
    var addAppsTargetSealId by remember { mutableStateOf<String?>(null) }

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
                        onTap = { detailsSealId = seal.id },
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

    if (detailsSealId != null) {
        val detailsSeal = seals.firstOrNull { it.id == detailsSealId }
        if (detailsSeal != null) {
            SealDetailDialog(
                seal = detailsSeal,
                context = context,
                onDismiss = { detailsSealId = null },
                onAddApps = { addAppsTargetSealId = detailsSeal.id }
            )
        } else {
            detailsSealId = null
        }
    }

    val addAppsTarget = addAppsTargetSealId
    if (addAppsTarget != null) {
        AddAppsToSealDialog(
            sealId = addAppsTarget,
            seals = seals,
            context = context,
            onDismiss = { addAppsTargetSealId = null },
            onConfirm = { selected ->
                coroutineScope.launch {
                    VaultManager.addPackagesToSeal(context, addAppsTarget, selected)
                    seals = VaultManager.activeSeals(context)
                }
                addAppsTargetSealId = null
                detailsSealId = null
            }
        )
    }
}

@Composable
private fun SealCard(seal: Seal, onTap: () -> Unit, onExtend: () -> Unit, onEmergencyUnlock: () -> Unit) {
    val context = LocalContext.current
    var remainingLabel by remember(seal.id) { mutableStateOf(VaultManager.remainingLabelFor(context, seal)) }

    LaunchedEffect(seal.id) {
        while (true) {
            remainingLabel = VaultManager.remainingLabelFor(context, seal)
            delay(30_000)
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap),
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

private data class PackageEntry(val packageName: String, val label: String)

@Composable
private fun SealDetailDialog(
    seal: Seal,
    context: android.content.Context,
    onDismiss: () -> Unit,
    onAddApps: () -> Unit
) {
    val uniquePackages = remember(seal) { seal.packages.toList().distinct() }
    val apps = remember(uniquePackages) {
        uniquePackages.map { packageName ->
            val label = runCatching {
                val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
                context.packageManager.getApplicationLabel(appInfo).toString()
            }.getOrDefault(packageName)
            PackageEntry(packageName, label)
        }.sortedBy { it.label.lowercase() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Seal details") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "This seal is blocking ${seal.packages.size} app${if (seal.packages.size == 1) "" else "s"}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                    items(apps) { app ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val icon = remember(app.packageName) {
                                runCatching { context.packageManager.getApplicationIcon(app.packageName).toBitmap().asImageBitmap() }.getOrNull()
                            }
                            if (icon != null) {
                                Image(
                                    bitmap = icon,
                                    contentDescription = app.label,
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                )
                                Spacer(Modifier.width(10.dp))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(app.label, style = MaterialTheme.typography.bodyLarge)
                                Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onAddApps) { Text("Add apps", color = BrassPrimary) }
                TextButton(onClick = onDismiss) { Text("Close", color = BrassPrimary) }
            }
        }
    )
}

@Composable
private fun AddAppsToSealDialog(
    sealId: String,
    seals: List<Seal>,
    context: android.content.Context,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    val allApps = remember(sealId) { loadLaunchableApps(context) }
    val existingPackages = remember(sealId, seals) {
        seals.firstOrNull { it.id == sealId }?.packages.orEmpty()
    }
    val candidates = remember(sealId) { allApps.filter { it.packageName !in existingPackages } }
    val selected = remember { mutableStateOf(setOf<String>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add apps to this seal") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "This keeps the same emergency phrase and recovery key for this seal.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                if (candidates.isEmpty()) {
                    Text("No more apps are available to add to this seal.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(candidates) { app ->
                            val checked = selected.value.contains(app.packageName)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selected.value = if (checked) selected.value - app.packageName else selected.value + app.packageName }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = null,
                                    colors = CheckboxDefaults.colors(checkedColor = BrassPrimary)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(app.label, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected.value.isNotEmpty(),
                onClick = { onConfirm(selected.value) }
            ) {
                Text("Add selected", color = BrassPrimary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun AppIconRow(context: android.content.Context, packages: Set<String>) {
    val uniquePackages = packages.toList().distinct()
    Row {
        uniquePackages.take(8).forEach { pkg ->
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
        if (uniquePackages.size > 8) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Text("+${uniquePackages.size - 8}", style = MaterialTheme.typography.bodyMedium)
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
                    valueRange = MIN_SEAL_DURATION_DAYS.toFloat()..MAX_SEAL_DURATION_DAYS.toFloat(),
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
