package com.fortress.vault.ui.screens

import android.app.Activity
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
import com.fortress.vault.core.VaultManager

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
    var phrase by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier
            .fillMaxSize()
            .padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("Restricted app", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text("Enter the emergency phrase to temporarily open $packageName.")
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(value = phrase, onValueChange = { phrase = it }, label = { Text("Emergency phrase") }, modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(12.dp))
            Row {
                Button(onClick = {
                    // Try to grant temporary access for this package (30s)
                    val granted = VaultManager.grantTemporaryAccess(context, packageName, 30_000L, phrase)
                    if (granted) {
                        onDone(true)
                    } else {
                        error = "Incorrect phrase or cooldown"
                    }
                }) {
                    Text("Unlock temporarily")
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { onDone(false) }) { Text("Cancel") }
            }
        }
    }
}
