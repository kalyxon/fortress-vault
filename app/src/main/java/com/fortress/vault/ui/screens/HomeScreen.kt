package com.fortress.vault.ui.screens

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@Composable
fun HomeScreen(onSealVault: () -> Unit, onEmergencyUnlock: (String) -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var seals by remember { mutableStateOf(VaultManager.activeSeals(context)) }
    var extendTargetSealId by remember { mutableStateOf<String?>(null) }
    var detailsSealId by remember { mutableStateOf<String?>(null) }
    var websiteRulesTargetSealId by remember { mutableStateOf<String?>(null) }
    var addAppsTargetSealId by remember { mutableStateOf<String?>(null) }
    var pendingAddedApps by remember { mutableStateOf<Pair<String, Set<String>>?>(null) }
    var pendingExtension by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var sealActionError by remember { mutableStateOf<String?>(null) }

    // Single shared tick — refreshes seal list and countdown labels every 30s.
    // All SealCards share this tick instead of each running their own LaunchedEffect loop.
    var tick by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            // Reload seals in background — activeSeals() reads from in-memory cache, no disk I/O
            val fresh = withContext(Dispatchers.IO) { VaultManager.activeSeals(context) }
            seals = fresh
            tick = System.currentTimeMillis()
        }
    }

    val detailsSeal = seals.firstOrNull { it.id == detailsSealId }
    BackHandler(enabled = detailsSeal != null) { detailsSealId = null }

    if (detailsSeal != null) {
        SealDetailsScreen(
            seal = detailsSeal,
            context = context,
            onDismiss = { detailsSealId = null },
            onAddApps = { addAppsTargetSealId = detailsSeal.id },
            onEditWebsites = {
                websiteRulesTargetSealId = detailsSeal.id
                detailsSealId = null
            }
        )
    } else {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Device controls") }
                }
            },
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
                            tick = tick,
                            onTap = { detailsSealId = seal.id },
                            onExtend = { extendTargetSealId = seal.id },
                            onEmergencyUnlock = { onEmergencyUnlock(seal.id) }
                        )
                        Spacer(Modifier.height(16.dp))
                    }
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

    if (detailsSealId != null && detailsSeal == null) {
        detailsSealId = null
    }

    val websiteRulesSeal = seals.firstOrNull { it.id == websiteRulesTargetSealId }
    if (websiteRulesSeal != null) {
        WebsiteDomainsDialog(
            seal = websiteRulesSeal,
            context = context,
            onDismiss = { websiteRulesTargetSealId = null },
            onSave = { domains, onComplete ->
                coroutineScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            VaultManager.updateSealWebsiteDomains(context, websiteRulesSeal.id, domains)
                        }
                        seals = withContext(Dispatchers.IO) { VaultManager.activeSeals(context) }
                        onComplete(null)
                    } catch (error: Exception) {
                        seals = withContext(Dispatchers.IO) { VaultManager.activeSeals(context) }
                        onComplete(error.message ?: "Chrome could not apply the website rules.")
                    }
                }
            }
        )
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
                        try {
                            VaultManager.addPackagesToSeal(context, seal.id, addedApps.second)
                            seals = VaultManager.activeSeals(context)
                        } catch (error: Exception) {
                            sealActionError = error.message ?: "Fortress could not add those apps to the seal."
                        } finally {
                            pendingAddedApps = null
                        }
                    }
                }
            )
        }
    }

    sealActionError?.let { message ->
        AlertDialog(
            onDismissRequest = { sealActionError = null },
            title = { Text("Apps were not added") },
            text = { Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            confirmButton = {
                TextButton(onClick = { sealActionError = null }) { Text("OK") }
            }
        )
    }
}

@Composable
private fun SealCard(seal: Seal, tick: Long, onTap: () -> Unit, onExtend: () -> Unit, onEmergencyUnlock: () -> Unit) {
    val context = LocalContext.current
    // Recompute label when tick changes — driven by single shared 30s loop in HomeScreen,
    // not per-card. This eliminates N parallel LaunchedEffect loops for N seal cards.
    val remainingLabel = remember(seal.id, tick) {
        VaultManager.remainingLabelFor(context, seal)
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
private fun SealDetailsScreen(
    seal: Seal,
    context: android.content.Context,
    onDismiss: () -> Unit,
    onAddApps: () -> Unit,
    onEditWebsites: () -> Unit
) {
    val uniquePackages = remember(seal) { seal.packages.toList().distinct() }
    var detailTick by remember(seal.id) { mutableLongStateOf(0L) }
    LaunchedEffect(seal.id) {
        while (true) {
            delay(60_000)
            detailTick = System.currentTimeMillis()
        }
    }
    val remainingLabel = remember(seal.id, detailTick) {
        VaultManager.remainingLabelFor(context, seal)
    }

    val apps by produceState<List<PackageEntry>>(initialValue = emptyList(), uniquePackages) {
        value = withContext(Dispatchers.IO) {
            uniquePackages.map { packageName ->
                val label = runCatching {
                    val appInfo = context.packageManager.getApplicationInfo(
                        packageName,
                        android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES
                    )
                    context.packageManager.getApplicationLabel(appInfo).toString()
                }.getOrElse { packageName.toAppDisplayName() }
                PackageEntry(packageName, label)
            }.sortedBy { it.label.lowercase() }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to seals")
                }
                Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(
                        "SEAL DETAILS",
                        style = MaterialTheme.typography.labelLarge,
                        color = BrassPrimary
                    )
                    Text(
                        "Your focus is protected",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    Icons.Filled.Shield,
                    contentDescription = null,
                    tint = BrassPrimary,
                    modifier = Modifier.padding(end = 12.dp).size(24.dp)
                )
            }
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background, shadowElevation = 12.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onEditWebsites,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = BrassPrimary),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BrassPrimary.copy(alpha = 0.55f))
                    ) {
                        Icon(Icons.Filled.Language, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Websites")
                    }
                    Button(
                        onClick = onAddApps,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BrassPrimary,
                            contentColor = MaterialTheme.colorScheme.background
                        )
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Add apps")
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(26.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(22.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .background(BrassPrimary.copy(alpha = 0.12f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Filled.Lock, contentDescription = null, tint = BrassPrimary, modifier = Modifier.size(20.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    "ACTIVE SEAL",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = BrassPrimary
                                )
                                Text(
                                    "${seal.packages.size} protected app${if (seal.packages.size == 1) "" else "s"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                        Text(
                            remainingLabel,
                            style = CountdownStyle.copy(fontSize = 42.sp, letterSpacing = 0.5.sp),
                            color = BrassPrimary
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "REMAINING",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(20.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f))
                        Spacer(Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.CalendarMonth,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Unlocks automatically",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    formatSealDate(seal.unlockAtMillis),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("PROTECTED APPS", style = MaterialTheme.typography.labelLarge, color = BrassPrimary)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Unavailable until the seal ends",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "${seal.packages.size}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (apps.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = BrassPrimary, strokeWidth = 2.dp)
                    }
                }
            } else {
                items(apps, key = { it.packageName }) { app ->
                    val icon by produceState<androidx.compose.ui.graphics.ImageBitmap?>(
                        initialValue = null,
                        app.packageName
                    ) {
                        value = com.fortress.vault.core.IconCache.getIconAsync(context, app.packageName)
                    }
                    val domains = seal.websiteDomains[app.packageName].orEmpty().sorted()
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(BrassPrimary.copy(alpha = 0.10f)),
                                contentAlignment = Alignment.Center
                            ) {
                                icon?.let { appIcon ->
                                    Image(
                                        bitmap = appIcon,
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp))
                                    )
                                } ?: run {
                                    Icon(Icons.Filled.Lock, contentDescription = null, tint = BrassPrimary, modifier = Modifier.size(20.dp))
                                }
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    app.label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    if (domains.isEmpty()) "App access sealed" else "${domains.size} website rule${if (domains.size == 1) "" else "s"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (domains.isNotEmpty()) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        domains.joinToString(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = BrassPrimary.copy(alpha = 0.9f),
                                        maxLines = 2
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                val websiteRuleCount = seal.websiteDomains.values.sumOf { it.size }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Language, contentDescription = null, tint = BrassPrimary, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Website protection", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text(
                                if (websiteRuleCount == 0) "No website rules added" else "$websiteRuleCount blocked website${if (websiteRuleCount == 1) "" else "s"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WebsiteDomainsDialog(
    seal: Seal,
    context: android.content.Context,
    onDismiss: () -> Unit,
    onSave: (Map<String, Set<String>>, (String?) -> Unit) -> Unit
) {
    var domains by remember(seal.id) { mutableStateOf(seal.websiteDomains) }
    var saving by remember(seal.id) { mutableStateOf(false) }
    var errorMessage by remember(seal.id) { mutableStateOf<String?>(null) }
    val apps by produceState<List<PackageEntry>>(initialValue = emptyList(), seal.id) {
        value = withContext(Dispatchers.IO) {
            seal.packages.map { packageName ->
                val label = runCatching {
                    val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
                    context.packageManager.getApplicationLabel(appInfo).toString()
                }.getOrDefault(packageName)
                PackageEntry(packageName, label)
            }.sortedBy { it.label.lowercase() }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Websites for this seal") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState())
            ) {
                Text(
                    "Add domains to block in managed Chrome for each sealed app. Once saved, a domain cannot be removed until this seal ends.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                if (apps.isEmpty()) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.CenterHorizontally).size(24.dp),
                        color = BrassPrimary
                    )
                } else {
                    WebsiteDomainsEditor(
                        apps = apps.map { it.packageName to it.label },
                        websiteDomains = domains,
                        allowRemove = false,
                        onDomainsChanged = { domains = it }
                    )
                }
                errorMessage?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = apps.isNotEmpty() && !saving,
                onClick = {
                    saving = true
                    errorMessage = null
                    onSave(domains) { error ->
                        saving = false
                        if (error == null) onDismiss() else errorMessage = error
                    }
                }
            ) {
                Text(if (saving) "Applying..." else "Save websites", color = BrassPrimary)
            }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onDismiss) { Text("Cancel") }
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
    val allApps by produceState<List<InstalledApp>>(initialValue = emptyList(), sealId) {
        value = withContext(Dispatchers.IO) { loadLaunchableApps(context) }
    }
    val existingPackages = remember(sealId, seals) {
        seals.firstOrNull { it.id == sealId }?.packages.orEmpty()
    }
    val candidates = remember(allApps, existingPackages) { allApps.filter { it.packageName !in existingPackages } }
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
                if (allApps.isEmpty()) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.CenterHorizontally).size(24.dp),
                        color = BrassPrimary,
                        strokeWidth = 2.dp
                    )
                } else if (candidates.isEmpty()) {
                    Text("No more apps are available to add to this seal.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(candidates, key = { it.packageName }) { app ->
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
    // Load labels asynchronously — previously done synchronously on composition thread
    val labels by produceState<List<String>>(initialValue = emptyList(), packageNames) {
        value = withContext(Dispatchers.IO) {
            packageNames.map { packageName ->
                runCatching {
                    context.packageManager.getApplicationLabel(
                        context.packageManager.getApplicationInfo(packageName, 0)
                    ).toString()
                }.getOrDefault(packageName)
            }.sorted()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Confirm apps to add") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("These apps will be added to the existing seal and blocked until the current unlock date:")
                Spacer(Modifier.height(12.dp))
                if (labels.isEmpty()) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.CenterHorizontally).size(20.dp),
                        color = BrassPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    labels.forEach { label ->
                        Text("- $label", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(4.dp))
                    }
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
            TextButton(
                enabled = labels.isNotEmpty(),
                onClick = onConfirm
            ) { Text("Confirm and add", color = BrassPrimary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Back") }
        }
    )
}

@Composable
private fun AppIconRow(context: android.content.Context, packages: Set<String>) {
    val uniquePackages = remember(packages) { packages.toList().distinct() }
    Row {
        uniquePackages.take(8).forEach { pkg ->
            val icon by produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, pkg) {
                value = com.fortress.vault.core.IconCache.getIconAsync(context, pkg)
            }
            if (icon != null) {
                Image(
                    bitmap = icon!!,
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
    SimpleDateFormat("d MMM · HH:mm", Locale.getDefault()).format(Date(epochMillis))

private fun String.toAppDisplayName(): String {
    val ignoredSegments = setOf("android", "app", "mobile", "client", "lite")
    val name = split('.').lastOrNull { it.isNotBlank() && it.lowercase() !in ignoredSegments }
        ?: substringAfterLast('.')
    return name
        .replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .replace('_', ' ')
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
}
