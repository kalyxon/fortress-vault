package com.fortress.vault

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.fortress.vault.core.ControlManager
import com.fortress.vault.core.DeviceSecurity
import com.fortress.vault.core.OnboardingPrefs
import com.fortress.vault.core.VaultManager
import com.fortress.vault.ui.screens.EmergencyUnlockScreen
import com.fortress.vault.ui.screens.HomeScreen
import com.fortress.vault.ui.screens.SealVaultScreen
import com.fortress.vault.ui.screens.SettingsScreen
import com.fortress.vault.ui.screens.SetupScreen
import com.fortress.vault.ui.screens.TermsScreen
import com.fortress.vault.ui.theme.BrassPrimary
import com.fortress.vault.ui.theme.FortressVaultTheme
import com.fortress.vault.ui.theme.ObsidianBlack
import com.fortress.vault.ui.theme.TextSecondary

object Routes {
    const val TERMS = "terms"
    const val SETUP = "setup"
    const val HOME = "home"
    const val SEAL = "seal"
    const val EMERGENCY = "emergency/{sealId}"
    const val SETTINGS = "settings"

    fun emergency(sealId: String) = "emergency/$sealId"
}

class MainActivity : FragmentActivity() {

    private var isAuthenticated by mutableStateOf(false)
    private var authenticationRequested = false

    // Cache the result of hasActiveSealsOrLocks() so it is only computed once
    // per lifecycle event, not on every recomposition or method call.
    @Volatile private var cachedHasLocks: Boolean? = null

    private fun hasActiveSealsOrLocks(): Boolean {
        cachedHasLocks?.let { return it }
        val result = VaultManager.isSealed(this) || ControlManager.activeLocks(this).isNotEmpty()
        cachedHasLocks = result
        return result
    }

    /** Call after any operation that changes the seal/lock state. */
    private fun invalidateLockCache() {
        cachedHasLocks = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val hasLocks = hasActiveSealsOrLocks()
        if (savedInstanceState != null) {
            isAuthenticated = savedInstanceState.getBoolean("is_authenticated", !hasLocks)
            authenticationRequested = savedInstanceState.getBoolean("auth_requested", false)
        } else {
            isAuthenticated = !hasLocks
        }

        if (hasLocks && !isAuthenticated) {
            promptBiometricAuthentication()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }

        setContent {
            FortressVaultTheme {
                val hasAcceptedTerms = remember { OnboardingPrefs.hasAcceptedTerms(this@MainActivity) }
                val isSetupCompleted = remember { OnboardingPrefs.isSetupCompleted(this@MainActivity) }
                // Use cached value — avoids calling isSealed() + activeLocks() during recomposition
                val activeLocksExist = remember { hasActiveSealsOrLocks() }

                Box(modifier = Modifier.fillMaxSize()) {
                    // Pre-rendered base surface containing navigation host
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                        contentColor = MaterialTheme.colorScheme.onBackground
                    ) {
                        FortressNavHost(
                            hasAcceptedTerms = hasAcceptedTerms,
                            isSetupCompleted = isSetupCompleted
                        )
                    }

                    // Lock screen overlay shown ONLY when active seals exist AND user is unauthenticated
                    if (activeLocksExist && !isAuthenticated) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.background,
                            contentColor = MaterialTheme.colorScheme.onBackground
                        ) {
                            UnlockRequiredScreen(
                                onUnlockClicked = {
                                    promptBiometricAuthentication()
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("is_authenticated", isAuthenticated)
        outState.putBoolean("auth_requested", authenticationRequested)
    }

    override fun onResume() {
        super.onResume()
        // Invalidate cache so next read reflects any changes made while app was backgrounded
        invalidateLockCache()
        val hasLocks = hasActiveSealsOrLocks()
        if (hasLocks && !isAuthenticated && !authenticationRequested) {
            promptBiometricAuthentication()
        }
    }

    override fun onStop() {
        super.onStop()
        // Re-use cached value here — avoids a redundant synchronized call on the main thread
        val hasLocks = cachedHasLocks ?: hasActiveSealsOrLocks()
        if (!isChangingConfigurations && !authenticationRequested && hasLocks) {
            isAuthenticated = false
        }
        // Invalidate so onResume always gets fresh state
        invalidateLockCache()
    }

    private fun promptBiometricAuthentication() {
        if (authenticationRequested || isAuthenticated || !hasActiveSealsOrLocks()) return
        if (!DeviceSecurity.hasSecureLock(this)) {
            // No phone lock configured — skip prompt
            isAuthenticated = true
            return
        }

        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    authenticationRequested = false
                    isAuthenticated = true
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    authenticationRequested = false
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    // Prompt stays active for fingerprint retries
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Fortress Vault")
            .setSubtitle("Verify your fingerprint, PIN, pattern, or password")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()

        authenticationRequested = true
        runCatching {
            biometricPrompt.authenticate(promptInfo)
        }.onFailure {
            authenticationRequested = false
        }
    }
}

@Composable
fun FortressNavHost(
    hasAcceptedTerms: Boolean,
    isSetupCompleted: Boolean
) {
    val navController: NavHostController = rememberNavController()

    val startDestination = when {
        !hasAcceptedTerms -> Routes.TERMS
        !isSetupCompleted -> Routes.SETUP
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
private fun UnlockRequiredScreen(onUnlockClicked: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            modifier = Modifier.size(52.dp),
            tint = BrassPrimary
        )
        Spacer(Modifier.height(20.dp))
        Text(
            "Fortress Vault is Locked",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onUnlockClicked,
            colors = ButtonDefaults.buttonColors(
                containerColor = BrassPrimary
            ),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text(
                "Unlock Vault",
                color = ObsidianBlack
            )
        }
    }
}
