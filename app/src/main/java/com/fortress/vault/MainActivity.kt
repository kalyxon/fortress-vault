package com.fortress.vault

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.fortress.vault.core.OnboardingPrefs
import com.fortress.vault.ui.screens.EmergencyUnlockScreen
import com.fortress.vault.ui.screens.HomeScreen
import com.fortress.vault.ui.screens.SealVaultScreen
import com.fortress.vault.ui.screens.SetupScreen
import com.fortress.vault.ui.screens.TermsScreen
import com.fortress.vault.ui.theme.FortressVaultTheme

object Routes {
    const val TERMS = "terms"
    const val SETUP = "setup"
    const val HOME = "home"
    const val SEAL = "seal"
    const val EMERGENCY = "emergency/{sealId}"

    fun emergency(sealId: String) = "emergency/$sealId"
}

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            FortressVaultTheme {
                androidx.compose.material3.Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.background,
                    contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onBackground
                ) {
                    FortressNavHost(
                        hasAcceptedTerms = OnboardingPrefs.hasAcceptedTerms(this),
                        isDeviceOwner = isDeviceOwner()
                    )
                }
            }
        }
    }

    private fun isDeviceOwner(): Boolean {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isDeviceOwnerApp(packageName)
    }
}

@Composable
fun FortressNavHost(hasAcceptedTerms: Boolean, isDeviceOwner: Boolean) {
    val navController: NavHostController = rememberNavController()
    val startDestination = when {
        !hasAcceptedTerms -> Routes.TERMS
        !isDeviceOwner -> Routes.SETUP
        else -> Routes.HOME
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
        composable(Routes.HOME) {
            HomeScreen(
                onSealVault = { navController.navigate(Routes.SEAL) },
                onEmergencyUnlock = { sealId -> navController.navigate(Routes.emergency(sealId)) }
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
