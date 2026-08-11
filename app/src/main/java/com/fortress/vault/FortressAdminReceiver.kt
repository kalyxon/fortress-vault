package com.fortress.vault

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.fortress.vault.core.VaultManager

class FortressAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        VaultManager.init(context)
    }

    // Fires when the user tries to uncheck "Fortress" under
    // Settings > Security > Device Admin Apps.
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        val remaining = VaultManager.remainingTimeLabel(context)
        return if (VaultManager.isSealed(context)) {
            "Your past self sealed this path. Freedom comes in $remaining."
        } else {
            "Remove Fortress protection? You currently have no active seal."
        }
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        // If we ever legitimately reach here (vault unsealed), clean up.
        VaultManager.onAdminDisabled(context)
    }

    companion object {
        fun getComponentName(context: Context): ComponentName =
            ComponentName(context.applicationContext, FortressAdminReceiver::class.java)
    }
}
