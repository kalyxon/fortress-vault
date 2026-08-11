package com.fortress.vault.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.fortress.vault.core.PackageFreezer
import com.fortress.vault.core.VaultManager

class PackageChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.data?.schemeSpecificPart ?: return
        if (!VaultManager.isSealed(context)) return
        if (packageName !in VaultManager.blockedPackages(context)) return

        PackageFreezer.onPackageReinstalled(context, packageName)
    }
}
