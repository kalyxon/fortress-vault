package com.fortress.vault.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fortress.vault.core.VaultManager
import com.fortress.vault.ui.theme.BrassPrimary
import com.fortress.vault.ui.theme.EmberRed
import kotlin.random.Random

@Composable
fun EmergencyUnlockScreen(onUnlocked: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current

    val challengeCode = remember { generateChallengeCode() }
    var typedChallenge by remember { mutableStateOf("") }
    var recoveryInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val inCooldown = remember { VaultManager.isInCooldown(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
    ) {
        Text("Emergency Unlock", style = MaterialTheme.typography.headlineMedium, color = EmberRed)
        Spacer(Modifier.height(8.dp))
        Text(
            "This breaks the seal immediately. Make sure this is real.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (inCooldown) {
            Spacer(Modifier.height(32.dp))
            Text(
                "Too many failed attempts. Try again later — this cooldown exists on purpose.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Spacer(Modifier.height(32.dp))
            Text("Step 1 — type this exactly:", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(challengeCode, style = MaterialTheme.typography.displayLarge, color = BrassPrimary)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = typedChallenge,
                onValueChange = { typedChallenge = it },
                label = { Text("Type the code above") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(28.dp))
            Text("Step 2 — your 12-word recovery phrase:", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = recoveryInput,
                onValueChange = { recoveryInput = it },
                label = { Text("word word word ...") },
                modifier = Modifier.fillMaxWidth().height(120.dp)
            )

            errorMessage?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = EmberRed)
            }

            Spacer(Modifier.weight(1f))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onCancel) { Text("Cancel") }
                Button(
                    enabled = typedChallenge == challengeCode && recoveryInput.isNotBlank(),
                    onClick = {
                        val success = VaultManager.attemptEmergencyUnlock(context, recoveryInput)
                        if (success) {
                            onUnlocked()
                        } else {
                            errorMessage = "That phrase doesn't match. Attempts are limited."
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmberRed)
                ) {
                    Text("Break The Seal", color = MaterialTheme.colorScheme.onError)
                }
            }
        }
    }
}

private fun generateChallengeCode(): String {
    val chars = "ABCDEFGHJKMNPQRSTUVWXYZ23456789" // no ambiguous chars (0/O, 1/I/l)
    return (1..8).map { chars[Random.nextInt(chars.length)] }.joinToString("")
}
