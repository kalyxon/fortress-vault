package com.fortress.vault

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.app.KeyguardManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.fortress.vault.core.OnboardingPrefs
import com.fortress.vault.core.ControlManager
import com.fortress.vault.core.DeviceSecurity
import com.fortress.vault.ui.screens.EmergencyUnlockScreen
import com.fortress.vault.ui.screens.HomeScreen
import com.fortress.vault.ui.screens.SealVaultScreen
import com.fortress.vault.ui.screens.SetupScreen
import com.fortress.vault.ui.screens.SettingsScreen
import com.fortress.vault.ui.screens.TermsScreen
import com.fortress.vault.ui.theme.FortressVaultTheme

object Routes {
    const val TERMS = "terms"
    const val SETUP = "setup"
    const val LOCK_REQUIRED = "lock_required"
    const val HOME = "home"
    const val SEAL = "seal"
    const val EMERGENCY = "emergency/{sealId}"
    const val SETTINGS = "settings"

    fun emergency(sealId: String) = "emergency/$sealId"
}

class MainActivity : ComponentActivity() {

    private var hasSecureLock by mutableStateOf(false)
    private var isAuthenticated by mutableStateOf(false)
    private var authenticationRequested = false

    private val deviceCredentialLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        authenticationRequested = false
        isAuthenticated = result.resultCode == RESULT_OK && DeviceSecurity.hasSecureLock(this)
        if (!isAuthenticated) finish()
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hasSecureLock = DeviceSecurity.hasSecureLock(this)
        requestDeviceAuthenticationIfNeeded()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            FortressVaultTheme {
                if (!hasSecureLock) {
                    SecurityRequiredScreen { openLockSettings() }
                } else if (isAuthenticated) {
                    androidx.compose.material3.Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = androidx.compose.material3.MaterialTheme.colorScheme.background,
                        contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onBackground
                    ) {
                        FortressNavHost(
                            hasAcceptedTerms = OnboardingPrefs.hasAcceptedTerms(this),
                            isDeviceOwner = isDeviceOwner(),
                            hasSecureLock = hasSecureLock,
                            onOpenLockSettings = { openLockSettings() }
                        )
                    }
                }
            }
        }
        ControlManager.apply(this)
    }

    override fun onResume() {
        super.onResume()
        hasSecureLock = DeviceSecurity.hasSecureLock(this)
        if (!hasSecureLock) {
            isAuthenticated = false
        } else if (!isAuthenticated) {
            requestDeviceAuthenticationIfNeeded()
        }
    }

    override fun onStop() {
        super.onStop()
        if (!authenticationRequested) {
            isAuthenticated = false
        }
    }

    private fun requestDeviceAuthenticationIfNeeded() {
        if (!hasSecureLock || isAuthenticated || authenticationRequested) return
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val intent = keyguard.createConfirmDeviceCredentialIntent(
            "Unlock Fortress Vault",
            "Verify your phone lock to access Fortress Vault"
        ) ?: run {
            finish()
            return
        }
        authenticationRequested = true
        deviceCredentialLauncher.launch(intent)
    }

    private fun openLockSettings() {
        startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
    }

    private fun isDeviceOwner(): Boolean {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isDeviceOwnerApp(packageName)
    }
}

@Composable
fun FortressNavHost(
    hasAcceptedTerms: Boolean,
    isDeviceOwner: Boolean,
    hasSecureLock: Boolean,
    onOpenLockSettings: () -> Unit
) {
    val navController: NavHostController = rememberNavController()
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
        if (hasAcceptedTerms && isDeviceOwner && !hasSecureLock) {
            SecurityRequiredScreen(onOpenLockSettings)
            return
        }
    val startDestination = when {
        !hasAcceptedTerms -> Routes.TERMS
        !isDeviceOwner -> Routes.SETUP
        else -> Routes.HOME
    }

    androidx.compose.runtime.LaunchedEffect(hasSecureLock, hasAcceptedTerms, isDeviceOwner, currentRoute) {
        if (hasAcceptedTerms && isDeviceOwner && !hasSecureLock &&
            currentRoute != Routes.LOCK_REQUIRED
        ) {
            navController.navigate(Routes.LOCK_REQUIRED) {
                popUpTo(currentRoute ?: Routes.HOME) { inclusive = true }
            }
        } else if (hasSecureLock && currentRoute == Routes.LOCK_REQUIRED) {
            navController.navigate(Routes.HOME) {
                popUpTo(Routes.LOCK_REQUIRED) { inclusive = true }
            }
        }
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.TERMS) {
            TermsScreen(
                onAccepted = {
                    navController.navigate(Routes.SETUP) {
                        popUpTo(Routes.TERMS) { inclusive = true }
                    }
                }
            )
        }
        composable(Routes.SETUP) {
            SetupScreen(
                onDeviceOwnerConfirmed = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.SETUP) { inclusive = true }
                    }
                }
            )
        }
        composable(Routes.LOCK_REQUIRED) {
            SecurityRequiredScreen(onOpenLockSettings)
        }
        composable(Routes.HOME) {
            HomeScreen(
                onSealVault = { navController.navigate(Routes.SEAL) },
                onEmergencyUnlock = { sealId -> navController.navigate(Routes.emergency(sealId)) },
                onSettings = {
                    navController.navigate(Routes.SETTINGS) {
                        launchSingleTop = true
                    }
                }
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.HOME) { inclusive = false }
                        launchSingleTop = true
                    }
                }
            )
        }
        composable(Routes.SEAL) {
            SealVaultScreen(
                onSealed = { navController.popBackStack() },
                onCancel = { navController.popBackStack() }
            )
        }
        composable(
            route = Routes.EMERGENCY,
            arguments = listOf(navArgument("sealId") { type = NavType.StringType })
        ) { backStackEntry ->
            val sealId = backStackEntry.arguments?.getString("sealId").orEmpty()
            EmergencyUnlockScreen(
                sealId = sealId,
                onUnlocked = { navController.popBackStack() },
                onCancel = { navController.popBackStack() }
            )
        }
    }
}

@Composable
private fun SecurityRequiredScreen(onOpenLockSettings: () -> Unit) {
    androidx.compose.foundation.layout.Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
    ) {
        androidx.compose.material3.Icon(
            imageVector = androidx.compose.material.icons.Icons.Filled.Lock,
            contentDescription = null,
            modifier = Modifier.size(52.dp),
            tint = com.fortress.vault.ui.theme.BrassPrimary
        )
        Spacer(Modifier.height(20.dp))
        androidx.compose.material3.Text(
            "Secure your device first",
            style = androidx.compose.material3.MaterialTheme.typography.headlineMedium
        )
        Spacer(Modifier.height(10.dp))
        androidx.compose.material3.Text(
            "Fortress Vault requires a PIN, password, or pattern. Without a secure phone lock, anyone could change protection settings or create a seal.",
            style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
            color = com.fortress.vault.ui.theme.TextSecondary
        )
        Spacer(Modifier.height(24.dp))
        androidx.compose.material3.Button(
            onClick = onOpenLockSettings,
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = com.fortress.vault.ui.theme.BrassPrimary
            ),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            androidx.compose.material3.Text(
                "Set phone lock",
                color = com.fortress.vault.ui.theme.ObsidianBlack
            )
        }
    }
}
