package com.fortress.vault.core

import android.content.Context

object ReelsBlockerManager {
    // FUTURE/EXPERIMENTAL:
    // The Accessibility-based Reels/Shorts/Spotlight implementation remains in
    // ReelsBlockerAccessibilityService.kt. It is hidden from Settings and
    // disabled here because the current route detection is not reliable enough
    // for a security feature. Do not enable this again until a stable route
    // detection strategy is verified for every supported app and layout.
    private const val EXPERIMENTAL_FEATURE_DISABLED = true

    private const val PREFS_NAME = "fortress_reels_blocker"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PACKAGES = "packages"

    val supportedPackages = setOf(
        "com.instagram.android",
        "com.snapchat.android",
        "com.google.android.youtube"
    )

    fun isEnabled(context: Context): Boolean {
        if (EXPERIMENTAL_FEATURE_DISABLED) return false
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
    }

    fun blockedPackages(context: Context): Set<String> =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_PACKAGES, supportedPackages) ?: supportedPackages

    fun setEnabled(context: Context, enabled: Boolean) {
        if (EXPERIMENTAL_FEATURE_DISABLED) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, false).commit()
            return
        }
        check(!WebProtectionLockManager.isLocked(context, WebProtectionSetting.REELS_BLOCKER)) {
            "Unlock Reels & Shorts blocker with its recovery phrase before changing it."
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun setBlockedPackage(context: Context, packageName: String, blocked: Boolean) {
        check(!WebProtectionLockManager.isLocked(context, WebProtectionSetting.REELS_BLOCKER)) {
            "Unlock Reels & Shorts blocker with its recovery phrase before editing it."
        }
        require(packageName in supportedPackages) { "Unsupported app package." }
        val updated = blockedPackages(context).toMutableSet()
        if (blocked) updated.add(packageName) else updated.remove(packageName)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_PACKAGES, updated).apply()
    }
}
