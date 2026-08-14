package com.fortress.vault.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fortress.vault.core.OnboardingPrefs
import com.fortress.vault.ui.theme.BrassPrimary
import com.fortress.vault.ui.theme.EmberRed

@Composable
fun TermsScreen(onAccepted: () -> Unit) {
    val context = LocalContext.current

    var understandsDataRisk by remember { mutableStateOf(false) }
    var willBackUp by remember { mutableStateOf(false) }
    var willStorePhraseSafely by remember { mutableStateOf(false) }

    val allChecked = understandsDataRisk && willBackUp && willStorePhraseSafely

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
    ) {
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text("Before You Begin", style = MaterialTheme.typography.headlineMedium, color = EmberRed)
            Spacer(Modifier.height(16.dp))

            WarningBlock(
                title = "This can make things genuinely inaccessible",
                body = "Once something is sealed, the only ways back in are: the timer " +
                    "running out, that seal's recovery phrase, or a full factory reset. " +
                    "There is no support ticket, no password reset, no override."
            )

            WarningBlock(
                title = "It closes some recovery paths — but not USB debugging by itself",
                body = "While anything is sealed, Fortress disables Safe Mode and restricts " +
                    "re-enabling USB debugging if it's currently off. But if USB debugging is " +
                    "already ON when you seal something, it stays on — there's no Android API " +
                    "that lets an app force-close an already-authorized connection. A computer " +
                    "already connected via USB debugging can remove Fortress entirely, seal or " +
                    "not. If you want a seal to actually hold against that, turn off USB " +
                    "debugging (Settings → Developer options) before you seal anything. " +
                    "Separately: if you lose a seal's recovery phrase while it's active, a " +
                    "factory reset — erasing this entire device — becomes your only way back " +
                    "in before the timer expires."
            )

            WarningBlock(
                title = "Back up your data before you rely on this",
                body = "Photos, messages, anything you can't afford to lose — back it up " +
                    "somewhere off this device first. Fortress doesn't touch your data " +
                    "directly, but the recovery paths above mean a lost phrase plus a " +
                    "stubborn seal can genuinely lead to a factory reset."
            )

            WarningBlock(
                title = "Each recovery phrase is shown exactly once",
                body = "Write it down somewhere physical — paper, not a screenshot on this " +
                    "same device — and keep it somewhere separate from the phone. There is " +
                    "no way to retrieve it later if it's lost."
            )

            Spacer(Modifier.height(8.dp))
            Text(
                "This is a personal self-restriction tool, not a licensed security product. " +
                    "Use it at your own risk.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(24.dp))
            CheckRow(
                checked = understandsDataRisk,
                onCheckedChange = { understandsDataRisk = it },
                label = "I understand a lost recovery phrase may mean a factory reset, and that I should turn off USB debugging before sealing if I want that protection to hold."
            )
            CheckRow(
                checked = willBackUp,
                onCheckedChange = { willBackUp = it },
                label = "I will back up anything important on this device before sealing it."
            )
            CheckRow(
                checked = willStorePhraseSafely,
                onCheckedChange = { willStorePhraseSafely = it },
                label = "I will write down each recovery phrase physically, separate from this device."
            )
        }

        Spacer(Modifier.height(16.dp))
        Button(
            enabled = allChecked,
            onClick = {
                OnboardingPrefs.setAcceptedTerms(context)
                onAccepted()
            },
            colors = ButtonDefaults.buttonColors(containerColor = BrassPrimary),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text("I Understand, Continue", color = MaterialTheme.colorScheme.background)
        }
    }
}

@Composable
private fun WarningBlock(title: String, body: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CheckRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit, label: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(checkedColor = BrassPrimary)
        )
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
    }
}
