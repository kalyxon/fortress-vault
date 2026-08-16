package com.fortress.vault.ui.screens

import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.fortress.vault.ui.theme.BrassPrimary

private const val ADB_COMMAND =
    "adb shell dpm set-device-owner com.fortress.vault/.FortressAdminReceiver"

@Composable
fun SetupScreen(onDeviceOwnerConfirmed: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var showNotYetGrantedError by remember { mutableStateOf(false) }
    var orgName by remember { mutableStateOf("") }
    var showOrgNameError by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
            .verticalScroll(rememberScrollStateCompat()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(32.dp))
        Icon(
            imageVector = Icons.Filled.Shield,
            contentDescription = null,
            tint = BrassPrimary,
            modifier = Modifier.size(56.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text("Before You Seal Anything", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Fortress needs Device Owner status to become unremovable. " +
                "This is a one-time setup done on a freshly reset phone, with no " +
                "Google account added yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = orgName,
            onValueChange = { orgName = it; showOrgNameError = false },
            label = { Text("Organization name") },
            placeholder = { Text("Your organization (shown by Android)") },
            modifier = Modifier.fillMaxWidth()
        )
        if (showOrgNameError) {
            Spacer(Modifier.height(8.dp))
            Text("Please enter a non-empty organization name.", color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "This name is used by Android when this app is Device Owner. " +
                "The system may show: 'This device belongs to <Organization>' on the lock screen and in admin UI.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(28.dp))
        SetupStep(
            number = 1,
            title = "Factory reset required",
            description = "Device Owner can only be granted on a device with zero accounts " +
                "ever added. If you've already signed into Google, you'll need to reset first."
        )
        SetupStep(
            number = 2,
            title = "Enable USB debugging",
            description = "Settings → About phone → tap Build number 7 times → Developer " +
                "options → enable USB debugging. Connect the phone to a computer."
        )
        SetupStep(
            number = 3,
            title = "Run this command from your computer",
            description = null
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    ADB_COMMAND,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = {
                    clipboard.setText(AnnotatedString(ADB_COMMAND))
                }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Copy command", tint = BrassPrimary)
                }
            }
        }

        SetupStep(
            number = 4,
            title = "Confirm",
            description = "Once the command succeeds, come back here and tap below. " +
                "Reminder: sealing doesn't turn off USB debugging by itself — if you plan to " +
                "seal something and want it to actually hold against a connected computer, " +
                "turn off USB debugging yourself first (Developer options → USB debugging)."
        )

        Spacer(Modifier.height(24.dp))
        if (showNotYetGrantedError) {
            Text(
                "Not detected yet. Double-check the command ran without errors, " +
                    "then try again.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }
        Button(
            onClick = {
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                if (!dpm.isDeviceOwnerApp(context.packageName)) {
                    showNotYetGrantedError = true
                    return@Button
                }
                if (orgName.isBlank()) {
                    showOrgNameError = true
                    return@Button
                }

                try {
                    val admin = com.fortress.vault.FortressAdminReceiver.getComponentName(context)
                    dpm.setOrganizationName(admin, orgName)
                } catch (_: Exception) {
                }

                onDeviceOwnerConfirmed()
            },
            colors = ButtonDefaults.buttonColors(containerColor = BrassPrimary),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text("I've Run The Command", color = MaterialTheme.colorScheme.background)
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun SetupStep(number: Int, title: String, description: String?) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(BrassPrimary, shape = androidx.compose.foundation.shape.CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("$number", color = MaterialTheme.colorScheme.background, style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (description != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun rememberScrollStateCompat() = androidx.compose.foundation.rememberScrollState()
