package com.fortress.vault.core

import android.content.Context

object OnboardingPrefs {
    private const val PREFS_NAME = "fortress_onboarding_prefs"
    private const val KEY_ACCEPTED_TERMS = "accepted_terms"

    fun hasAcceptedTerms(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ACCEPTED_TERMS, false)

    fun setAcceptedTerms(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ACCEPTED_TERMS, true)
            .apply()
    }
}
