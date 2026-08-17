package com.fortress.vault.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.fortress.vault.core.PackageFreezer
import com.fortress.vault.core.PersistentVaultStore
import com.fortress.vault.core.VaultManager
import com.fortress.vault.service.SentinelService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) return

        if (!VaultManager.isSealed(context)) return
        val seals = PersistentVaultStore.read(context) ?: VaultManager.activeSeals(context)
        PackageFreezer.freezeAll(context, seals.flatMap { it.packages }.toSet())

        val serviceIntent = Intent(context, SentinelService::class.java)
        ContextCompat.startForegroundService(context, serviceIntent)

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
