package com.fortress.vault.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fortress.vault.core.normalizeWebsiteDomain
import com.fortress.vault.ui.components.SettingsCard
import com.fortress.vault.ui.theme.BrassPrimary
import com.fortress.vault.ui.theme.TextSecondary

@Composable
fun WebsiteDomainsEditor(
    apps: List<Pair<String, String>>,
    websiteDomains: Map<String, Set<String>>,
    allowRemove: Boolean = true,
    onDomainsChanged: (Map<String, Set<String>>) -> Unit
) {
    val inputs = remember { mutableStateMapOf<String, String>() }
    val errors = remember { mutableStateMapOf<String, String>() }

    Column {
        apps.forEach { (packageName, label) ->
            SettingsCard(modifier = Modifier.padding(vertical = 6.dp)) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = inputs[packageName].orEmpty(),
                            onValueChange = {
                                inputs[packageName] = it
                                errors.remove(packageName)
                            },
                            modifier = Modifier.weight(1f),
                            label = { Text("Website domain") },
                            placeholder = { Text("example.com") },
                            singleLine = true,
                            isError = errors.containsKey(packageName)
                        )
                        Spacer(Modifier.width(4.dp))
                        TextButton(
                            enabled = inputs[packageName].orEmpty().isNotBlank(),
                            onClick = {
                                try {
                                    val domain = normalizeWebsiteDomain(inputs[packageName].orEmpty())
                                    val updated = websiteDomains.toMutableMap()
                                    updated[packageName] = updated[packageName].orEmpty() + domain
                                    onDomainsChanged(updated)
                                    inputs[packageName] = ""
                                    errors.remove(packageName)
                                } catch (error: IllegalArgumentException) {
                                    errors[packageName] = error.message ?: "Enter a valid website domain."
                                }
                            }
                        ) {
                            Text("Add", color = BrassPrimary)
                        }
                    }
                    errors[packageName]?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    websiteDomains[packageName].orEmpty().sorted().forEach { domain ->
                        if (allowRemove) {
                            AssistChip(
                                onClick = {
                                    val updated = websiteDomains.toMutableMap()
                                    val remaining = updated[packageName].orEmpty() - domain
                                    if (remaining.isEmpty()) updated.remove(packageName)
                                    else updated[packageName] = remaining
                                    onDomainsChanged(updated)
                                },
                                label = { Text(domain) },
                                trailingIcon = {
                                    Icon(Icons.Filled.Close, contentDescription = "Remove $domain")
                                }
                            )
                        } else {
                            Text(
                                domain,
                                modifier = Modifier.padding(vertical = 6.dp),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }
}
