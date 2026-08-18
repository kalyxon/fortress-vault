package com.fortress.vault.core

import android.content.Context

object OnboardingPrefs {
    private const val PREFS_NAME = "fortress_onboarding_prefs"
    private const val KEY_ACCEPTED_TERMS = "accepted_terms"

    // User-switching block preference:
    //   "unset"   → never answered (ask on next seal)
    //   "block"   → user chose to block user-switching
    //   "noblock" → user chose NOT to block user-switching (per-user freeze still applies)
    //   "saved"   → user said "don't ask again", default = noblock
    // User-switching block preference:
    //   "unset"   → never answered
    //   "block"   → user chose to block user-switching
    //   "noblock" → user chose NOT to block user-switching (per-user freeze still applies)
    private const val KEY_USER_SWITCH_PREF = "user_switch_block_pref"
    private const val VAL_UNSET = "unset"
    const val VAL_BLOCK = "block"
    const val VAL_NOBLOCK = "noblock"

    private const val KEY_SWITCH_ASK_COUNT = "user_switch_ask_count"

    fun hasAcceptedTerms(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ACCEPTED_TERMS, false)

    fun setAcceptedTerms(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ACCEPTED_TERMS, true)
            .apply()
    }

    // ── User-switch block preference ────────────────────────────────────────

    /**
     * Always prompt user on seal creation unless an existing active seal is already blocking user switching.
     */
    fun shouldAskAboutUserSwitch(context: Context): Boolean {
        val activeSeals = VaultManager.activeSeals(context)
        val isAlreadyBlockedByActiveSeal = activeSeals.any { it.blockUserSwitch }
        return !isAlreadyBlockedByActiveSeal
    }
}
