package com.fortress.vault.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable

private val FortressColorScheme = darkColorScheme(
    primary = BrassPrimary,
    onPrimary = ObsidianBlack,
    secondary = BrassPrimaryDim,
    onSecondary = TextPrimary,
    background = ObsidianBlack,
    onBackground = TextPrimary,
    surface = CharcoalSurface,
    onSurface = TextPrimary,
    surfaceVariant = SteelSurfaceHigh,
    onSurfaceVariant = TextSecondary,
    error = EmberRed,
    onError = TextPrimary,
    outline = VaultDivider,
    outlineVariant = VaultDivider.copy(alpha = 0.65f),
    surfaceTint = BrassPrimary
)

@Composable
fun FortressVaultTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FortressColorScheme,
        typography = FortressTypography,
        shapes = Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(24.dp)
        ),
        content = content
    )
}
