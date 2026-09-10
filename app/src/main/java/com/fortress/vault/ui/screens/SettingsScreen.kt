package com.fortress.vault.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.fortress.vault.core.ControlManager
import com.fortress.vault.core.DeviceControl
import com.fortress.vault.core.PreparedControls
import com.fortress.vault.core.MAX_SEAL_DURATION_DAYS
import com.fortress.vault.core.MAX_FULL_APP_CHANGE_BLOCK_DAYS
import com.fortress.vault.core.MAX_UPDATE_FRIENDLY_APP_CHANGE_DAYS
import com.fortress.vault.core.MIN_SEAL_DURATION_DAYS
import com.fortress.vault.ui.theme.BrassPrimary
import com.fortress.vault.ui.theme.EmberRed
import com.fortress.vault.ui.theme.SteelSurfaceHigh
import com.fortress.vault.ui.theme.TextPrimary
import com.fortress.vault.ui.theme.TextSecondary
import com.fortress.vault.ui.theme.VaultDivider
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var locks by remember { mutableStateOf(ControlManager.activeLocks(context)) }
    var selected by remember { mutableStateOf(emptySet<DeviceControl>()) }
    var fullAppChangeBlock by remember { mutableStateOf(false) }
    var days by remember { mutableStateOf(1) }
    var preparedControls by remember { mutableStateOf<PreparedControls?>(null) }
    var step by remember { mutableStateOf(0) }
    var unlockTarget by remember { mutableStateOf<DeviceControl?>(null) }
    var phrase by remember { mutableStateOf("") }
    var hasWrittenDown by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val maximumControlDays = when {
        DeviceControl.APP_INSTALLS !in selected -> MAX_SEAL_DURATION_DAYS
        fullAppChangeBlock -> MAX_FULL_APP_CHANGE_BLOCK_DAYS
        else -> MAX_UPDATE_FRIENDLY_APP_CHANGE_DAYS
    }

    fun refresh() { locks = ControlManager.activeLocks(context) }

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
                        ControlManager.commit(context, prepared)
                        preparedControls = null
                        step = 0
                        refresh()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BrassPrimary)
            ) { Text("Lock these controls", color = MaterialTheme.colorScheme.background) }
        }
    } else {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Device Controls", style = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = onBack) { Text("Back") }
        }
        Text("Each control has its own timer and recovery phrase.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(DeviceControl.entries) { control ->
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
                        Text(control.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (active != null) {
                                "Active for ${TimeUnit.MILLISECONDS.toHours(ControlManager.remainingMillis(context, active))}h"
                            }
                            else "Not active",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (active != null) {
                        TextButton(onClick = { unlockTarget = control; phrase = ""; error = null }) { Text("Unlock") }
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
                    enabled = selected.isNotEmpty(),
                    onClick = {
                        scope.launch {
                            preparedControls = ControlManager.prepare(
                                context,
                                selected,
                                days,
                                fullAppChangeBlock
                            )
                            selected = emptySet()
                            step = 1
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Lock selected controls") }
            }
        }
    }
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
