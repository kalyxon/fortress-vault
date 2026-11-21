package com.fortress.vault.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.fortress.vault.core.PackageFreezer
import com.fortress.vault.core.PersistentVaultStore
import com.fortress.vault.core.VaultManager
import com.fortress.vault.core.ControlManager
import com.fortress.vault.core.WebProtectionManager
import com.fortress.vault.core.WebProtectionLockManager
import com.fortress.vault.core.AppTimeLimitManager
import com.fortress.vault.service.SentinelService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        WebProtectionLockManager.reconcileExpired(context)
        WebProtectionManager.apply(context, force = true)
        ControlManager.apply(context)
        if (!VaultManager.isSealed(context) &&
            ControlManager.activeLocks(context).isEmpty() &&
            WebProtectionLockManager.activeLocks(context).isEmpty() &&
            !AppTimeLimitManager.hasActiveWork(context)
        ) return
        ControlManager.apply(context)
        val seals = PersistentVaultStore.read(context) ?: VaultManager.activeSeals(context)
        VaultManager.enforceDeviceOwnerRestrictions(context)
        PackageFreezer.freezeAll(context, seals.flatMap { it.packages }.toSet())

        com.fortress.vault.core.SentinelController.start(context)

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                VaultManager.verifyAndEnforce(context)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
