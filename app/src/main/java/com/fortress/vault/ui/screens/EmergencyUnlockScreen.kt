package com.fortress.vault.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fortress.vault.core.VaultManager
import com.fortress.vault.ui.theme.BrassPrimary
import com.fortress.vault.ui.theme.EmberRed
import kotlin.random.Random
import com.fortress.vault.ui.components.PageHeader
import com.fortress.vault.ui.components.SettingsCard
import com.fortress.vault.ui.theme.TextPrimary
import com.fortress.vault.ui.theme.TextSecondary
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun EmergencyUnlockScreen(sealId: String, onUnlocked: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val seal = remember(sealId) { VaultManager.activeSeals(context).firstOrNull { it.id == sealId } }
    val challengeCode = remember { generateChallengeCode() }
    var typedChallenge by remember { mutableStateOf("") }
    var recoveryInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var inCooldown by remember(sealId) { mutableStateOf(VaultManager.isInCooldown(context, sealId)) }
    var attemptsLeft by remember(sealId) {
        mutableStateOf((3 - (seal?.failedAttempts ?: 0)).coerceIn(0, 3))
    }
    var checking by remember { mutableStateOf(false) }
    var unlockedSuccessfully by remember { mutableStateOf(false) }
    BackHandler(enabled = true) { if (!unlockedSuccessfully) onCancel() }

    LaunchedEffect(unlockedSuccessfully) {
        if (unlockedSuccessfully) {
            delay(900)
            onUnlocked()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp)
    ) {
        PageHeader(title = "Emergency unlock", onBack = onCancel)
        Spacer(Modifier.height(8.dp))

        if (seal == null) {
            Text(
                "This seal is no longer active — it may have already expired or been unlocked.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
            TextButton(onClick = onCancel) { Text("Back") }
            return@Column
        }

        if (inCooldown) {
            Spacer(Modifier.height(32.dp))
            Text(
                "Too many failed attempts on this seal. Try again later — this cooldown exists on purpose.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
            TextButton(onClick = onCancel) { Text("Back") }
        } else {
            Text(
                "Seal " + seal.id.replace("-", "").take(8).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Spacer(Modifier.height(8.dp))
                SettingsCard {
                    Column(Modifier.fillMaxWidth().padding(18.dp)) {
                        Text("Step 1", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            challengeCode,
                            style = MaterialTheme.typography.displaySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 3.sp
                            ),
                            color = TextPrimary
                        )
                        Spacer(Modifier.height(14.dp))
                        OutlinedTextField(
                            value = typedChallenge,
                            onValueChange = { typedChallenge = it },
                            label = { Text("Type the code above") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                }

                SettingsCard {
                    Column(Modifier.fillMaxWidth().padding(18.dp)) {
                        Text("Step 2", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Text("Recovery phrase", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = recoveryInput,
                            onValueChange = { recoveryInput = it },
                            label = { Text("word word word ...") },
                            modifier = Modifier.fillMaxWidth().height(112.dp),
                            maxLines = 6
                        )
                    }
                }

                errorMessage?.let { Text(it, color = EmberRed) }

                if (checking) {
                    SettingsCard {
                        Text(
                            "Checking recovery phrase and unblocking sealed apps...",
                            modifier = Modifier.fillMaxWidth().padding(18.dp),
                            color = TextSecondary
                        )
                    }
                }

                AnimatedVisibility(
                    visible = unlockedSuccessfully,
                    enter = fadeIn() + scaleIn(),
                    exit = fadeOut()
                ) {
                    SettingsCard {
                        Column(
                            Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                Modifier
                                    .size(64.dp)
                                    .graphicsLayer { alpha = 1f }
                                    .background(BrassPrimary, RoundedCornerShape(32.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = "Seal unlocked",
                                    tint = TextPrimary,
                                    modifier = Modifier.size(34.dp)
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            Text("Seal unlocked", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "The protected apps are available again.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(onClick = onCancel) { Text("Cancel") }
                Button(
                    enabled = typedChallenge == challengeCode &&
                        recoveryInput.isNotBlank() &&
                        !checking &&
                        !unlockedSuccessfully,
                    onClick = {
                        checking = true
                        errorMessage = null
                        coroutineScope.launch {
                            try {
                                val success = withContext(Dispatchers.IO) {
                                    VaultManager.attemptEmergencyUnlock(context, sealId, recoveryInput)
                                }
                                if (success) {
                                    unlockedSuccessfully = true
                                } else {
                                    val updatedSeal = withContext(Dispatchers.IO) {
                                        VaultManager.activeSeals(context).firstOrNull { it.id == sealId }
                                    }
                                    val cooldownNow = updatedSeal?.let {
                                        withContext(Dispatchers.IO) {
                                            VaultManager.isInCooldown(context, sealId)
                                        }
                                    } ?: false
                                    inCooldown = cooldownNow
                                    attemptsLeft = (3 - (updatedSeal?.failedAttempts ?: 0)).coerceIn(0, 3)
                                    errorMessage = if (cooldownNow) {
                                        "Too many incorrect attempts. Try again later."
                                    } else {
                                        "Incorrect phrase — $attemptsLeft attempt${if (attemptsLeft == 1) "" else "s"} left."
                                    }
                                }
                            } catch (error: IllegalStateException) {
                                errorMessage = error.message ?: "Android could not safely unblock the sealed apps."
                            } finally {
                                checking = false
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmberRed)
                ) {
                    Text(
                        if (checking) "Checking..." else if (unlockedSuccessfully) "Unlocked" else "Break The Seal",
                        color = MaterialTheme.colorScheme.onError
                    )
                }
            }
        }
    }
}

private fun generateChallengeCode(): String {
    val chars = "ABCDEFGHJKMNPQRSTUVWXYZ23456789" // no ambiguous chars (0/O, 1/I/l)
    return (1..8).map { chars[Random.nextInt(chars.length)] }.joinToString("")
}
