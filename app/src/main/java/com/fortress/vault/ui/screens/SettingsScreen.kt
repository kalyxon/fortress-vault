package com.fortress.vault.ui.screens

import android.app.admin.DevicePolicyManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fortress.vault.core.ControlManager
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
    val adultDomainBlockCount: Int,
    val webLocks: Map<WebProtectionSetting, WebProtectionLock>,
    val chromePolicyApplied: Boolean
)

@Composable
fun SettingsScreen(onBack: () -> Unit) {
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
                    adultDomainBlockCount = WebProtectionManager.adultDomainBlockCount(context),
                    webLocks = WebProtectionLockManager.activeLocks(context),
                    chromePolicyApplied = policyApplied
                )
            }
            isDeviceOwner = settings.isDeviceOwner
            blockAdultSites = settings.blockAdultSites
            blockBypassApps = settings.blockBypassApps
            blockSealedAppSites = settings.blockSealedAppSites
            adultDomainBlockCount = settings.adultDomainBlockCount
            webLocks = settings.webLocks
            webLocksLoaded = true
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
                    }
                }
                if (!applied) {
                    webProtectionError = failureMessage ?: when (setting) {
                        WebProtectionSetting.ADULT_SITES ->
                            "Android could not apply the DNS policy. Check device-owner status and DNS connectivity."
                        WebProtectionSetting.CHROME_ONLY ->
                            "Android could not restrict browsers or VPN setup. Check device-owner status."
                        WebProtectionSetting.SEALED_APP_WEBSITES ->
                            "Android could not apply Chrome's website policy. Check device-owner status and Chrome."
                    }
                } else {
                    when (setting) {
                        WebProtectionSetting.ADULT_SITES -> blockAdultSites = enabled
                        WebProtectionSetting.CHROME_ONLY -> blockBypassApps = enabled
                        WebProtectionSetting.SEALED_APP_WEBSITES -> blockSealedAppSites = enabled
                    }
                }
            } catch (failure: Exception) {
                webProtectionError = failure.message ?: "Android could not apply this protection."
            } finally {
                webProtectionBusy = false
            }
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
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = EmberRed.copy(alpha = 0.12f)),
                    border = BorderStroke(1.dp, EmberRed.copy(alpha = 0.28f)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("READY TO LOCK", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${prepared.locks.size} device control${if (prepared.locks.size == 1) "" else "s"} selected",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
                Text("Selected controls", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                prepared.locks.keys.forEachIndexed { index, control ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, VaultDivider),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("CONTROL ${"%02d".format(index + 1)}", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                            Spacer(Modifier.height(6.dp))
                            Text(control.title, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                if (control == DeviceControl.APP_INSTALLS) {
                                    if (prepared.fullAppChangeBlock) {
                                        "FULL BLOCK: new installs, app updates, and all user uninstalls are blocked."
                                    } else {
                                        "UPDATE-FRIENDLY: existing apps update normally; new apps are suspended and removed."
                                    }
                                } else {
                                    control.effect()
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, VaultDivider),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
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
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SteelSurfaceHigh.copy(alpha = 0.55f)),
                    border = BorderStroke(1.dp, VaultDivider),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Your recovery keys", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Each control has a different key. Store both phrases somewhere private and offline.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                }
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
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SteelSurfaceHigh.copy(alpha = 0.55f)),
                    border = BorderStroke(1.dp, VaultDivider),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Final check", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${prepared.locks.size} control${if (prepared.locks.size == 1) "" else "s"} will be locked for $days day(s).",
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
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "The selected controls stay active until the timer ends or their individual recovery phrase is entered.",
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Device controls", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = onBack) {
                Text("Back", style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (!isDeviceOwner) {
            Text(
                "Device controls and DNS filtering require Fortress Vault to be the device owner. Complete the one-time setup before enabling them.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
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
        Spacer(Modifier.height(16.dp))
        LazyColumn(Modifier.weight(1f)) {
            item {
                Text("Web protection", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                WebProtectionSettingCard(
                    title = WebProtectionSetting.ADULT_SITES.title,
                    summary = "Device-wide adult-domain filter.",
                    checked = blockAdultSites,
                    locked = WebProtectionSetting.ADULT_SITES in webLocks,
                    unlockAtMillis = webLocks[WebProtectionSetting.ADULT_SITES]?.unlockAtMillis,
                    enabled = isDeviceOwner && webLocksLoaded && !webProtectionBusy,
                    onCheckedChange = { changeWebSetting(WebProtectionSetting.ADULT_SITES, it) },
                    onOpenDetails = { webDetailsTarget = WebProtectionSetting.ADULT_SITES }
                )
                WebProtectionSettingCard(
                    title = "Allow only Chrome and block VPN apps",
                    summary = "Restrict browsers and VPN apps.",
                    checked = blockBypassApps,
                    locked = WebProtectionSetting.CHROME_ONLY in webLocks,
                    unlockAtMillis = webLocks[WebProtectionSetting.CHROME_ONLY]?.unlockAtMillis,
                    enabled = isDeviceOwner && webLocksLoaded && !webProtectionBusy,
                    onCheckedChange = { changeWebSetting(WebProtectionSetting.CHROME_ONLY, it) },
                    onOpenDetails = { webDetailsTarget = WebProtectionSetting.CHROME_ONLY }
                )
                WebProtectionSettingCard(
                    title = WebProtectionSetting.SEALED_APP_WEBSITES.title,
                    summary = "Block sealed-app domains in Chrome.",
                    checked = blockSealedAppSites,
                    locked = WebProtectionSetting.SEALED_APP_WEBSITES in webLocks,
                    unlockAtMillis = webLocks[WebProtectionSetting.SEALED_APP_WEBSITES]?.unlockAtMillis,
                    enabled = isDeviceOwner && webLocksLoaded && !webProtectionBusy,
                    onCheckedChange = { changeWebSetting(WebProtectionSetting.SEALED_APP_WEBSITES, it) },
                    onOpenDetails = { webDetailsTarget = WebProtectionSetting.SEALED_APP_WEBSITES }
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
                Text(
                    "Website rules apply in Chrome.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 6.dp)
                )
                HorizontalDivider(color = VaultDivider, modifier = Modifier.padding(vertical = 16.dp))
            }
            items(DeviceControl.entries, key = { it.key }) { control ->
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
                        TextButton(onClick = { unlockTarget = control; phrase = ""; error = null }) {
                            Text("Unlock", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            item {
                Spacer(Modifier.height(12.dp))
                if (DeviceControl.APP_INSTALLS in selected) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, VaultDivider),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Full install block", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Also blocks app updates and prevents all installs and uninstalls.",
                                    style = MaterialTheme.typography.bodyMedium,
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
                    }
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
        },
        locked = setting in webLocks,
        unlockAtMillis = webLocks[setting]?.unlockAtMillis,
        savedDomainCount = adultDomainBlockCount,
        controlsEnabled = isDeviceOwner && webLocksLoaded && !webProtectionBusy,
        busy = webProtectionBusy || adultDomainBusy,
        onDismiss = { webDetailsTarget = null },
        onToggle = { changeWebSetting(setting, it) },
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
        AlertDialog(
            onDismissRequest = { unlockTarget = null },
            title = { Text("Unlock ${control.title}") },
            text = {
                Column {
                    OutlinedTextField(value = phrase, onValueChange = { phrase = it }, label = { Text("Recovery phrase") })
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (ControlManager.unlock(context, control, phrase)) { unlockTarget = null; refresh() }
                    else error = "Incorrect recovery phrase"
                }) { Text("Unlock") }
            },
            dismissButton = { TextButton(onClick = { unlockTarget = null }) { Text("Cancel") } }
        )
    }

    if (showAdultDomainEditor) {
        AlertDialog(
            onDismissRequest = {
                if (!adultDomainBusy) {
                    showAdultDomainEditor = false
                    pendingAdultDomains = emptySet()
                    originalAdultDomains = emptySet()
                }
            },
            title = { Text("Manage Chrome blocks") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "These domains are blocked in managed Chrome while adult-site protection is on.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    OutlinedTextField(
                        value = adultDomainInput,
                        onValueChange = { adultDomainInput = it },
                        label = { Text("Website domain") },
                        placeholder = { Text("example.com") },
                        enabled = !adultDomainBusy,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        onClick = { stageAdultDomain() },
                        enabled = adultDomainInput.isNotBlank() && !adultDomainBusy,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Add domain")
                    }
                    Text(
                        "DOMAINS",
                        style = MaterialTheme.typography.labelLarge,
                        color = BrassPrimary
                    )
                    if (pendingAdultDomains.isEmpty()) {
                        Text(
                            "No custom domains.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    } else {
                        Column(
                            modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                        ) {
                            pendingAdultDomains.sorted().forEach { domain ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
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
                                HorizontalDivider(color = VaultDivider)
                            }
                        }
                    }
                    adultDomainError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = pendingAdultDomains != originalAdultDomains && !adultDomainBusy,
                    onClick = {
                        showAdultDomainEditor = false
                        showAdultDomainReview = true
                        adultDomainConfirmed = false
                        adultDomainError = null
                    }
                ) {
                    Text("Review changes")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !adultDomainBusy,
                    onClick = {
                        showAdultDomainEditor = false
                        pendingAdultDomains = emptySet()
                        originalAdultDomains = emptySet()
                        adultDomainError = null
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showAdultDomainReview) {
        AlertDialog(
            onDismissRequest = {
                if (!adultDomainBusy) {
                    showAdultDomainReview = false
                    showAdultDomainEditor = true
                }
            },
            title = { Text("Confirm Chrome blocks") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "Review the complete list that will be saved. These rules apply in managed Google Chrome only while adult-site protection is on.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Only adult-site protection's recovery key can prevent later viewing or editing of this list.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    if (pendingAdultDomains.isEmpty()) {
                        Text("The saved domain list will be empty.", style = MaterialTheme.typography.bodyLarge)
                    } else Column(
                        modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                    ) {
                        pendingAdultDomains.sorted().forEach { domain ->
                            Text(
                                domain,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(vertical = 5.dp)
                            )
                            HorizontalDivider(color = VaultDivider)
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
            },
            confirmButton = {
                Button(
                    enabled = adultDomainConfirmed &&
                        pendingAdultDomains != originalAdultDomains &&
                        !adultDomainBusy,
                    onClick = { saveAdultDomainBlocks() }
                ) {
                    Text(if (adultDomainBusy) "Applying…" else "Confirm changes")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !adultDomainBusy,
                    onClick = {
                        showAdultDomainReview = false
                        showAdultDomainEditor = true
                    }
                ) {
                    Text("Back to list")
                }
            }
        )
    }

    preparedWebLock?.let { webLock ->
        val clipboard = LocalClipboardManager.current
        AlertDialog(
            onDismissRequest = {
                preparedWebLock = null
                webLockKeySaved = false
            },
            title = { Text("Lock ${webLock.lock.setting.title}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "The protection stays on after this timer ends. Only the recovery lock expires; the setting then becomes editable."
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Lock duration", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "$webLockDurationDays days",
                            style = MaterialTheme.typography.titleMedium,
                            color = BrassPrimary
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
                    Text(
                        webLock.recoveryPhrase,
                        style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                        color = BrassPrimary
                    )
                    TextButton(
                        onClick = {
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(webLock.recoveryPhrase))
                        }
                    ) { Text("Copy recovery phrase") }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = webLockKeySaved,
                            onCheckedChange = { webLockKeySaved = it }
                        )
                        Text("I saved this phrase somewhere safe.")
                    }
                }
            },
            confirmButton = {
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
                    }
                ) { Text("Save key and lock") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        preparedWebLock = null
                        webLockKeySaved = false
                    }
                ) { Text("Cancel") }
            }
        )
    }

    webUnlockTarget?.let { setting ->
        AlertDialog(
            onDismissRequest = {
                webUnlockTarget = null
                webRecoveryPhrase = ""
                webLockError = null
            },
            title = { Text("Unlock ${setting.title}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter the recovery phrase saved when this setting was locked.")
                    OutlinedTextField(
                        value = webRecoveryPhrase,
                        onValueChange = { webRecoveryPhrase = it },
                        label = { Text("Recovery phrase") },
                        enabled = !webProtectionBusy,
                        singleLine = false
                    )
                    webLockError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = webRecoveryPhrase.isNotBlank() && !webProtectionBusy,
                    onClick = { unlockWebSetting(setting) }
                ) { Text(if (webProtectionBusy) "Checking..." else "Unlock") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        webUnlockTarget = null
                        webRecoveryPhrase = ""
                        webLockError = null
                    }
                ) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun WebProtectionSettingCard(
    title: String,
    summary: String,
    checked: Boolean,
    locked: Boolean,
    unlockAtMillis: Long?,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onOpenDetails: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clickable(onClick = onOpenDetails),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, if (locked) BrassPrimary.copy(alpha = 0.6f) else VaultDivider),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(summary, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                }
                Switch(
                    checked = checked,
                    enabled = enabled && !locked,
                    onCheckedChange = onCheckedChange
                )
                IconButton(onClick = onOpenDetails) {
                    Icon(
                        imageVector = Icons.Filled.ChevronRight,
                        contentDescription = "Protection details",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (locked) {
                Text(
                    expiryLabel(unlockAtMillis),
                    style = MaterialTheme.typography.bodyMedium,
                    color = BrassPrimary,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun WebProtectionDetailsDialog(
    setting: WebProtectionSetting,
    enabled: Boolean,
    locked: Boolean,
    unlockAtMillis: Long?,
    savedDomainCount: Int,
    controlsEnabled: Boolean,
    busy: Boolean,
    onDismiss: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onManageDomains: () -> Unit,
    onAddRecoveryKey: () -> Unit,
    onUnlock: () -> Unit
) {
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
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(0.dp),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to settings")
                    }
                    Column(Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(
                            "WEB PROTECTION",
                            style = MaterialTheme.typography.labelLarge,
                            color = BrassPrimary
                        )
                        Text(
                            setting.title,
                            style = MaterialTheme.typography.bodyMedium,
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

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                        .padding(top = 12.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(24.dp),
                        border = BorderStroke(1.dp, VaultDivider)
                    ) {
                        Column(Modifier.padding(20.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .background(BrassPrimary.copy(alpha = 0.12f), RoundedCornerShape(14.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Filled.Shield,
                                        contentDescription = null,
                                        tint = BrassPrimary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Spacer(Modifier.width(14.dp))
                                Column {
                                    Text(
                                        if (enabled) "Protection is on" else "Protection is off",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        details.scope,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = BrassPrimary,
                                        modifier = Modifier.padding(top = 3.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.height(18.dp))
                            Text(
                                details.description,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, VaultDivider)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Protection",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    if (locked) "Locked with a recovery key" else "Turn this protection on or off",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                            Switch(
                                checked = enabled,
                                enabled = controlsEnabled && !locked,
                                onCheckedChange = onToggle
                            )
                        }
                    }

                    if (setting == WebProtectionSetting.ADULT_SITES) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(18.dp),
                            border = BorderStroke(1.dp, VaultDivider)
                        ) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                )
                                {
                                    Icon(
                                        imageVector = Icons.Filled.Language,
                                        contentDescription = null,
                                        tint = BrassPrimary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "Custom Chrome blocks",
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            if (locked) {
                                                "List hidden while recovery lock is active."
                                            } else if (savedDomainCount == 0) {
                                                "Add domains the filter misses."
                                            } else {
                                                "$savedDomainCount saved"
                                            },
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = TextSecondary
                                        )
                                    }
                                    if (!locked) {
                                        TextButton(
                                            enabled = controlsEnabled && !busy,
                                            onClick = onManageDomains
                                        ) {
                                            Text(if (savedDomainCount == 0) "Add" else "Manage")
                                        }
                                    }
                                }
                                HorizontalDivider(color = VaultDivider)
                                Text(
                                    "Custom blocks apply in Google Chrome only while this protection is on.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextSecondary
                                )
                            }
                        }
                    }

                    if (locked) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Column(Modifier.padding(18.dp)) {
                                Text(
                                    "RECOVERY LOCK",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = BrassPrimary
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    expiryLabel(unlockAtMillis),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    "The protection stays on when the recovery lock expires.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextSecondary,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                                TextButton(
                                    enabled = controlsEnabled && !busy,
                                    onClick = onUnlock,
                                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)
                                ) {
                                    Text("Unlock with recovery phrase")
                                }
                            }
                        }
                    } else {
                        OutlinedButton(
                            enabled = controlsEnabled && enabled && !busy,
                            onClick = onAddRecoveryKey,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Add recovery key")
                        }
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(
                            details.note,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }

                Surface(color = MaterialTheme.colorScheme.background, shadowElevation = 12.dp) {
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
    }
}

private data class WebProtectionDetails(
    val description: String,
    val scope: String,
    val note: String
)

@Composable
private fun expiryLabel(unlockAtMillis: Long?): String {
    val context = LocalContext.current
    val until = unlockAtMillis ?: 0L
    if (until == 0L) return "Recovery protected"
    val daysLeft = ((until - TimeKeeper.estimateCurrentTrustedTimeMillis(context))
        .coerceAtLeast(0L) + TimeUnit.DAYS.toMillis(1) - 1) / TimeUnit.DAYS.toMillis(1)
    return "Recovery key · $daysLeft days left"
}

@Composable
private fun RecoveryPhraseCard(index: Int, control: DeviceControl, phrase: String) {
    val clipboard = LocalClipboardManager.current
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, VaultDivider),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SteelSurfaceHigh, RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 16.dp)
            ) {
                Text(
                    phrase,
                    style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    color = BrassPrimary
                )
            }
            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(phrase)) },
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)
            ) {
                Text("Copy phrase", color = TextPrimary)
            }
        }
    }
}

private fun DeviceControl.effect(): String = when (this) {
    DeviceControl.USB_DEBUGGING -> "USB debugging is disabled until this control expires or is unlocked."
    DeviceControl.USER_ACCOUNTS -> "Adding users and switching user accounts is disabled until this control expires or is unlocked."
    DeviceControl.APP_INSTALLS -> "App updates remain available. Uninstalling apps is blocked, and newly installed apps are suspended and removed automatically while this control is active."
}
