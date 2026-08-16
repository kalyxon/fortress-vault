package com.fortress.vault.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val FortressColorScheme = darkColorScheme(
    primary = BrassPrimary,
    onPrimary = ObsidianBlack,
    secondary = BrassPrimaryDim,
    background = ObsidianBlack,
    onBackground = TextPrimary,
    surface = CharcoalSurface,
    onSurface = TextPrimary,
    surfaceVariant = SteelSurfaceHigh,
    onSurfaceVariant = TextSecondary,
    error = EmberRed,
    onError = TextPrimary,
    outline = VaultDivider
)

@Composable
fun FortressVaultTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FortressColorScheme,
        typography = FortressTypography,
        content = content
    )
}
