package com.fortress.vault.ui.screens

import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fortress.vault.core.ControlManager
import com.fortress.vault.core.AppTimeLimitManager
import com.fortress.vault.core.DailyAppLimit
import com.fortress.vault.core.DeviceControl
import com.fortress.vault.core.PreparedControls
import com.fortress.vault.core.MAX_SEAL_DURATION_DAYS
import com.fortress.vault.core.MAX_FULL_APP_CHANGE_BLOCK_DAYS
import com.fortress.vault.core.MAX_UPDATE_FRIENDLY_APP_CHANGE_DAYS
import com.fortress.vault.core.MIN_SEAL_DURATION_DAYS
import com.fortress.vault.core.WebProtectionManager
import com.fortress.vault.core.WebProtectionLock
import com.fortress.vault.core.WebProtectionLockManager
import com.fortress.vault.core.WebProtectionSetting
import com.fortress.vault.core.PreparedWebProtectionLock
import com.fortress.vault.core.WebProtectionUnlockResult
import com.fortress.vault.core.TimeKeeper
import com.fortress.vault.core.VaultManager
import com.fortress.vault.core.normalizeWebsiteDomain
import com.fortress.vault.ui.theme.BrassPrimary
import com.fortress.vault.ui.theme.EmberRed
import com.fortress.vault.ui.theme.SteelSurfaceHigh
import com.fortress.vault.ui.theme.TextPrimary
import com.fortress.vault.ui.theme.TextSecondary
import com.fortress.vault.ui.theme.VaultDivider
import com.fortress.vault.ui.components.SettingsCard
import com.fortress.vault.ui.components.PageHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

private data class LoadedWebSettings(
    val isDeviceOwner: Boolean,
    val blockAdultSites: Boolean,
    val blockBypassApps: Boolean,
    val blockSealedAppSites: Boolean,
    val blockChromeIncognito: Boolean,
    val appTimeLimitsEnabled: Boolean,
    val adultDomainBlockCount: Int,
    val webLocks: Map<WebProtectionSetting, WebProtectionLock>,
    val chromePolicyApplied: Boolean
)

private enum class SettingsSection(val title: String, val description: String, val icon: ImageVector) {
    WEB_PROTECTION("Web protection", "Filters, browser access and Incognito", Icons.Filled.Shield),
    APP_LIMITS("App time limits", "Daily allowances for selected apps", Icons.Filled.Timer),
    DEVICE_CONTROLS("Device controls", "System settings and app changes", Icons.Filled.Tune)
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    var section by remember { mutableStateOf<SettingsSection?>(null) }
    BackHandler(enabled = section != null) { section = null }

    AnimatedContent(
        targetState = section,
        transitionSpec = {
            (fadeIn(tween(160)) + slideInHorizontally(tween(160)) { it / 24 }) togetherWith
                (fadeOut(tween(110)) + slideOutHorizontally(tween(110)) { -it / 24 })
        },
        label = "settings-navigation"
    ) { selectedSection ->
        if (selectedSection == null) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(18.dp))
                SettingsGroupCard(
                    items = listOf(SettingsSection.WEB_PROTECTION, SettingsSection.APP_LIMITS),
                    onSelect = { section = it }
                )
                Spacer(Modifier.height(16.dp))
                SettingsGroupCard(
                    items = listOf(SettingsSection.DEVICE_CONTROLS),
                    onSelect = { section = it }
                )
            }
        } else {
            SettingsDetailScreen(section = selectedSection, onBack = { section = null })
        }
    }
}

@Composable
private fun SettingsGroupCard(
    items: List<SettingsSection>,
    onSelect: (SettingsSection) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column {
            items.forEachIndexed { index, item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(item) }
                        .padding(horizontal = 16.dp, vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            item.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(21.dp)
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            item.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = TextSecondary
                    )
                }
                if (index < items.lastIndex) {
                    HorizontalDivider(
                        color = VaultDivider,
                        modifier = Modifier.padding(start = 72.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsDetailScreen(section: SettingsSection, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var locks by remember { mutableStateOf(emptyList<com.fortress.vault.core.ControlLock>()) }

    LaunchedEffect(Unit) {
        locks = withContext(Dispatchers.IO) { ControlManager.activeLocks(context) }
    }

    suspend fun refreshLocks() {
        locks = withContext(Dispatchers.IO) { ControlManager.activeLocks(context) }
    }

    var selected by remember { mutableStateOf(emptySet<DeviceControl>()) }
    var fullAppChangeBlock by remember { mutableStateOf(false) }
    var days by remember { mutableStateOf(1) }
    var preparedControls by remember { mutableStateOf<PreparedControls?>(null) }
    var step by remember { mutableStateOf(0) }
    var unlockTarget by remember { mutableStateOf<DeviceControl?>(null) }
    var phrase by remember { mutableStateOf("") }
    var hasWrittenDown by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var blockAdultSites by remember { mutableStateOf(false) }
    var blockBypassApps by remember { mutableStateOf(false) }
    var blockSealedAppSites by remember { mutableStateOf(true) }
    var blockChromeIncognito by remember { mutableStateOf(false) }
    var appLimits by remember { mutableStateOf(emptyList<DailyAppLimit>()) }
    var appTimeLimitsEnabled by remember { mutableStateOf(false) }
    var usageAccessGranted by remember { mutableStateOf(false) }
    var appTimeError by remember { mutableStateOf<String?>(null) }
    var showAppPicker by remember { mutableStateOf(false) }
    var appPickerQuery by remember { mutableStateOf("") }
    var limitEditorApp by remember { mutableStateOf<InstalledApp?>(null) }
    var limitMinutesInput by remember { mutableStateOf("10") }
    var appTimeUsage by remember { mutableStateOf(emptyMap<String, Long>()) }
    var launchableApps by remember { mutableStateOf(emptyList<InstalledApp>()) }
    var webProtectionError by remember { mutableStateOf<String?>(null) }
    var webProtectionBusy by remember { mutableStateOf(false) }
    var isDeviceOwner by remember { mutableStateOf(false) }
    var controlError by remember { mutableStateOf<String?>(null) }
    var controlsBusy by remember { mutableStateOf(false) }
    var webLocks by remember { mutableStateOf(emptyMap<WebProtectionSetting, WebProtectionLock>()) }
    var webLocksLoaded by remember { mutableStateOf(false) }
    var preparedWebLock by remember { mutableStateOf<PreparedWebProtectionLock?>(null) }
    var webLockKeySaved by remember { mutableStateOf(false) }
    var webUnlockTarget by remember { mutableStateOf<WebProtectionSetting?>(null) }
    var webRecoveryPhrase by remember { mutableStateOf("") }
    var webLockError by remember { mutableStateOf<String?>(null) }
    var webLockDurationDays by remember { mutableIntStateOf(WebProtectionLockManager.DEFAULT_LOCK_DAYS) }
    var adultDomainBlockCount by remember { mutableIntStateOf(0) }
    var showAdultDomainEditor by remember { mutableStateOf(false) }
    var showAdultDomainReview by remember { mutableStateOf(false) }
    var adultDomainInput by remember { mutableStateOf("") }
    var pendingAdultDomains by remember { mutableStateOf(emptySet<String>()) }
    var originalAdultDomains by remember { mutableStateOf(emptySet<String>()) }
    var adultDomainConfirmed by remember { mutableStateOf(false) }
    var adultDomainError by remember { mutableStateOf<String?>(null) }
    var adultDomainBusy by remember { mutableStateOf(false) }
    var webDetailsTarget by remember { mutableStateOf<WebProtectionSetting?>(null) }
    BackHandler(
        enabled = webDetailsTarget != null ||
            showAdultDomainEditor ||
            showAdultDomainReview ||
            showAppPicker ||
            limitEditorApp != null ||
            preparedWebLock != null ||
            webUnlockTarget != null ||
            unlockTarget != null
    ) {
        when {
            preparedWebLock != null -> {
                preparedWebLock = null
                webLockKeySaved = false
            }
            webUnlockTarget != null -> webUnlockTarget = null
            limitEditorApp != null -> limitEditorApp = null
            showAppPicker -> {
                showAppPicker = false
                appPickerQuery = ""
            }
            showAdultDomainReview -> {
                if (!adultDomainBusy) {
                    showAdultDomainReview = false
                    showAdultDomainEditor = true
                }
            }
            showAdultDomainEditor -> {
                if (!adultDomainBusy) {
                    showAdultDomainEditor = false
                    pendingAdultDomains = emptySet()
                    originalAdultDomains = emptySet()
                }
            }
            unlockTarget != null -> unlockTarget = null
            webDetailsTarget != null -> webDetailsTarget = null
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            usageAccessGranted = withContext(Dispatchers.IO) {
                AppTimeLimitManager.hasUsageAccess(context)
            }
        }
    }

    LaunchedEffect(appLimits) {
        if (appLimits.isNotEmpty() && launchableApps.isEmpty()) {
            launchableApps = withContext(Dispatchers.IO) { loadLaunchableApps(context) }
        }
        while (true) {
            if (usageAccessGranted) {
                appTimeUsage = withContext(Dispatchers.IO) {
                    AppTimeLimitManager.usageMillisToday(
                        context,
                        appLimits.map { it.packageName }.toSet()
                    )
                }
            }
            appTimeError = withContext(Dispatchers.IO) {
                AppTimeLimitManager.enforcementError(context)
            }
            delay(30_000)
        }
    }

    LaunchedEffect(Unit) {
        try {
            val settings = withContext(Dispatchers.IO) {
                WebProtectionLockManager.reconcileExpired(context)
                val dpm = context.getSystemService(DevicePolicyManager::class.java)
                val owner = dpm.isDeviceOwnerApp(context.packageName)
                val adultSites = WebProtectionManager.blockAdultSites(context)
                val policyApplied = if (owner) {
                    WebProtectionManager.applyChromeWebsitePolicy(context, force = true)
                } else {
                    true
                }
                LoadedWebSettings(
                    isDeviceOwner = owner,
                    blockAdultSites = adultSites,
                    blockBypassApps = WebProtectionManager.blockBypassApps(context),
                    blockSealedAppSites = WebProtectionManager.blockSealedAppSites(context),
                    blockChromeIncognito = WebProtectionManager.blockChromeIncognito(context),
                    appTimeLimitsEnabled = AppTimeLimitManager.isEnabled(context),
                    adultDomainBlockCount = WebProtectionManager.adultDomainBlockCount(context),
                    webLocks = WebProtectionLockManager.activeLocks(context),
                    chromePolicyApplied = policyApplied
                )
            }
            isDeviceOwner = settings.isDeviceOwner
            blockAdultSites = settings.blockAdultSites
            blockBypassApps = settings.blockBypassApps
            blockSealedAppSites = settings.blockSealedAppSites
            blockChromeIncognito = settings.blockChromeIncognito
            appTimeLimitsEnabled = settings.appTimeLimitsEnabled
            adultDomainBlockCount = settings.adultDomainBlockCount
            webLocks = settings.webLocks
            webLocksLoaded = true
            appLimits = withContext(Dispatchers.IO) { AppTimeLimitManager.limits(context) }
            usageAccessGranted = withContext(Dispatchers.IO) { AppTimeLimitManager.hasUsageAccess(context) }
            appTimeError = withContext(Dispatchers.IO) { AppTimeLimitManager.enforcementError(context) }
            if (settings.isDeviceOwner && !settings.chromePolicyApplied) {
                webProtectionError =
                    "Android did not confirm Chrome's website/Incognito policy. Restart Chrome; if Incognito is still available, check chrome://policy for IncognitoModeAvailability and URLBlocklist."
            }
        } catch (failure: Exception) {
            webProtectionError = failure.message ?: "Could not load web-protection settings safely."
        }
    }

    LaunchedEffect(webLocksLoaded) {
        if (!webLocksLoaded) return@LaunchedEffect
        while (true) {
            delay(60_000)
            try {
                webLocks = withContext(Dispatchers.IO) {
                    WebProtectionLockManager.reconcileExpired(context)
                    WebProtectionLockManager.activeLocks(context)
                }
            } catch (failure: Exception) {
                webLockError = failure.message ?: "Could not refresh recovery-lock status."
            }
        }
    }

    val maximumControlDays = when {
        DeviceControl.APP_INSTALLS !in selected -> MAX_SEAL_DURATION_DAYS
        fullAppChangeBlock -> MAX_FULL_APP_CHANGE_BLOCK_DAYS
        else -> MAX_UPDATE_FRIENDLY_APP_CHANGE_DAYS
    }

    fun refresh() { scope.launch { refreshLocks() } }

    fun changeWebSetting(setting: WebProtectionSetting, enabled: Boolean) {
        scope.launch {
            webProtectionBusy = true
            webProtectionError = null
            try {
                val (applied, failureMessage) = withContext(Dispatchers.IO) {
                    when (setting) {
                        WebProtectionSetting.ADULT_SITES ->
                            WebProtectionManager.setBlockAdultSites(context, enabled).let {
                                it.applied to it.error
                            }
                        WebProtectionSetting.CHROME_ONLY ->
                            (WebProtectionManager.setBlockBypassApps(context, enabled) to null)
                        WebProtectionSetting.SEALED_APP_WEBSITES ->
                            (WebProtectionManager.setBlockSealedAppSites(context, enabled) to null)
                        WebProtectionSetting.CHROME_INCOGNITO ->
                            (WebProtectionManager.setBlockChromeIncognito(context, enabled) to null)
                        WebProtectionSetting.APP_TIME_LIMITS -> try {
                            AppTimeLimitManager.setEnabled(context, enabled)
                            true to null
                        } catch (failure: Exception) {
                            false to (failure.message ?: "Could not change daily app time limits.")
                        }
                    }

                }
                if (!applied) {
                    if (setting == WebProtectionSetting.APP_TIME_LIMITS) {
                        appTimeLimitsEnabled = withContext(Dispatchers.IO) {
                            AppTimeLimitManager.isEnabled(context)
                        }
                    }
                    webProtectionError = failureMessage ?: when (setting) {
                        WebProtectionSetting.ADULT_SITES ->
                            "Android could not apply the DNS policy. Check device-owner status and DNS connectivity."
                        WebProtectionSetting.CHROME_ONLY ->
                            "Android could not restrict browsers or VPN setup. Check device-owner status."
                        WebProtectionSetting.SEALED_APP_WEBSITES ->
                            "Android could not apply Chrome's website policy. Check device-owner status and Chrome."
                        WebProtectionSetting.CHROME_INCOGNITO ->
                            "Android could not update Chrome's Incognito policy. Check device-owner status and Chrome."
                        WebProtectionSetting.APP_TIME_LIMITS ->
                            "Could not enable daily app time limits."
                    }
                } else {
                    when (setting) {
                        WebProtectionSetting.ADULT_SITES -> blockAdultSites = enabled
                        WebProtectionSetting.CHROME_ONLY -> blockBypassApps = enabled
                        WebProtectionSetting.SEALED_APP_WEBSITES -> blockSealedAppSites = enabled
                        WebProtectionSetting.CHROME_INCOGNITO -> blockChromeIncognito = enabled
                        WebProtectionSetting.APP_TIME_LIMITS -> {
                            appTimeLimitsEnabled = enabled
                            appTimeError = null
                        }
                    }
                }
            } catch (failure: Exception) {
                webProtectionError = failure.message ?: "Android could not apply this protection."
            } finally {
                webProtectionBusy = false
            }
        }
    }

    fun saveAppLimit(packageName: String, minutes: Int?) {
        scope.launch {
            appTimeError = null
            try {
                withContext(Dispatchers.IO) {
                    AppTimeLimitManager.setLimit(context, packageName, minutes)
                }
                appLimits = withContext(Dispatchers.IO) { AppTimeLimitManager.limits(context) }
            } catch (failure: Exception) {
                appTimeError = failure.message ?: "Could not save this app's daily limit."
            }
        }
    }

    LaunchedEffect(showAppPicker) {
        if (showAppPicker) {
            launchableApps = withContext(Dispatchers.IO) { loadLaunchableApps(context) }
        }
    }

    fun prepareWebLock(setting: WebProtectionSetting) {
        scope.launch {
            webProtectionBusy = true
            webLockError = null
            webLockDurationDays = WebProtectionLockManager.DEFAULT_LOCK_DAYS
            try {
                preparedWebLock = withContext(Dispatchers.IO) {
                    WebProtectionLockManager.prepareLock(context, setting)
                }
                webLockKeySaved = false
            } catch (failure: Exception) {
                webLockError = failure.message ?: "Could not prepare this recovery lock."
            } finally {
                webProtectionBusy = false
            }
        }
    }

    fun stageAdultDomain() {
        try {
            val normalized = normalizeWebsiteDomain(adultDomainInput)
            require(normalized !in pendingAdultDomains) {
                "That domain is already in the list."
            }
            pendingAdultDomains = pendingAdultDomains + normalized
            adultDomainInput = ""
            adultDomainError = null
        } catch (failure: IllegalArgumentException) {
            adultDomainError = failure.message ?: "Enter a valid website domain."
        }
    }

    fun openAdultDomainEditor() {
        scope.launch {
            adultDomainBusy = true
            adultDomainError = null
            webProtectionError = null
            try {
                val savedDomains = withContext(Dispatchers.IO) {
                    WebProtectionManager.adultDomainsForEditing(context)
                }
                originalAdultDomains = savedDomains
                pendingAdultDomains = savedDomains
                adultDomainInput = ""
                webDetailsTarget = null
                showAdultDomainEditor = true
            } catch (failure: Exception) {
                webDetailsTarget = null
                webProtectionError = failure.message ?: "Could not load Chrome domain blocks."
            } finally {
                adultDomainBusy = false
            }
        }
    }

    fun saveAdultDomainBlocks() {
        scope.launch {
            adultDomainBusy = true
            adultDomainError = null
            webProtectionError = null
            try {
                withContext(Dispatchers.IO) {
                    WebProtectionManager.updateAdultDomainBlocks(context, pendingAdultDomains)
                }
                adultDomainBlockCount = withContext(Dispatchers.IO) {
                    WebProtectionManager.adultDomainBlockCount(context)
                }
                showAdultDomainReview = false
                showAdultDomainEditor = false
                originalAdultDomains = pendingAdultDomains
                pendingAdultDomains = emptySet()
                adultDomainConfirmed = false
            } catch (failure: Exception) {
                adultDomainError = failure.message ?: "Chrome could not apply the domain blocks."
            } finally {
                adultDomainBusy = false
            }
        }
    }

    fun unlockWebSetting(setting: WebProtectionSetting) {
        scope.launch {
            webProtectionBusy = true
            webLockError = null
            try {
                val result = withContext(Dispatchers.IO) {
                    WebProtectionLockManager.unlock(context, setting, webRecoveryPhrase)
                }
                when (result) {
                    WebProtectionUnlockResult.UNLOCKED -> {
                        webLocks = withContext(Dispatchers.IO) {
                            WebProtectionLockManager.activeLocks(context)
                        }
                        webUnlockTarget = null
                        webRecoveryPhrase = ""
                    }
                    WebProtectionUnlockResult.WRONG_PHRASE ->
                        webLockError = "That recovery phrase is incorrect."
                    WebProtectionUnlockResult.COOLDOWN ->
                        webLockError = "Recovery is temporarily locked after repeated incorrect phrases."
                    WebProtectionUnlockResult.NOT_LOCKED ->
                        webLockError = "This setting is already unlocked."
                }
            } catch (failure: Exception) {
                webLockError = failure.message ?: "Could not unlock this setting."
            } finally {
                webProtectionBusy = false
            }
        }
    }

    val prepared = preparedControls
    if (step == 1 && prepared != null) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(Modifier.weight(1f)) {
                    Text("FINAL REVIEW", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    Spacer(Modifier.height(6.dp))
                    Text("Review lock", style = MaterialTheme.typography.headlineMedium)
                }
                TextButton(onClick = { preparedControls = null; step = 0 }) { Text("Back") }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("STEP 1 OF 3", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.size(4.dp).background(TextSecondary, androidx.compose.foundation.shape.CircleShape))
                Spacer(Modifier.width(10.dp))
                Text("Check what will be locked", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            }
            Spacer(Modifier.height(20.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text("READY TO LOCK", style = MaterialTheme.typography.labelMedium, color = EmberRed)
                Spacer(Modifier.height(6.dp))
                Text(
                    "${prepared.locks.size} device control${if (prepared.locks.size == 1) "" else "s"} selected",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
                HorizontalDivider(color = VaultDivider, modifier = Modifier.padding(top = 16.dp))
                Spacer(Modifier.height(20.dp))
                Text("Selected controls", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                prepared.locks.keys.forEachIndexed { index, control ->
                    Text("CONTROL ${"%02d".format(index + 1)}", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    Spacer(Modifier.height(4.dp))
                    Text(control.title, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (control == DeviceControl.APP_INSTALLS) {
                            if (prepared.fullAppChangeBlock) {
                                "New installs, updates and uninstalls are blocked."
                            } else {
                                "Existing apps update normally; new apps are suspended and removed."
                            }
                        } else {
                            control.effect()
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("LOCK DURATION", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                        Spacer(Modifier.height(4.dp))
                        Text("$days day(s)", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                    }
                    Text("Timer protected", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
            }
            Button(
                onClick = { step = 2 },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BrassPrimary)
            ) { Text("Continue to recovery key", color = MaterialTheme.colorScheme.background) }
        }
    } else if (step == 2 && prepared != null) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(Modifier.weight(1f)) {
                    Text("RECOVERY KEY", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    Spacer(Modifier.height(6.dp))
                    Text("Write this down now", style = MaterialTheme.typography.headlineMedium)
                }
                TextButton(onClick = { step = 1 }) { Text("Back") }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("STEP 2 OF 3", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.size(4.dp).background(TextSecondary, androidx.compose.foundation.shape.CircleShape))
                Spacer(Modifier.width(10.dp))
                Text("Save every key before continuing", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            }
            Spacer(Modifier.height(20.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text("Your recovery keys", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Each control has a different key. Store both phrases offline.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                HorizontalDivider(color = VaultDivider, modifier = Modifier.padding(top = 12.dp))
                Spacer(Modifier.height(20.dp))
                prepared.phrases.entries.forEachIndexed { index, (control, value) ->
                    RecoveryPhraseCard(index + 1, control, value)
                }
                Row(
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Checkbox(
                        checked = hasWrittenDown,
                        onCheckedChange = { hasWrittenDown = it },
                        colors = CheckboxDefaults.colors(checkedColor = BrassPrimary)
                    )
                    Text(
                        "I've written every phrase down somewhere physical.",
                        modifier = Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextPrimary
                    )
                }
            }
            Button(
                onClick = { step = 3 },
                enabled = hasWrittenDown,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BrassPrimary)
            ) { Text("Continue to confirmation", color = MaterialTheme.colorScheme.background) }
        }
    } else if (step == 3 && prepared != null) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(Modifier.weight(1f)) {
                    Text("FINAL CONFIRMATION", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    Spacer(Modifier.height(6.dp))
                    Text("Confirm and lock", style = MaterialTheme.typography.headlineMedium)
                }
                TextButton(onClick = { step = 2 }) { Text("Back") }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("STEP 3 OF 3", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.size(4.dp).background(TextSecondary, androidx.compose.foundation.shape.CircleShape))
                Spacer(Modifier.width(10.dp))
                Text("Nothing is locked yet", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            }
            Spacer(Modifier.height(20.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text("Final check", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${prepared.locks.size} control${if (prepared.locks.size == 1) "" else "s"} · $days day(s)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                Spacer(Modifier.height(16.dp))
                prepared.locks.keys.forEachIndexed { index, control ->
                    if (index > 0) {
                        HorizontalDivider(color = VaultDivider, modifier = Modifier.padding(vertical = 12.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "%02d".format(index + 1),
                            style = MaterialTheme.typography.labelLarge,
                            color = TextSecondary
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(control.title, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                    }
                }
                HorizontalDivider(color = VaultDivider, modifier = Modifier.padding(vertical = 16.dp))
                Text(
                    "Controls stay active until the timer ends or a recovery phrase is entered.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }
            Button(
                onClick = {
                    scope.launch {
                        controlsBusy = true
                        controlError = null
                        try {
                            withContext(Dispatchers.IO) {
                                ControlManager.commit(context, prepared)
                            }
                            preparedControls = null
                            step = 0
                            refresh()
                        } catch (error: Exception) {
                            controlError = error.message ?: "Android could not apply the selected controls."
                        } finally {
                            controlsBusy = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = !controlsBusy,
                colors = ButtonDefaults.buttonColors(containerColor = BrassPrimary)
            ) {
                Text(
                    if (controlsBusy) "Applying controls..." else "Lock these controls",
                    color = MaterialTheme.colorScheme.background
                )
            }
            controlError?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    } else {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 20.dp, vertical = 16.dp)) {
        PageHeader(title = section.title, onBack = onBack)
        if (!isDeviceOwner && section != SettingsSection.APP_LIMITS) {
            Text(
                "Device controls and DNS filtering require Fortress Vault to be the device owner. Complete the one-time setup before enabling them.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        if (section == SettingsSection.DEVICE_CONTROLS) controlError?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        Spacer(Modifier.height(16.dp))
        LazyColumn(Modifier.weight(1f)) {
            if (section == SettingsSection.WEB_PROTECTION) item {
                WebProtectionSettingCard(
                    title = WebProtectionSetting.ADULT_SITES.title,
                    summary = "Device-wide domain filter",
                    checked = blockAdultSites,
                    locked = WebProtectionSetting.ADULT_SITES in webLocks,
                    enabled = isDeviceOwner && webLocksLoaded && !webProtectionBusy,
                    onCheckedChange = { changeWebSetting(WebProtectionSetting.ADULT_SITES, it) },
                    onOpenDetails = { webDetailsTarget = WebProtectionSetting.ADULT_SITES }
                )
                WebProtectionSettingCard(
                    title = "Allow only Chrome and block VPN apps",
                    summary = "Restrict browsers and VPN apps",
                    checked = blockBypassApps,
                    locked = WebProtectionSetting.CHROME_ONLY in webLocks,
                    enabled = isDeviceOwner && webLocksLoaded && !webProtectionBusy,
                    onCheckedChange = { changeWebSetting(WebProtectionSetting.CHROME_ONLY, it) },
                    onOpenDetails = { webDetailsTarget = WebProtectionSetting.CHROME_ONLY }
                )
                WebProtectionSettingCard(
                    title = WebProtectionSetting.SEALED_APP_WEBSITES.title,
                    summary = "Block sealed-app domains in Chrome",
                    checked = blockSealedAppSites,
                    locked = WebProtectionSetting.SEALED_APP_WEBSITES in webLocks,
                    enabled = isDeviceOwner && webLocksLoaded && !webProtectionBusy,
                    onCheckedChange = { changeWebSetting(WebProtectionSetting.SEALED_APP_WEBSITES, it) },
                    onOpenDetails = { webDetailsTarget = WebProtectionSetting.SEALED_APP_WEBSITES }
                )
                WebProtectionSettingCard(
                    title = WebProtectionSetting.CHROME_INCOGNITO.title,
                    summary = "Disable private browsing in Chrome",
                    checked = blockChromeIncognito,
                    locked = WebProtectionSetting.CHROME_INCOGNITO in webLocks,
                    enabled = isDeviceOwner && webLocksLoaded && !webProtectionBusy,
                    onCheckedChange = { changeWebSetting(WebProtectionSetting.CHROME_INCOGNITO, it) },
                    onOpenDetails = { webDetailsTarget = WebProtectionSetting.CHROME_INCOGNITO }
                )
                if (webProtectionBusy) {
                    Text("Applying changes…", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
                webProtectionError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                webLockError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (section == SettingsSection.APP_LIMITS) item {
                Column {
                    WebProtectionSettingCard(
                        title = WebProtectionSetting.APP_TIME_LIMITS.title,
                        summary = "Daily allowances for selected apps",
                        checked = appTimeLimitsEnabled,
                        locked = WebProtectionSetting.APP_TIME_LIMITS in webLocks,
                        enabled = isDeviceOwner && webLocksLoaded && !webProtectionBusy &&
                            (appTimeLimitsEnabled || appLimits.isNotEmpty()),
                        onCheckedChange = { changeWebSetting(WebProtectionSetting.APP_TIME_LIMITS, it) },
                        onOpenDetails = { webDetailsTarget = WebProtectionSetting.APP_TIME_LIMITS }
                    )
                    if (!usageAccessGranted) {
                        Text(
                            "Usage access is required.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        OutlinedButton(
                            onClick = { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                        ) { Text("Grant Usage access") }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("App allowances", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        TextButton(
                            enabled = !webProtectionBusy &&
                                WebProtectionSetting.APP_TIME_LIMITS !in webLocks,
                            onClick = { showAppPicker = true }
                        ) { Text("Add app") }
                    }
                    appLimits.forEach { limit ->
                        val appName = launchableApps.firstOrNull { it.packageName == limit.packageName }?.label
                            ?: limit.packageName
                        val usedMillis = appTimeUsage[limit.packageName] ?: 0L
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val appIcon by produceState<androidx.compose.ui.graphics.ImageBitmap?>(
                                initialValue = null,
                                key1 = limit.packageName
                            ) {
                                value = com.fortress.vault.core.IconCache.getIconAsync(context, limit.packageName)
                            }
                            if (appIcon != null) {
                                Image(
                                    bitmap = appIcon!!,
                                    contentDescription = appName,
                                    modifier = Modifier.size(42.dp).clip(RoundedCornerShape(12.dp))
                                )
                                Spacer(Modifier.width(14.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(appName, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "${(usedMillis / 60_000L).coerceAtMost(limit.limitMinutes.toLong())} / ${limit.limitMinutes} min today",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextSecondary
                                )
                            }
                            TextButton(
                                enabled = WebProtectionSetting.APP_TIME_LIMITS !in webLocks,
                                onClick = {
                                    limitMinutesInput = limit.limitMinutes.toString()
                                    limitEditorApp = InstalledApp(appName, limit.packageName)
                                }
                            ) { Text("Edit") }
                            TextButton(
                                enabled = WebProtectionSetting.APP_TIME_LIMITS !in webLocks,
                                onClick = { saveAppLimit(limit.packageName, null) }
                            ) { Text("Remove") }
                        }
                    }
                    appTimeError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    webProtectionError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (section == SettingsSection.DEVICE_CONTROLS) items(DeviceControl.entries, key = { it.key }) { control ->
                val active = locks.firstOrNull { it.control == control }
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = active != null || control in selected,
                        onCheckedChange = { checked ->
                            if (active == null) {
                                selected = if (checked) selected + control else selected - control
                                if (checked && control == DeviceControl.APP_INSTALLS) {
                                    days = days.coerceAtMost(MAX_UPDATE_FRIENDLY_APP_CHANGE_DAYS)
                                }
                                if (!checked && control == DeviceControl.APP_INSTALLS) {
                                    fullAppChangeBlock = false
                                }
                            }
                        }
                    )
                    Column(Modifier.weight(1f)) {
                                    Text(control.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                    Text(
                                        if (active != null) {
                                            // Memoize per lock — only recomputes when active lock object changes
                                            val hoursLeft = remember(active) {
                                                TimeUnit.MILLISECONDS.toHours(ControlManager.remainingMillis(context, active))
                                            }
                                            "Active for ${hoursLeft}h"
                                        }
                                        else "Not active",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                    if (active != null) {
                        IconButton(onClick = { unlockTarget = control; phrase = ""; error = null }) {
                            Icon(
                                Icons.Filled.LockOpen,
                                contentDescription = "Unlock ${control.title}",
                                tint = TextSecondary
                            )
                        }
                    }
                }
            }
            if (section == SettingsSection.DEVICE_CONTROLS) item {
                Spacer(Modifier.height(12.dp))
                if (DeviceControl.APP_INSTALLS in selected) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Full install block", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Also blocks app updates and all installs and uninstalls.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                        Switch(
                            checked = fullAppChangeBlock,
                            onCheckedChange = {
                                fullAppChangeBlock = it
                                if (it) days = days.coerceAtMost(MAX_FULL_APP_CHANGE_BLOCK_DAYS)
                            }
                        )
                    }
                    HorizontalDivider(color = VaultDivider)
                }
                if (DeviceControl.APP_INSTALLS in selected) {
                    Text(
                        if (fullAppChangeBlock) {
                            "Maximum duration for full install block: $MAX_FULL_APP_CHANGE_BLOCK_DAYS days"
                        } else {
                            "Maximum duration with updates available: $MAX_UPDATE_FRIENDLY_APP_CHANGE_DAYS days"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                Text("Lock duration: $days day(s)")
                Slider(
                    value = days.toFloat(),
                    onValueChange = { days = it.toInt().coerceIn(MIN_SEAL_DURATION_DAYS, maximumControlDays) },
                    valueRange = MIN_SEAL_DURATION_DAYS.toFloat()..maximumControlDays.toFloat()
                )
                Button(
                    enabled = selected.isNotEmpty() && isDeviceOwner && !controlsBusy,
                    onClick = {
                        scope.launch {
                            controlsBusy = true
                            controlError = null
                            try {
                                preparedControls = withContext(Dispatchers.IO) {
                                    ControlManager.prepare(
                                        context,
                                        selected,
                                        days,
                                        fullAppChangeBlock
                                    )
                                }
                                selected = emptySet()
                                step = 1
                            } catch (error: Exception) {
                                controlError = error.message ?: "Could not prepare the selected device controls."
                            } finally {
                                controlsBusy = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (controlsBusy) "Preparing controls..." else "Lock selected controls",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
    }

    webDetailsTarget?.let { setting ->
    WebProtectionDetailsDialog(
        setting = setting,
        enabled = when (setting) {
            WebProtectionSetting.ADULT_SITES -> blockAdultSites
            WebProtectionSetting.CHROME_ONLY -> blockBypassApps
            WebProtectionSetting.SEALED_APP_WEBSITES -> blockSealedAppSites
            WebProtectionSetting.CHROME_INCOGNITO -> blockChromeIncognito
            WebProtectionSetting.APP_TIME_LIMITS -> appTimeLimitsEnabled
        },
        locked = setting in webLocks,
        controlsEnabled = isDeviceOwner && webLocksLoaded && !webProtectionBusy,
        busy = webProtectionBusy || adultDomainBusy,
        onDismiss = { webDetailsTarget = null },
        onManageDomains = { openAdultDomainEditor() },
        onAddRecoveryKey = {
            webDetailsTarget = null
            prepareWebLock(setting)
        },
        onUnlock = {
            webDetailsTarget = null
            webUnlockTarget = setting
            webRecoveryPhrase = ""
            webLockError = null
        }
    )
    }

    unlockTarget?.let { control ->
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
                PageHeader(title = "Unlock ${control.title}", onBack = { unlockTarget = null })
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        "Enter the recovery phrase saved when this control was locked.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    SettingsCard {
                        OutlinedTextField(
                            value = phrase,
                            onValueChange = { phrase = it },
                            label = { Text("Recovery phrase") },
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            minLines = 4
                        )
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                Button(
                    onClick = {
                        if (ControlManager.unlock(context, control, phrase)) {
                            unlockTarget = null
                            refresh()
                        } else {
                            error = "Incorrect recovery phrase"
                        }
                    },
                    enabled = phrase.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BrassPrimary)
                ) {
                    Text("Unlock", color = MaterialTheme.colorScheme.background)
                }
            }
        }
    }

    if (showAdultDomainEditor) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
                PageHeader(
                    title = "Custom Chrome blocks",
                    onBack = {
                        if (!adultDomainBusy) {
                            showAdultDomainEditor = false
                            pendingAdultDomains = emptySet()
                            originalAdultDomains = emptySet()
                        }
                    }
                )
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        "Add domains that should be blocked in managed Chrome while protection is on.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = adultDomainInput,
                            onValueChange = { adultDomainInput = it },
                            label = { Text("Website domain") },
                            placeholder = { Text("example.com") },
                            enabled = !adultDomainBusy,
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { stageAdultDomain() },
                            enabled = adultDomainInput.isNotBlank() && !adultDomainBusy,
                            colors = ButtonDefaults.buttonColors(containerColor = BrassPrimary)
                        ) {
                            Text("Add", color = MaterialTheme.colorScheme.background)
                        }
                    }
                    SettingsCard {
                        Column {
                            Text(
                                "Saved domains",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(16.dp)
                            )
                            if (pendingAdultDomains.isEmpty()) {
                                Text(
                                    "No custom domains.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextSecondary,
                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                                )
                            } else {
                                pendingAdultDomains.sorted().forEach { domain ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(domain, style = MaterialTheme.typography.bodyLarge)
                                        TextButton(
                                            enabled = !adultDomainBusy,
                                            onClick = { pendingAdultDomains = pendingAdultDomains - domain }
                                        ) {
                                            Text("Remove")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    adultDomainError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Button(
                    enabled = pendingAdultDomains != originalAdultDomains && !adultDomainBusy,
                    onClick = {
                        showAdultDomainReview = true
                        adultDomainConfirmed = false
                        adultDomainError = null
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BrassPrimary)
                ) {
                    Text("Review changes", color = MaterialTheme.colorScheme.background)
                }
            }
        }
    }

    if (showAdultDomainReview) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
                PageHeader(
                    title = "Review Chrome blocks",
                    onBack = {
                        if (!adultDomainBusy) {
                            showAdultDomainReview = false
                            showAdultDomainEditor = true
                        }
                    }
                )
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        "Review the complete list before saving. These rules apply in managed Chrome while adult-site protection is on.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Only the recovery phrase can prevent later editing while the feature is locked.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    SettingsCard {
                        Column(Modifier.padding(16.dp)) {
                            if (pendingAdultDomains.isEmpty()) {
                                Text("The saved domain list will be empty.", color = TextSecondary)
                            } else {
                                pendingAdultDomains.sorted().forEach { domain ->
                                    Text(domain, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 7.dp))
                                }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = adultDomainConfirmed,
                            enabled = !adultDomainBusy,
                            onCheckedChange = { adultDomainConfirmed = it }
                        )
                        Text(
                            "I reviewed the complete list and want to apply these changes.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    adultDomainError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Button(
                    enabled = adultDomainConfirmed &&
                        pendingAdultDomains != originalAdultDomains &&
                        !adultDomainBusy,
                    onClick = { saveAdultDomainBlocks() },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BrassPrimary)
                ) {
                    Text(if (adultDomainBusy) "Applying…" else "Save changes", color = MaterialTheme.colorScheme.background)
                }
            }
        }
    }

    if (showAppPicker) {
        val candidates = launchableApps.filter { app ->
            app.packageName != context.packageName &&
                app.label.contains(appPickerQuery, ignoreCase = true) &&
                appLimits.none { it.packageName == app.packageName } &&
                app.packageName !in VaultManager.blockedPackages(context) &&
                app.packageName !in ControlManager.newlyBlockedPackages(context)
        }
        Dialog(
            onDismissRequest = {
                showAppPicker = false
                appPickerQuery = ""
            },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
                    PageHeader(
                        title = "Choose an app",
                        onBack = {
                            showAppPicker = false
                            appPickerQuery = ""
                        }
                    )
                    OutlinedTextField(
                        value = appPickerQuery,
                        onValueChange = { appPickerQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(50),
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        placeholder = { Text("Search for apps") }
                    )
                    Spacer(Modifier.height(16.dp))
                    if (candidates.isEmpty()) {
                        Text(
                            "No eligible apps are available to add.",
                            color = TextSecondary,
                            modifier = Modifier.padding(4.dp)
                        )
                    } else {
                        SettingsCard(modifier = Modifier.weight(1f)) {
                            LazyColumn {
                                items(candidates, key = { it.packageName }) { app ->
                                    TextButton(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                                        onClick = {
                                            showAppPicker = false
                                            appPickerQuery = ""
                                            limitMinutesInput = "10"
                                            limitEditorApp = app
                                        }
                                    ) {
                                        Text(
                                            app.label,
                                            modifier = Modifier.fillMaxWidth(),
                                            style = MaterialTheme.typography.titleMedium,
                                            color = TextPrimary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    limitEditorApp?.let { app ->
        val selectedMinutes = limitMinutesInput.toIntOrNull()?.coerceIn(1, 24 * 60) ?: 10
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                PageHeader(title = "Daily app limit", onBack = { limitEditorApp = null })
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    Text(app.label, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Choose how long this app can be used each day. The allowance resets at local midnight.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    SettingsCard {
                        Column(Modifier.padding(20.dp)) {
                            Text(
                                formatLimitDuration(selectedMinutes),
                                style = MaterialTheme.typography.displaySmall,
                                color = TextPrimary
                            )
                            Text("per day", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                            Spacer(Modifier.height(20.dp))
                            Slider(
                                value = selectedMinutes.toFloat(),
                                onValueChange = {
                                    limitMinutesInput = it.toInt().coerceIn(1, 24 * 60).toString()
                                },
                                valueRange = 1f..(24 * 60).toFloat(),
                                colors = SliderDefaults.colors(
                                    thumbColor = BrassPrimary,
                                    activeTrackColor = BrassPrimary
                                )
                            )
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("1 min", color = TextSecondary)
                                Text("24 hours", color = TextSecondary)
                            }
                        }
                    }
                    Text("Common limits", style = MaterialTheme.typography.titleMedium)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(10, 30, 60, 120).forEach { minutes ->
                            FilterChip(
                                selected = selectedMinutes == minutes,
                                onClick = { limitMinutesInput = minutes.toString() },
                                label = { Text(formatLimitDuration(minutes)) }
                            )
                        }
                    }
                    appTimeError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
                Button(
                    enabled = selectedMinutes in 1..(24 * 60),
                    onClick = {
                        saveAppLimit(app.packageName, selectedMinutes)
                        limitEditorApp = null
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BrassPrimary)
                ) {
                    Text("Save limit", color = MaterialTheme.colorScheme.background)
                }
            }
        }
    }

    preparedWebLock?.let { webLock ->
        val clipboard = LocalClipboardManager.current
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
                PageHeader(
                    title = "Lock ${webLock.lock.setting.title}",
                    onBack = {
                        preparedWebLock = null
                        webLockKeySaved = false
                    }
                )
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    Text(
                        "Save the recovery phrase before locking this protection. The protection stays on after the recovery timer ends.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    SettingsCard {
                        Column(Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Lock duration", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "$webLockDurationDays days",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                    }
                    Slider(
                        value = webLockDurationDays.toFloat(),
                        onValueChange = {
                            webLockDurationDays = it.toInt().coerceIn(
                                WebProtectionLockManager.MIN_LOCK_DAYS,
                                WebProtectionLockManager.MAX_LOCK_DAYS
                            )
                        },
                        valueRange = WebProtectionLockManager.MIN_LOCK_DAYS.toFloat()..
                            WebProtectionLockManager.MAX_LOCK_DAYS.toFloat(),
                        colors = SliderDefaults.colors(
                            thumbColor = BrassPrimary,
                            activeTrackColor = BrassPrimary
                        )
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "${WebProtectionLockManager.MIN_LOCK_DAYS} days",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                        Text(
                            "${WebProtectionLockManager.MAX_LOCK_DAYS} days",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                        }
                    }
                    SettingsCard {
                        Column(Modifier.padding(18.dp)) {
                            Text("Recovery phrase", style = MaterialTheme.typography.titleMedium)
                    Text(
                        webLock.recoveryPhrase,
                        style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                        color = TextPrimary,
                        modifier = Modifier.padding(vertical = 14.dp)
                    )
                    TextButton(
                        onClick = {
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(webLock.recoveryPhrase))
                        }
                    ) { Text("Copy recovery phrase") }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = webLockKeySaved,
                            onCheckedChange = { webLockKeySaved = it }
                        )
                        Text("I saved this phrase somewhere safe.", color = TextSecondary)
                    }
                }
                Button(
                    enabled = webLockKeySaved && !webProtectionBusy,
                    onClick = {
                        scope.launch {
                            webProtectionBusy = true
                            webLockError = null
                            try {
                                withContext(Dispatchers.IO) {
                                    WebProtectionLockManager.commitLock(
                                        context,
                                        webLock,
                                        webLockDurationDays
                                    )
                                }
                                webLocks = withContext(Dispatchers.IO) {
                                    WebProtectionLockManager.activeLocks(context)
                                }
                                preparedWebLock = null
                                webLockKeySaved = false
                            } catch (failure: Exception) {
                                webLockError = failure.message ?: "Could not lock this protection."
                            } finally {
                                webProtectionBusy = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BrassPrimary)
                ) { Text("Save key and lock", color = MaterialTheme.colorScheme.background) }
            }
        }
    }

    webUnlockTarget?.let { setting ->
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
                PageHeader(
                    title = "Unlock ${setting.title}",
                    onBack = {
                        webUnlockTarget = null
                        webRecoveryPhrase = ""
                        webLockError = null
                    }
                )
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        "Enter the recovery phrase saved when this setting was locked.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    SettingsCard {
                        OutlinedTextField(
                            value = webRecoveryPhrase,
                            onValueChange = { webRecoveryPhrase = it },
                            label = { Text("Recovery phrase") },
                            enabled = !webProtectionBusy,
                            minLines = 4,
                            modifier = Modifier.fillMaxWidth().padding(16.dp)
                        )
                    }
                    webLockError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                Button(
                    enabled = webRecoveryPhrase.isNotBlank() && !webProtectionBusy,
                    onClick = { unlockWebSetting(setting) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BrassPrimary)
                ) {
                    Text(if (webProtectionBusy) "Checking..." else "Unlock", color = MaterialTheme.colorScheme.background)
                }
            }
        }
    }
}

@Composable
private fun WebProtectionSettingCard(
    title: String,
    summary: String,
    checked: Boolean,
    locked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onOpenDetails: () -> Unit
) {
    SettingsCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clickable(onClick = onOpenDetails)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
            Switch(
                checked = checked,
                enabled = enabled && !locked,
                onCheckedChange = onCheckedChange
            )
        }
    }
}

@Composable
private fun WebProtectionDetailsDialog(
    setting: WebProtectionSetting,
    enabled: Boolean,
    locked: Boolean,
    controlsEnabled: Boolean,
    busy: Boolean,
    onDismiss: () -> Unit,
    onManageDomains: () -> Unit,
    onAddRecoveryKey: () -> Unit,
    onUnlock: () -> Unit
) {
    var showInfo by remember { mutableStateOf(false) }
    val details = when (setting) {
        WebProtectionSetting.ADULT_SITES -> WebProtectionDetails(
            description = "Uses Android Private DNS to filter adult domains across the device. Chrome also uses SafeSearch and has secure DNS disabled.",
            scope = "Device-wide filter · Chrome policies",
            note = "This filters domains, not individual pages or content hosted on allowed sites."
        )
        WebProtectionSetting.CHROME_ONLY -> WebProtectionDetails(
            description = "Hides other installed browsers and common VPN apps, and blocks VPN setup in Android settings.",
            scope = "Android app visibility · VPN settings",
            note = "Some proxies, VPNs, or apps with built-in encrypted DNS may not be detected."
        )
        WebProtectionSetting.SEALED_APP_WEBSITES -> WebProtectionDetails(
            description = "Blocks known websites for sealed apps in managed Google Chrome, including any custom domains saved to a seal.",
            scope = "Managed Google Chrome only",
            note = "Other browsers are unaffected. Saved custom seal domains remain blocked until their seal ends."
        )
        WebProtectionSetting.CHROME_INCOGNITO -> WebProtectionDetails(
            description = "Controls private browsing in managed Google Chrome independently from website filtering.",
            scope = "Managed Google Chrome only",
            note = "When off, this setting explicitly allows Incognito even if other web protections are enabled. Chrome must be installed and Fortress Vault must be the device owner."
        )
        WebProtectionSetting.APP_TIME_LIMITS -> WebProtectionDetails(
            description = "Suspends each selected app when Android reports that it has reached its daily foreground-use allowance.",
            scope = "Selected apps · resets at local midnight",
            note = "Requires Android Usage access and device-owner status. The app remains suspended until the next local day."
        )
    }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(Modifier.fillMaxSize()) {
                PageHeader(title = setting.title, onBack = onDismiss)

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                        .padding(top = 12.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Shield, contentDescription = null, tint = BrassPrimary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (enabled) "Protection is on" else "Protection is off",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(details.scope, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                            }
                            Box {
                                IconButton(onClick = { showInfo = !showInfo }) {
                                    Icon(Icons.Filled.Info, contentDescription = "More information", tint = TextSecondary)
                                }
                                DropdownMenu(
                                    expanded = showInfo,
                                    onDismissRequest = { showInfo = false },
                                    modifier = Modifier.widthIn(max = 300.dp)
                                ) {
                                    Surface(color = SteelSurfaceHigh) {
                                        Text(
                                            details.description,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = TextSecondary,
                                            modifier = Modifier.padding(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    if (setting == WebProtectionSetting.ADULT_SITES) {
                        SettingsCard(
                            modifier = Modifier
                                .clickable(enabled = !locked && controlsEnabled && !busy) {
                                    onManageDomains()
                                }
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                    Icon(
                                        imageVector = Icons.Filled.Language,
                                        contentDescription = null,
                                        tint = BrassPrimary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(Modifier.width(14.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "Custom Chrome blocks",
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                    if (!locked) {
                                        Icon(
                                            Icons.Filled.ChevronRight,
                                            contentDescription = "Open custom Chrome blocks",
                                            tint = TextSecondary
                                        )
                                    }
                                }
                        }
                    }

                    if (locked) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            IconButton(
                                enabled = controlsEnabled && !busy,
                                onClick = onUnlock
                            ) {
                                Icon(
                                    Icons.Filled.LockOpen,
                                    contentDescription = "Unlock feature",
                                    tint = TextSecondary
                                )
                            }
                        }
                    } else {
                        OutlinedButton(
                            enabled = controlsEnabled && enabled && !busy,
                            onClick = onAddRecoveryKey,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Lock feature")
                        }
                    }
                }

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BrassPrimary,
                        contentColor = MaterialTheme.colorScheme.background
                    )
                ) {
                    Text("Done")
                }
            }
    }
}

private data class WebProtectionDetails(
    val description: String,
    val scope: String,
    val note: String
)

@Composable
private fun RecoveryPhraseCard(index: Int, control: DeviceControl, phrase: String) {
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "%02d".format(index),
                    style = MaterialTheme.typography.labelLarge,
                    color = BrassPrimary
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(control.title, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                    Text("Recovery phrase", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                phrase,
                style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                color = BrassPrimary,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(phrase)) },
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)
            ) {
                Text("Copy phrase", color = TextPrimary)
            }
        HorizontalDivider(color = VaultDivider)
    }
}

private fun DeviceControl.effect(): String = when (this) {
    DeviceControl.USB_DEBUGGING -> "USB debugging is disabled until this control expires or is unlocked."
    DeviceControl.USER_ACCOUNTS -> "Adding users and switching user accounts is disabled until this control expires or is unlocked."
    DeviceControl.APP_INSTALLS -> "App updates remain available. Uninstalling apps is blocked, and newly installed apps are suspended and removed automatically while this control is active."
}

private fun formatLimitDuration(minutes: Int): String {
    val hours = minutes / 60
    val remainingMinutes = minutes % 60
    return when {
        hours == 0 -> "$remainingMinutes min"
        remainingMinutes == 0 -> "$hours hr"
        else -> "$hours hr $remainingMinutes min"
    }
}
