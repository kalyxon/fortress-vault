package com.fortress.vault.ui.screens

import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Info
import androidx.compose.foundation.shape.CircleShape
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
import com.fortress.vault.ui.components.PageHeader
import com.fortress.vault.ui.components.SectionLabel
import com.fortress.vault.ui.components.SettingsCard
import com.fortress.vault.ui.components.SettingsRow
import com.fortress.vault.ui.theme.TextPrimary
import com.fortress.vault.ui.theme.TextSecondary
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
    BackHandler(
        enabled = detailsSeal != null ||
            websiteRulesTargetSealId != null ||
            addAppsTargetSealId != null ||
            pendingAddedApps != null ||
            extendTargetSealId != null ||
            pendingExtension != null
    ) {
        when {
            pendingExtension != null -> pendingExtension = null
            extendTargetSealId != null -> extendTargetSealId = null
            pendingAddedApps != null -> pendingAddedApps = null
            addAppsTargetSealId != null -> addAppsTargetSealId = null
            websiteRulesTargetSealId != null -> {
                detailsSealId = websiteRulesTargetSealId
                websiteRulesTargetSealId = null
            }
            detailsSeal != null -> detailsSealId = null
        }
    }

    if (detailsSeal != null) {
        SealDetailsScreen(
            seal = detailsSeal,
            context = context,
            onDismiss = { detailsSealId = null },
            onAddApps = { addAppsTargetSealId = detailsSeal.id },
            onExtend = { extendTargetSealId = detailsSeal.id },
            onEmergencyUnlock = { onEmergencyUnlock(detailsSeal.id) },
            onEditWebsites = {
                websiteRulesTargetSealId = detailsSeal.id
                detailsSealId = null
            }
        )
    } else {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                Box(Modifier.padding(start = 20.dp, top = 8.dp, end = 20.dp)) {
                    PageHeader(
                        title = "Fortress Vault",
                        action = {
                            IconButton(onClick = onSettings) {
                                Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    )
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
                    Text("Nothing sealed right now", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Choose apps to pause distractions and set a time to unlock them.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 20.dp),
                    contentPadding = PaddingValues(top = 20.dp, bottom = 96.dp)
                ) {
                    item {
                        SectionLabel("Active seals")
                    }
                    itemsIndexed(seals, key = { _, seal -> seal.id }) { _, seal ->
                        SealCard(
                            seal = seal,
                            tick = tick,
                            onTap = { detailsSealId = seal.id }
                        )
                    }
                }
            }
        }
    }

    val extendTarget = extendTargetSealId
    if (extendTarget != null) {
        ExtendSealDialog(
            onDismiss = { extendTargetSealId = null },
            onConfirm = { extraHours ->
                pendingExtension = extendTarget to extraHours
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
                extraHours = extension.second,
                onDismiss = { pendingExtension = null },
                onConfirm = {
                    coroutineScope.launch {
                        VaultManager.extendSealByHours(context, seal.id, extension.second)
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
private fun SealCard(
    seal: Seal,
    tick: Long,
    onTap: () -> Unit
) {
    val context = LocalContext.current
    // Recompute label when tick changes — driven by single shared 30s loop in HomeScreen,
    // not per-card. This eliminates N parallel LaunchedEffect loops for N seal cards.
    val remainingLabel = remember(seal.id, tick) {
        VaultManager.remainingLabelFor(context, seal)
    }

    SettingsCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clickable(onClick = onTap)
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "SEAL",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            AnimatedContent(
                targetState = remainingLabel,
                transitionSpec = {
                    fadeIn(tween(120)) togetherWith fadeOut(tween(80))
                },
                label = "seal-countdown"
            ) { label ->
                val parts = label.split(" ")
                Row(verticalAlignment = Alignment.Bottom) {
                    if (parts.size >= 2 && parts[0].endsWith("d") && parts[1].endsWith("h")) {
                        Text(
                            parts[0].removeSuffix("d"),
                            style = CountdownStyle.copy(fontSize = 44.sp, letterSpacing = 0.sp),
                            color = TextPrimary
                        )
                        Text(
                            "d",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            modifier = Modifier.padding(start = 5.dp, bottom = 7.dp, end = 12.dp)
                        )
                        Text(
                            parts[1].removeSuffix("h").removeSuffix("m"),
                            style = CountdownStyle.copy(fontSize = 44.sp, letterSpacing = 0.sp),
                            color = TextPrimary
                        )
                        Text(
                            if (parts[1].endsWith("h")) "h" else "m",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            modifier = Modifier.padding(start = 5.dp, bottom = 7.dp)
                        )
                    } else if (parts.size >= 2 && parts[0].endsWith("h") && parts[1].endsWith("m")) {
                        Text(
                            parts[0].removeSuffix("h"),
                            style = CountdownStyle.copy(fontSize = 44.sp, letterSpacing = 0.sp),
                            color = TextPrimary
                        )
                        Text(
                            "h",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            modifier = Modifier.padding(start = 5.dp, bottom = 7.dp, end = 12.dp)
                        )
                        Text(
                            parts[1].removeSuffix("m"),
                            style = CountdownStyle.copy(fontSize = 44.sp, letterSpacing = 0.sp),
                            color = TextPrimary
                        )
                        Text(
                            "m",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            modifier = Modifier.padding(start = 5.dp, bottom = 7.dp)
                        )
                    } else {
                        Text(
                            parts.firstOrNull().orEmpty(),
                            style = CountdownStyle.copy(fontSize = 44.sp, letterSpacing = 0.sp),
                            color = TextPrimary
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${seal.packages.size} protected app${if (seal.packages.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                Icon(Icons.Filled.ChevronRight, contentDescription = "Seal details", tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
    onExtend: () -> Unit,
    onEmergencyUnlock: () -> Unit,
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
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to seals")
                }
                Column {
                    Text("Seal", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(sealShortId(seal.id), style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                }
            }
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                IconButton(onClick = onAddApps) {
                    Icon(Icons.Filled.Add, contentDescription = "Add apps")
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onExtend) {
                    Icon(Icons.Filled.Timer, contentDescription = "Add time")
                }
                IconButton(onClick = onEmergencyUnlock) {
                    Icon(Icons.Filled.LockOpen, contentDescription = "Emergency unlock", tint = EmberRed)
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp
                ) {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                        Text("Time remaining", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        AnimatedContent(
                            targetState = remainingLabel,
                            transitionSpec = {
                                fadeIn(tween(120)) togetherWith fadeOut(tween(80))
                            },
                            label = "detail-countdown"
                        ) { label ->
                            Text(label, style = CountdownStyle.copy(fontSize = 38.sp, letterSpacing = 0.sp), color = TextPrimary)
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.CalendarMonth, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Unlocks ${formatSealDate(seal.unlockAtMillis)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Protected apps", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("${uniquePackages.size}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    Column(Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            icon?.let { appIcon ->
                                Image(bitmap = appIcon, contentDescription = null, modifier = Modifier.size(38.dp).clip(RoundedCornerShape(8.dp)))
                            } ?: Icon(Icons.Filled.Lock, contentDescription = null, tint = BrassPrimary, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(app.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                if (domains.isNotEmpty()) {
                                    Text(
                                        domains.joinToString(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2
                                    )
                                }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                    }
                }
            }

            item {
                val websiteRuleCount = seal.websiteDomains.values.sumOf { it.size }
                Row(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onEditWebsites).padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Language, contentDescription = null, tint = BrassPrimary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (websiteRuleCount == 0) "Add website rules" else "Website rules · $websiteRuleCount",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(Icons.Filled.ChevronRight, contentDescription = "Edit website rules", tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
    BackHandler(enabled = true) { if (!saving) onDismiss() }
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

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
            PageHeader(title = "Website rules", onBack = { if (!saving) onDismiss() })
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (apps.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = BrassPrimary, strokeWidth = 2.dp)
                    }
                } else {
                    WebsiteDomainsEditor(
                        apps = apps.map { it.packageName to it.label },
                        websiteDomains = domains,
                        allowRemove = true,
                        onDomainsChanged = { domains = it }
                    )
                }
                errorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
            Button(
                enabled = apps.isNotEmpty() && !saving,
                onClick = {
                    saving = true
                    errorMessage = null
                    onSave(domains) { error ->
                        saving = false
                        if (error == null) onDismiss() else errorMessage = error
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BrassPrimary)
            ) {
                Text(if (saving) "Applying..." else "Save websites", color = MaterialTheme.colorScheme.background)
            }
        }
    }
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
    BackHandler(enabled = true) { onDismiss() }

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
    BackHandler(enabled = true) { onDismiss() }
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
private fun ExtendSealDialog(onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var mode by remember { mutableStateOf(AddTimeMode.HOURS) }
    BackHandler(enabled = true) { onDismiss() }
    var extraHours by remember { mutableIntStateOf(24) }
    var extraDays by remember { mutableIntStateOf(1) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
            PageHeader(title = "Add time", onBack = onDismiss)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mode == AddTimeMode.HOURS,
                        onClick = { mode = AddTimeMode.HOURS },
                        label = { Text("Hours") }
                    )
                    FilterChip(
                        selected = mode == AddTimeMode.DAYS,
                        onClick = { mode = AddTimeMode.DAYS },
                        label = { Text("Days") }
                    )
                }
                SettingsCard {
                    Column(Modifier.padding(20.dp)) {
                        Text(
                            "+" + if (mode == AddTimeMode.HOURS) formatDuration(extraHours)
                            else formatDuration(extraDays * 24),
                            style = MaterialTheme.typography.displaySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(20.dp))
                        if (mode == AddTimeMode.HOURS) {
                            Slider(
                                value = extraHours.toFloat(),
                                onValueChange = { extraHours = it.toInt().coerceIn(1, 24) },
                                valueRange = 1f..24f,
                                colors = SliderDefaults.colors(
                                    thumbColor = BrassPrimary,
                                    activeTrackColor = BrassPrimary
                                )
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("1 hour", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("24 hours", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            Slider(
                                value = extraDays.toFloat(),
                                onValueChange = { extraDays = it.toInt().coerceIn(1, 365) },
                                valueRange = 1f..365f,
                                colors = SliderDefaults.colors(
                                    thumbColor = BrassPrimary,
                                    activeTrackColor = BrassPrimary
                                )
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("1 day", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("365 days", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            Button(
                onClick = {
                    onConfirm(if (mode == AddTimeMode.HOURS) extraHours else extraDays * 24)
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BrassPrimary)
            ) {
                Text("Continue", color = MaterialTheme.colorScheme.background)
            }
        }
    }
}

@Composable
private fun ExtendSealConfirmationDialog(
    seal: Seal,
    extraHours: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val newUnlockAt = seal.unlockAtMillis + TimeUnit.HOURS.toMillis(extraHours.toLong())
    BackHandler(enabled = true) { onDismiss() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
            PageHeader(title = "Confirm added time", onBack = onDismiss)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsCard {
                    Column(Modifier.padding(18.dp)) {
                        Text("Current unlock", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(formatSealDate(seal.unlockAtMillis), style = MaterialTheme.typography.titleMedium)
                    }
                }
                SettingsCard {
                    Column(Modifier.padding(18.dp)) {
                        Text("New unlock", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(formatSealDate(newUnlockAt), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            Button(
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BrassPrimary)
            ) {
                Text("Confirm added time", color = MaterialTheme.colorScheme.background)
            }
        }
    }
}

private fun formatSealDate(epochMillis: Long): String =
    SimpleDateFormat("d MMM · HH:mm", Locale.getDefault()).format(Date(epochMillis))

private fun sealShortId(sealId: String): String =
    "ID " + sealId.replace("-", "").take(8).uppercase(Locale.getDefault())

private fun formatDuration(totalHours: Int): String {
    if (totalHours < 24) return "$totalHours hour${if (totalHours == 1) "" else "s"}"
    val days = totalHours / 24
    val hours = totalHours % 24
    return if (hours == 0) "$days day${if (days == 1) "" else "s"}"
    else "$days d $hours h"
}

private enum class AddTimeMode { HOURS, DAYS }

private fun String.toAppDisplayName(): String {
    val ignoredSegments = setOf("android", "app", "mobile", "client", "lite")
    val name = split('.').lastOrNull { it.isNotBlank() && it.lowercase() !in ignoredSegments }
        ?: substringAfterLast('.')
    return name
        .replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .replace('_', ' ')
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
}
