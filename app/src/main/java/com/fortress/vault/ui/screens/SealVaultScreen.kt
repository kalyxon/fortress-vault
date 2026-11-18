package com.fortress.vault.ui.screens

import android.content.Intent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.fortress.vault.core.MAX_APPS_PER_SEAL
import com.fortress.vault.core.MAX_SEAL_DURATION_HOURS
import com.fortress.vault.core.VaultManager
import com.fortress.vault.ui.theme.BrassPrimary
import com.fortress.vault.ui.theme.CharcoalSurface
import com.fortress.vault.ui.theme.EmberRed
import com.fortress.vault.ui.theme.SteelSurfaceHigh
import com.fortress.vault.ui.theme.TextPrimary
import com.fortress.vault.ui.theme.TextSecondary
import com.fortress.vault.ui.theme.VaultDivider
import com.fortress.vault.ui.components.PageHeader
import com.fortress.vault.ui.components.SectionLabel
import com.fortress.vault.ui.components.SettingsCard
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class InstalledApp(val label: String, val packageName: String)

@Composable
fun SealVaultScreen(onSealed: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var step by remember { mutableStateOf(SealStep.SELECT_APPS) }
    var selectedPackages by remember { mutableStateOf(setOf<String>()) }
    var websiteDomains by remember { mutableStateOf<Map<String, Set<String>>>(emptyMap()) }
    var durationAmount by remember { mutableIntStateOf(30) }
    var durationInDays by remember { mutableStateOf(true) }
    var recoveryPhrase by remember { mutableStateOf<String?>(null) }
    var pendingSeal by remember { mutableStateOf<com.fortress.vault.core.Seal?>(null) }
    var hasWrittenDown by remember { mutableStateOf(false) }
    var isSealing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var installedApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var isLoadingApps by remember { mutableStateOf(true) }

    // sealByPackage and hasSecureLock are computed on IO to avoid main-thread IPC/KeyguardManager calls
    var sealByPackage by remember { mutableStateOf<Map<String, com.fortress.vault.core.Seal>>(emptyMap()) }
    var hasSecureLock by remember { mutableStateOf(true) } // optimistic default until IO confirms
    val stepProgress by animateFloatAsState(
        targetValue = (step.ordinal + 1) / SealStep.entries.size.toFloat(),
        label = "seal-setup-progress"
    )

    LaunchedEffect(Unit) {
        // Load apps, sealed package map, and secure lock check all on IO
        val apps = withContext(kotlinx.coroutines.Dispatchers.IO) { loadLaunchableApps(context) }
        val sealMap = withContext(kotlinx.coroutines.Dispatchers.IO) {
            VaultManager.activeSeals(context).flatMap { seal -> seal.packages.map { it to seal } }.toMap()
        }
        val secureLock = withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.fortress.vault.core.DeviceSecurity.hasSecureLock(context)
        }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            installedApps = apps
            sealByPackage = sealMap
            hasSecureLock = secureLock
            isLoadingApps = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
    ) {
        val goBack = {
            when (step) {
                SealStep.SELECT_APPS -> onCancel()
                SealStep.SET_DURATION -> step = SealStep.SELECT_APPS
                SealStep.CONFIRM_SEAL -> step = SealStep.SET_DURATION
                SealStep.SHOW_RECOVERY_PHRASE -> step = SealStep.CONFIRM_SEAL
            }
        }
        BackHandler(enabled = true, onBack = goBack)
        PageHeader(title = "Create a seal", onBack = goBack)
        if (!hasSecureLock) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Phone Lock Required to Create Seal",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Text(
                    "Set a PIN, pattern or password before creating a seal.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                TextButton(
                    onClick = { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)) },
                    contentPadding = PaddingValues(horizontal = 0.dp)
                ) { Text("Set phone lock") }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                when (step) {
                    SealStep.SELECT_APPS -> "SELECT APPS"
                    SealStep.SET_DURATION -> "SET DURATION"
                    SealStep.CONFIRM_SEAL -> "REVIEW SEAL"
                    SealStep.SHOW_RECOVERY_PHRASE -> "RECOVERY PHRASE"
                },
                style = MaterialTheme.typography.labelMedium,
                color = BrassPrimary
            )
            Text(
                "${step.ordinal + 1} of ${SealStep.entries.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        LinearProgressIndicator(
            progress = { stepProgress },
            modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(3.dp)),
            color = BrassPrimary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
        Spacer(Modifier.height(20.dp))

        when (step) {
            SealStep.SELECT_APPS -> {
                Text("Choose what to seal", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Select up to $MAX_APPS_PER_SEAL apps. Apps already sealed elsewhere are shown locked — use \"Add time\" on their card from Home instead.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                if (isLoadingApps) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = BrassPrimary)
                    }
                } else {
                    SettingsCard(modifier = Modifier.weight(1f)) {
                        LazyColumn {
                            items(installedApps, key = { it.packageName }) { app ->
                            val lockedBySeal = sealByPackage[app.packageName]
                            val remainingLabel = remember(lockedBySeal) {
                                lockedBySeal?.let { VaultManager.remainingLabelFor(context, it) }
                            }
                                AppRow(
                                app = app,
                                checked = app.packageName in selectedPackages,
                                lockedRemainingLabel = remainingLabel,
                                onToggle = { checked ->
                                    selectedPackages = if (checked) {
                                        if (selectedPackages.size < MAX_APPS_PER_SEAL) {
                                            selectedPackages + app.packageName
                                        } else {
                                            selectedPackages
                                        }
                                    } else {
                                        websiteDomains = websiteDomains - app.packageName
                                        selectedPackages - app.packageName
                                    }
                                }
                                )
                                if (app != installedApps.last()) {
                                    HorizontalDivider(color = VaultDivider, modifier = Modifier.padding(start = 72.dp))
                                }
                            }
                        }
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
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                ) {
                    Text("For how long?", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "${selectedPackages.size} apps selected",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(24.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = !durationInDays,
                            onClick = {
                                durationInDays = false
                                durationAmount = durationAmount.coerceIn(1, 24)
                            },
                            label = { Text("Hours") }
                        )
                        FilterChip(
                            selected = durationInDays,
                            onClick = {
                                durationInDays = true
                                durationAmount = durationAmount.coerceIn(1, MAX_SEAL_DURATION_HOURS / 24)
                            },
                            label = { Text("Days") }
                        )
                    }
                    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Bottom
                            ) {
                                Text(
                                    "Duration",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "$durationAmount ${if (durationInDays) "days" else "hours"}",
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = BrassPrimary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Slider(
                                value = durationAmount.toFloat(),
                                onValueChange = { durationAmount = it.toInt().coerceAtLeast(1) },
                                valueRange = 1f..if (durationInDays) {
                                    (MAX_SEAL_DURATION_HOURS / 24).toFloat()
                                } else {
                                    24f
                                },
                                colors = SliderDefaults.colors(
                                    thumbColor = BrassPrimary,
                                    activeTrackColor = BrassPrimary
                                )
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("1 ${if (durationInDays) "day" else "hour"}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    "${if (durationInDays) MAX_SEAL_DURATION_HOURS / 24 else 24} ${if (durationInDays) "days" else "hours"}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    var showWebsiteInfo by remember { mutableStateOf(false) }
                    var showWebsiteEditor by remember { mutableStateOf(false) }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Optional website blocks",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Box {
                            IconButton(onClick = { showWebsiteInfo = true }) {
                                Icon(Icons.Filled.Info, contentDescription = "Website blocks information", tint = TextSecondary)
                            }
                            DropdownMenu(
                                expanded = showWebsiteInfo,
                                onDismissRequest = { showWebsiteInfo = false },
                                modifier = Modifier.widthIn(max = 300.dp)
                            ) {
                                Text(
                                    "Enter a domain and tap Add. The domain and subdomains will be blocked in managed Chrome after sealing.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextSecondary,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        }
                        IconButton(onClick = { showWebsiteEditor = !showWebsiteEditor }) {
                            Icon(
                                Icons.Filled.KeyboardArrowDown,
                                contentDescription = if (showWebsiteEditor) "Hide website blocks" else "Show website blocks",
                                tint = TextSecondary
                            )
                        }
                    }
                    if (showWebsiteEditor) {
                        SettingsCard(modifier = Modifier.padding(top = 8.dp)) {
                            WebsiteDomainsEditor(
                                apps = selectedPackages.map { packageName ->
                                    packageName to (installedApps.firstOrNull { it.packageName == packageName }?.label ?: packageName)
                                }.sortedBy { it.second.lowercase() },
                                websiteDomains = websiteDomains,
                                onDomainsChanged = { websiteDomains = it }
                            )
                        }
                    }
                    errorMessage?.let {
                        Spacer(Modifier.height(12.dp))
                        Text(it, color = EmberRed)
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { step = SealStep.SELECT_APPS }) { Text("Back") }
                    Button(
                        enabled = !isSealing &&
                            durationAmount in 1..if (durationInDays) MAX_SEAL_DURATION_HOURS / 24 else 24,
                        onClick = {
                            isSealing = true
                            errorMessage = null
                            coroutineScope.launch {
                                try {
                                    val (seal, phrase) = withContext(Dispatchers.IO) {
                                        VaultManager.prepareSeal(
                                            context,
                                            selectedPackages,
                                            durationAmount * if (durationInDays) 24 else 1,
                                            websiteDomains
                                        )
                                    }
                                    recoveryPhrase = phrase
                                    pendingSeal = seal
                                    step = SealStep.CONFIRM_SEAL
                                } catch (e: IllegalArgumentException) {
                                    errorMessage = e.message ?: "Couldn't create this seal."
                                } finally {
                                    isSealing = false
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = EmberRed)
                    ) {
                        Text(if (isSealing) "Sealing..." else "Seal It", color = MaterialTheme.colorScheme.onError)
                    }
                }
            }

            SealStep.CONFIRM_SEAL -> {
                val seal = pendingSeal
                val appLabels = selectedPackages
                    .map { packageName -> installedApps.firstOrNull { it.packageName == packageName }?.label ?: packageName }
                    .sorted()
                val unlockDate = seal?.let {
                    SimpleDateFormat("EEE, d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(it.unlockAtMillis))
                } ?: "Unknown"

                Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Text(
                        "Confirm seal ${sealShortId(seal?.id.orEmpty())}",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Spacer(Modifier.height(16.dp))
                    SettingsCard {
                        Column(Modifier.padding(16.dp)) {
                            Text("Protected apps", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                        appLabels.forEach { label ->
                            Text(label, color = TextPrimary, modifier = Modifier.padding(vertical = 6.dp))
                        }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    if (seal?.websiteDomains?.isNotEmpty() == true) {
                        SettingsCard {
                            Column(Modifier.padding(16.dp)) {
                                Text("Website rules", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(8.dp))
                                seal.websiteDomains.values.flatten().toSet().sorted().forEach { domain ->
                                    Text(domain, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                    SettingsCard {
                        Column(Modifier.padding(16.dp)) {
                            Text("Unlock date", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                            Spacer(Modifier.height(4.dp))
                            Text(unlockDate, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = { step = SealStep.SET_DURATION }) { Text("Back") }
                    Button(
                        onClick = { step = SealStep.SHOW_RECOVERY_PHRASE },
                        colors = ButtonDefaults.buttonColors(containerColor = EmberRed)
                    ) {
                        Text("Confirm Seal", color = MaterialTheme.colorScheme.onError)
                    }
                }
            }

            SealStep.SHOW_RECOVERY_PHRASE -> {
                Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    val sealId = pendingSeal?.id.orEmpty()
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.Lock, contentDescription = null, tint = TextPrimary)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text("SEAL ${sealShortId(sealId)}", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("Write this down now", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "This is shown only once. It's this seal's only way back in before time is up — " +
                            "each seal has its own phrase.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(20.dp))
                    SettingsCard {
                        Text(
                            recoveryPhrase ?: "",
                            modifier = Modifier.fillMaxWidth().padding(18.dp),
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 32.sp
                            ),
                            color = TextPrimary
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

                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
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
                            text = "Disables Guest Mode and secondary profiles while sealed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    HorizontalDivider(color = VaultDivider, modifier = Modifier.padding(top = 12.dp))
                }

                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
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
                            text = "Freezes apps in this user account; user switching remains available.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
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

private enum class SealStep { SELECT_APPS, SET_DURATION, CONFIRM_SEAL, SHOW_RECOVERY_PHRASE }

private fun sealShortId(sealId: String): String =
    sealId.replace("-", "").take(8).uppercase(Locale.getDefault())

@Composable
private fun AppRow(
    app: InstalledApp,
    checked: Boolean,
    lockedRemainingLabel: String?,
    onToggle: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val iconBitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, app.packageName) {
        value = com.fortress.vault.core.IconCache.getIconAsync(context, app.packageName)
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

        val currentIcon = iconBitmap
        if (currentIcon != null) {
            Image(
                bitmap = currentIcon,
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

object AppRepository {
    @Volatile private var cachedApps: List<InstalledApp>? = null
    private val lock = Any()

    fun getLaunchableApps(context: android.content.Context): List<InstalledApp> {
        cachedApps?.let { return it }
        synchronized(lock) {
            cachedApps?.let { return it }
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            val list = pm.queryIntentActivities(intent, 0)
                .map { resolveInfo ->
                    val label = resolveInfo.nonLocalizedLabel?.toString()
                        ?: runCatching { resolveInfo.loadLabel(pm).toString() }.getOrDefault(resolveInfo.activityInfo.packageName)
                    InstalledApp(
                        label = label,
                        packageName = resolveInfo.activityInfo.packageName
                    )
                }
                .filter { it.packageName != context.packageName }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }

            val topPackages = list.take(30).map { it.packageName }
            com.fortress.vault.core.IconCache.preloadIcons(context, topPackages)

            cachedApps = list
            return list
        }
    }

    fun invalidateCache() {
        synchronized(lock) {
            cachedApps = null
        }
    }
}

fun loadLaunchableApps(context: android.content.Context): List<InstalledApp> =
    AppRepository.getLaunchableApps(context)
