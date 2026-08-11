package com.fortress.vault.ui.screens

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
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
import com.fortress.vault.core.RecoveryPhraseGenerator
import com.fortress.vault.core.VaultManager
import com.fortress.vault.ui.theme.BrassPrimary
import com.fortress.vault.ui.theme.EmberRed
import kotlinx.coroutines.launch

private data class InstalledApp(val label: String, val packageName: String)

@Composable
fun SealVaultScreen(onSealed: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var step by remember { mutableStateOf(SealStep.SELECT_APPS) }
    var selectedPackages by remember { mutableStateOf(setOf<String>()) }
    var durationDays by remember { mutableStateOf(30) }
    var recoveryPhrase by remember { mutableStateOf<String?>(null) }
    var hasWrittenDown by remember { mutableStateOf(false) }
    var isSealing by remember { mutableStateOf(false) }

    val installedApps = remember { loadLaunchableApps(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
    ) {
        when (step) {
            SealStep.SELECT_APPS -> {
                Text("Choose What To Seal", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(16.dp))
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(installedApps) { app ->
                        AppRow(
                            app = app,
                            checked = app.packageName in selectedPackages,
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
                    "There is no undo once sealed, except the emergency phrase.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(32.dp))
                Text("$durationDays days", style = MaterialTheme.typography.displayLarge, color = BrassPrimary)
                Slider(
                    value = durationDays.toFloat(),
                    onValueChange = { durationDays = it.toInt() },
                    valueRange = 1f..90f,
                    colors = SliderDefaults.colors(thumbColor = BrassPrimary, activeTrackColor = BrassPrimary)
                )
                Spacer(Modifier.weight(1f))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { step = SealStep.SELECT_APPS }) { Text("Back") }
                    Button(
                        enabled = !isSealing,
                        onClick = {
                            isSealing = true
                            recoveryPhrase = RecoveryPhraseGenerator.generate()
                            isSealing = false
                            step = SealStep.SHOW_RECOVERY_PHRASE
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
                        "This is shown only once. It's your only way back in before time is up.",
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
                            VaultManager.seal(
                                context = context,
                                packages = selectedPackages,
                                durationDays = durationDays,
                                recoveryPhrase = recoveryPhrase ?: return@launch
                            )
                            onSealed()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BrassPrimary),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Text(
                        if (isSealing) "Sealing..." else "Done — Vault Sealed",
                        color = MaterialTheme.colorScheme.background
                    )
                }
            }
        }
    }
}

private enum class SealStep { SELECT_APPS, SET_DURATION, SHOW_RECOVERY_PHRASE }

@Composable
private fun AppRow(app: InstalledApp, checked: Boolean, onToggle: (Boolean) -> Unit) {
    val context = LocalContext.current

    val iconBitmap = remember(app.packageName) {
        runCatching {
            context.packageManager.getApplicationIcon(app.packageName).toBitmap().asImageBitmap()
        }.getOrNull()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onToggle, colors = CheckboxDefaults.colors(checkedColor = BrassPrimary))
        Spacer(Modifier.width(4.dp))

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

        Column {
            Text(app.label, style = MaterialTheme.typography.bodyLarge)
            Text(app.packageName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun loadLaunchableApps(context: android.content.Context): List<InstalledApp> {
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
