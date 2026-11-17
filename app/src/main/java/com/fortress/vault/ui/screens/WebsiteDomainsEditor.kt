package com.fortress.vault.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fortress.vault.core.normalizeWebsiteDomain
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
        Text(
            "Optional website blocks",
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            "Enter a domain and tap Add to stage it. Its main domain and subdomains will be blocked in managed Chrome. After sealing, rules cannot be removed until the seal ends. Example: instagram.com",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
        Spacer(Modifier.height(8.dp))
        apps.forEach { (packageName, label) ->
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
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
