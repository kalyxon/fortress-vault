package com.fortress.vault.core

import android.app.KeyguardManager
import android.content.Context

object DeviceSecurity {
    fun hasSecureLock(context: Context): Boolean {
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return keyguard.isKeyguardSecure
    }
}
