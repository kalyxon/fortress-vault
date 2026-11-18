package com.fortress.vault.ui.screens

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fortress.vault.ui.components.PageHeader
import com.fortress.vault.ui.components.SettingsCard
import com.fortress.vault.core.VaultManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppLockActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val packageName = intent.getStringExtra(EXTRA_PACKAGE) ?: run { finish(); return }
        setContent {
            AppLockScreen(packageName) { success ->
                if (success) finish()
            }
        }
    }

    companion object {
        const val EXTRA_PACKAGE = "extra.package"

        fun start(context: Context, packageName: String) {
            val i = Intent(context, AppLockActivity::class.java).apply {
                putExtra(EXTRA_PACKAGE, packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(i)
        }
    }
}

@Composable
private fun AppLockScreen(packageName: String, onDone: (Boolean) -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var phrase by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var isUnlocking by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier
            .fillMaxSize()
            .padding(24.dp), verticalArrangement = Arrangement.Center) {
            PageHeader(title = "Restricted app")
            Spacer(Modifier.height(8.dp))
            Text("Enter the emergency phrase to temporarily open $packageName.")
            Spacer(Modifier.height(12.dp))
            SettingsCard {
                OutlinedTextField(
                    value = phrase,
                    onValueChange = { phrase = it },
                    label = { Text("Emergency phrase") },
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                )
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(12.dp))
            Row {
                Button(
                    enabled = !isUnlocking,
                    onClick = {
                        isUnlocking = true
                        error = null
                        coroutineScope.launch {
                            try {
                                val granted = withContext(Dispatchers.IO) {
                                    VaultManager.grantTemporaryAccess(context, packageName, 30_000L, phrase)
                                }
                                if (granted) onDone(true)
                                else error = "Incorrect phrase, cooldown active, or temporary access unavailable."
                            } catch (failure: Exception) {
                                error = failure.message ?: "Temporary access could not be applied."
                            } finally {
                                isUnlocking = false
                            }
                        }
                    }
                ) {
                    Text(if (isUnlocking) "Unlocking..." else "Unlock temporarily")
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { onDone(false) }) { Text("Cancel") }
            }
        }
    }
}
