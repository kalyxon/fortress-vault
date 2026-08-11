package com.fortress.vault

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.fortress.vault.core.VaultManager
import com.fortress.vault.ui.screens.EmergencyUnlockScreen
import com.fortress.vault.ui.screens.HomeScreen
import com.fortress.vault.ui.screens.SealVaultScreen
import com.fortress.vault.ui.screens.SetupScreen
import com.fortress.vault.ui.theme.FortressVaultTheme

object Routes {
    const val SETUP = "setup"
    const val HOME = "home"
    const val SEAL = "seal"
    const val EMERGENCY = "emergency"
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
                FortressNavHost(isDeviceOwner = isDeviceOwner())
            }
        }
    }

    private fun isDeviceOwner(): Boolean {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isDeviceOwnerApp(packageName)
    }
}

@Composable
fun FortressNavHost(isDeviceOwner: Boolean) {
    val navController: NavHostController = rememberNavController()
    val context = LocalContext.current
    val startDestination = if (!isDeviceOwner) Routes.SETUP else Routes.HOME

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.SETUP) {
            SetupScreen(
                onDeviceOwnerConfirmed = { organizationName ->
                    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                    dpm.setOrganizationName(
                        FortressAdminReceiver.getComponentName(context),
                        organizationName
                    )
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.SETUP) { inclusive = true }
                    }
                }
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                onSealVault = { navController.navigate(Routes.SEAL) },
                onEmergencyUnlock = { navController.navigate(Routes.EMERGENCY) }
            )
        }
        composable(Routes.SEAL) {
            SealVaultScreen(
                onSealed = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.SEAL) { inclusive = true }
                    }
                },
                onCancel = { navController.popBackStack() }
            )
        }
        composable(Routes.EMERGENCY) {
            EmergencyUnlockScreen(
                onUnlocked = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.EMERGENCY) { inclusive = true }
                    }
                },
                onCancel = { navController.popBackStack() }
            )
        }
    }
}
