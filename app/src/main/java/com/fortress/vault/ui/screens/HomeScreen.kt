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
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Shield
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@Composable
fun HomeScreen(onSealVault: () -> Unit, onEmergencyUnlock: (String) -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var seals by remember { mutableStateOf(VaultManager.activeSeals(context)) }
    var extendTargetSealId by remember { mutableStateOf<String?>(null) }
    var detailsSealId by remember { mutableStateOf<String?>(null) }
    var addAppsTargetSealId by remember { mutableStateOf<String?>(null) }
    var pendingAddedApps by remember { mutableStateOf<Pair<String, Set<String>>?>(null) }
    var pendingExtension by remember { mutableStateOf<Pair<String, Int>?>(null) }

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
                pendingExtension = extendTarget to extraDays
                extendTargetSealId = null
            }
        )
    }

    val extension = pendingExtension
    if (extension != null) {
        val seal = seals.firstOrNull { it.id == extension.first }
        if (seal != null) {
            ExtendSealConfirmationDialog(
                seal = seal,
                extraDays = extension.second,
                onDismiss = { pendingExtension = null },
                onConfirm = {
                    coroutineScope.launch {
                        VaultManager.extendSeal(context, seal.id, extension.second)
                        seals = VaultManager.activeSeals(context)
                    }
                    pendingExtension = null
                }
            )
        }
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
                pendingAddedApps = addAppsTarget to selected
                addAppsTargetSealId = null
                detailsSealId = null
            }
        )
    }

    val addedApps = pendingAddedApps
    if (addedApps != null) {
        val seal = seals.firstOrNull { it.id == addedApps.first }
        if (seal != null) {
            AddAppsConfirmationDialog(
                seal = seal,
                packageNames = addedApps.second,
                context = context,
                onDismiss = { pendingAddedApps = null },
                onConfirm = {
                    coroutineScope.launch {
                        VaultManager.addPackagesToSeal(context, seal.id, addedApps.second)
                        seals = VaultManager.activeSeals(context)
                    }
                    pendingAddedApps = null
                }
            )
        }
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

                // ── Security badges ──────────────────────────────────────────
                Spacer(Modifier.height(12.dp))
                // USB Debugging badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (seal.allowAdb) EmberRed.copy(alpha = 0.10f)
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = if (seal.allowAdb) Icons.Filled.Warning else Icons.Filled.Shield,
                        contentDescription = null,
                        tint = if (seal.allowAdb) EmberRed else BrassPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            if (seal.allowAdb) "USB debugging: allowed" else "USB debugging: blocked",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (seal.allowAdb) EmberRed else BrassPrimary
                        )
                        Text(
                            if (seal.allowAdb)
                                "⚠ A connected computer can bypass this seal."
                            else
                                "ADB access is restricted for the duration.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                // User-switching badge
                val isSystemSwitchBlocked = remember(seal) {
                    VaultManager.activeSeals(context).any { it.blockUserSwitch }
                }
                val isBlockedForThisSealOrActive = seal.blockUserSwitch || isSystemSwitchBlocked

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (isBlockedForThisSealOrActive) MaterialTheme.colorScheme.surfaceVariant
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = if (isBlockedForThisSealOrActive) Icons.Filled.Block else Icons.Filled.Shield,
                        contentDescription = null,
                        tint = if (isBlockedForThisSealOrActive) BrassPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            if (isBlockedForThisSealOrActive) "User switching: blocked" else "User switching: allowed",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isBlockedForThisSealOrActive) BrassPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            if (seal.blockUserSwitch)
                                "Switching to Guest or secondary profiles is disabled by this seal."
                            else if (isSystemSwitchBlocked)
                                "Switching is currently blocked on this device by another active seal."
                            else
                                "Apps are frozen in all accounts, but switching is allowed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // ─────────────────────────────────────────────────────────────

                Spacer(Modifier.height(12.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
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
private fun AddAppsConfirmationDialog(
    seal: Seal,
    packageNames: Set<String>,
    context: android.content.Context,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val labels = packageNames.map { packageName ->
        runCatching {
            context.packageManager.getApplicationLabel(
                context.packageManager.getApplicationInfo(packageName, 0)
            ).toString()
        }.getOrDefault(packageName)
    }.sorted()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Confirm apps to add") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("These apps will be added to the existing seal and blocked until the current unlock date:")
                Spacer(Modifier.height(12.dp))
                labels.forEach { label ->
                    Text("- $label", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(4.dp))
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Unlock date: ${formatSealDate(seal.unlockAtMillis)}",
                    style = MaterialTheme.typography.titleMedium,
                    color = BrassPrimary
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Confirm and add", color = BrassPrimary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Back") }
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

@Composable
private fun ExtendSealConfirmationDialog(
    seal: Seal,
    extraDays: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val newUnlockAt = seal.unlockAtMillis + TimeUnit.DAYS.toMillis(extraDays.toLong())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Confirm added time") },
        text = {
            Column {
                Text("Add $extraDays day${if (extraDays == 1) "" else "s"} to this seal?")
                Spacer(Modifier.height(12.dp))
                Text("Current unlock date: ${formatSealDate(seal.unlockAtMillis)}")
                Spacer(Modifier.height(4.dp))
                Text(
                    "New unlock date: ${formatSealDate(newUnlockAt)}",
                    style = MaterialTheme.typography.titleMedium,
                    color = BrassPrimary
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Confirm added time", color = BrassPrimary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Back") }
        }
    )
}

private fun formatSealDate(epochMillis: Long): String =
    SimpleDateFormat("EEE, d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(epochMillis))
