package com.fortress.vault.ui.screens

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.fortress.vault.core.MAX_SEAL_DURATION_DAYS
import com.fortress.vault.core.MIN_SEAL_DURATION_DAYS
import com.fortress.vault.core.OnboardingPrefs
import com.fortress.vault.core.VaultManager
import com.fortress.vault.ui.theme.BrassPrimary
import com.fortress.vault.ui.theme.CharcoalSurface
import com.fortress.vault.ui.theme.EmberRed
import com.fortress.vault.ui.theme.SteelSurfaceHigh
import com.fortress.vault.ui.theme.TextPrimary
import com.fortress.vault.ui.theme.TextSecondary
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import kotlinx.coroutines.launch

data class InstalledApp(val label: String, val packageName: String)

@Composable
fun SealVaultScreen(onSealed: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var step by remember { mutableStateOf(SealStep.SELECT_APPS) }
    var selectedPackages by remember { mutableStateOf(setOf<String>()) }
    var durationDays by remember { mutableStateOf(30) }
    var recoveryPhrase by remember { mutableStateOf<String?>(null) }
    var pendingSeal by remember { mutableStateOf<com.fortress.vault.core.Seal?>(null) }
    var allowAdb by remember { mutableStateOf(false) }
    var blockUserSwitch by remember { mutableStateOf(false) }
    var hasWrittenDown by remember { mutableStateOf(false) }
    var isSealing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Whether to show the user-switch preference dialog.
    var showUserSwitchDialog by remember { mutableStateOf(false) }

    val installedApps = remember { loadLaunchableApps(context) }
    val sealByPackage = remember {
        VaultManager.activeSeals(context).flatMap { seal -> seal.packages.map { it to seal } }.toMap()
    }

    if (showUserSwitchDialog) {
        UserSwitchBlockDialog(
            onDecide = { block ->
                blockUserSwitch = block
                showUserSwitchDialog = false
                isSealing = true
                errorMessage = null
                coroutineScope.launch {
                    try {
                        val (seal, phrase) = VaultManager.prepareSeal(
                            context, selectedPackages, durationDays, allowAdb, block
                        )
                        recoveryPhrase = phrase
                        pendingSeal = seal
                        step = SealStep.SHOW_RECOVERY_PHRASE
                    } catch (e: IllegalArgumentException) {
                        errorMessage = e.message ?: "Couldn't create this seal."
                    } finally {
                        isSealing = false
                    }
                }
            },
            onDismiss = {
                showUserSwitchDialog = false
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
    ) {
        when (step) {
            SealStep.SELECT_APPS -> {
                Text("Choose What To Seal", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Apps already sealed elsewhere are shown locked — use \"Add time\" on their card from Home instead.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(installedApps) { app ->
                        val lockedBySeal = sealByPackage[app.packageName]
                        AppRow(
                            app = app,
                            checked = app.packageName in selectedPackages,
                            lockedRemainingLabel = lockedBySeal?.let { VaultManager.remainingLabelFor(context, it) },
                            onToggle = { checked ->
                                selectedPackages = if (checked) {
                                    selectedPackages + app.packageName
                                } else {
                                    selectedPackages - app.packageName
                                }
                            }
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    Button(
                        enabled = selectedPackages.isNotEmpty(),
                        onClick = { step = SealStep.SET_DURATION },
                        colors = ButtonDefaults.buttonColors(containerColor = BrassPrimary)
                    ) {
                        Text("Next (${selectedPackages.size})", color = MaterialTheme.colorScheme.background)
                    }
                }
            }

            SealStep.SET_DURATION -> {
                Text("For How Long?", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "This seal covers only the ${selectedPackages.size} app(s) you just picked. " +
                        "Any other seals you have running are untouched.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (VaultManager.isUsbDebuggingCurrentlyEnabled(context)) {
                    Spacer(Modifier.height(16.dp))
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(SteelSurfaceHigh),
                        colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                        border = androidx.compose.foundation.BorderStroke(1.dp, EmberRed.copy(alpha = 0.4f))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .background(EmberRed.copy(alpha = 0.15f), shape = RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Block,
                                        contentDescription = null,
                                        tint = EmberRed,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = "USB Debugging Detected",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextPrimary
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = "USB debugging is currently enabled. A connected computer can bypass Fortress restrictions or uninstall the Device Owner admin. Turn it off in Settings → Developer options for full protection.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                    }
                }

                Spacer(Modifier.height(32.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = allowAdb,
                        onCheckedChange = { allowAdb = it },
                        colors = CheckboxDefaults.colors(checkedColor = BrassPrimary)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("Allow USB debugging while sealed (unsafe)", style = MaterialTheme.typography.bodyMedium)
                        Text("If enabled, a computer authorized for USB debugging can remove the admin and uninstall Fortress.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("$durationDays days", style = MaterialTheme.typography.displayLarge, color = BrassPrimary)
                Slider(
                    value = durationDays.toFloat(),
                    onValueChange = { durationDays = it.toInt() },
                    valueRange = MIN_SEAL_DURATION_DAYS.toFloat()..MAX_SEAL_DURATION_DAYS.toFloat(),
                    colors = SliderDefaults.colors(thumbColor = BrassPrimary, activeTrackColor = BrassPrimary)
                )
                errorMessage?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, color = EmberRed)
                }
                Spacer(Modifier.weight(1f))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { step = SealStep.SELECT_APPS }) { Text("Back") }
                    Button(
                        enabled = !isSealing,
                        onClick = {
                            val shouldAsk = OnboardingPrefs.shouldAskAboutUserSwitch(context)
                            if (shouldAsk) {
                                // Show the dialog; it will trigger seal preparation on confirm.
                                showUserSwitchDialog = true
                            } else {
                                // An existing active seal is already blocking user switching.
                                isSealing = true
                                errorMessage = null
                                coroutineScope.launch {
                                    try {
                                        val (seal, phrase) = VaultManager.prepareSeal(
                                            context, selectedPackages, durationDays, allowAdb, false
                                        )
                                        recoveryPhrase = phrase
                                        pendingSeal = seal
                                        step = SealStep.SHOW_RECOVERY_PHRASE
                                    } catch (e: IllegalArgumentException) {
                                        errorMessage = e.message ?: "Couldn't create this seal."
                                    } finally {
                                        isSealing = false
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = EmberRed)
                    ) {
                        Text(if (isSealing) "Sealing..." else "Seal It", color = MaterialTheme.colorScheme.onError)
                    }
                }
            }

            SealStep.SHOW_RECOVERY_PHRASE -> {
                Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Text("Write This Down Now", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "This is shown only once. It's this seal's only way back in before time is up — " +
                            "each seal has its own phrase.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(20.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            recoveryPhrase ?: "",
                            modifier = Modifier.padding(20.dp),
                            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                            color = BrassPrimary
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = hasWrittenDown,
                            onCheckedChange = { hasWrittenDown = it },
                            colors = CheckboxDefaults.colors(checkedColor = BrassPrimary)
                        )
                        Text("I've written this down somewhere physical.")
                    }
                }
                Button(
                    enabled = hasWrittenDown && !isSealing,
                    onClick = {
                        isSealing = true
                        coroutineScope.launch {
                            try {
                                val seal = pendingSeal
                                if (seal != null) {
                                    VaultManager.commitSeal(context, seal)
                                }
                                onSealed()
                            } finally {
                                isSealing = false
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BrassPrimary),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Text(if (isSealing) "Sealing..." else "Done — Vault Sealed", color = MaterialTheme.colorScheme.background)
                }
            }
        }
    }
}

// ── User-switch block dialog ─────────────────────────────────────────────────

@Composable
private fun UserSwitchBlockDialog(
    onDecide: (block: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CharcoalSurface,
        shape = RoundedCornerShape(24.dp),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(EmberRed.copy(alpha = 0.15f), shape = RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Shield,
                        contentDescription = null,
                        tint = EmberRed,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(14.dp))
                Text(
                    text = "Security Policy",
                    style = MaterialTheme.typography.headlineSmall,
                    color = TextPrimary
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Choose how Fortress enforces protection across guest & secondary accounts:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                Spacer(Modifier.height(16.dp))

                // Option 1 Card: Block Switching
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(EmberRed.copy(alpha = 0.08f)),
                    colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Block,
                                contentDescription = null,
                                tint = EmberRed,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Block User Switching (Recommended)",
                                style = MaterialTheme.typography.titleSmall,
                                color = EmberRed
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Disables Guest Mode & secondary profiles while sealed. Prevents all bypasses since Android pauses background enforcement in Guest mode.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextPrimary.copy(alpha = 0.9f)
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Option 2 Card: Package Freeze Only
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(SteelSurfaceHigh),
                    colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Lock,
                                contentDescription = null,
                                tint = BrassPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Just Freeze Packages",
                                style = MaterialTheme.typography.titleSmall,
                                color = BrassPrimary
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Freezes apps in existing user accounts, but allows user switching. Note: Fresh Guest sessions may reinstall apps in their clean space.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Button(
                    onClick = { onDecide(true) },
                    colors = ButtonDefaults.buttonColors(containerColor = EmberRed),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text(
                        text = "Block User Switching",
                        color = TextPrimary,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { onDecide(false) },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = BrassPrimary),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BrassPrimary.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text(
                        text = "Just Freeze Packages",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        },
        dismissButton = null
    )
}

// ── Shared helpers ───────────────────────────────────────────────────────────

private enum class SealStep { SELECT_APPS, SET_DURATION, SHOW_RECOVERY_PHRASE }

@Composable
private fun AppRow(
    app: InstalledApp,
    checked: Boolean,
    lockedRemainingLabel: String?,
    onToggle: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val iconBitmap = remember(app.packageName) {
        runCatching {
            context.packageManager.getApplicationIcon(app.packageName).toBitmap().asImageBitmap()
        }.getOrNull()
    }
    val isLocked = lockedRemainingLabel != null

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isLocked) {
            Spacer(Modifier.width(48.dp)) // align with checkbox width, no checkbox shown
        } else {
            Checkbox(checked = checked, onCheckedChange = onToggle, colors = CheckboxDefaults.colors(checkedColor = BrassPrimary))
        }

        if (iconBitmap != null) {
            Image(
                bitmap = iconBitmap,
                contentDescription = app.label,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
            )
        } else {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        }
        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                app.label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isLocked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
            )
            Text(
                app.packageName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (lockedRemainingLabel != null) {
            AssistChip(
                onClick = {},
                enabled = false,
                label = { Text(lockedRemainingLabel) }
            )
        }
    }
}

fun loadLaunchableApps(context: android.content.Context): List<InstalledApp> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
    return pm.queryIntentActivities(intent, 0)
        .map { resolveInfo ->
            InstalledApp(
                label = resolveInfo.loadLabel(pm).toString(),
                packageName = resolveInfo.activityInfo.packageName
            )
        }
        .filter { it.packageName != context.packageName }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}
