package com.fortress.vault.core

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import android.util.Log
import com.fortress.vault.FortressAdminReceiver
import org.json.JSONArray

data class WebProtectionUpdate(val applied: Boolean, val error: String? = null)

object WebProtectionManager {
    private const val PREFS_NAME = "fortress_web_protection"
    private const val KEY_BLOCK_ADULT = "block_adult_sites"
    private const val KEY_BLOCK_BYPASS_APPS = "block_bypass_apps"
    private const val KEY_BLOCK_SEALED_APP_SITES = "block_sealed_app_sites"
    private const val KEY_APPLIED_ADULT = "applied_adult_sites"
    private const val KEY_APPLIED_BYPASS = "applied_bypass_protection"
    private const val KEY_APPLIED_SEALED_PACKAGES = "applied_sealed_packages"
    private const val KEY_MANAGED_BYPASS_PACKAGES = "managed_bypass_packages"
    private const val KEY_PREVIOUSLY_HIDDEN_PACKAGES = "previously_hidden_bypass_packages"
    private const val KEY_APPLIED_CHROME_BLOCKLIST = "applied_chrome_blocklist"
    private const val KEY_APPLIED_CHROME_INCOGNITO_POLICY = "applied_chrome_incognito_policy"
    private const val KEY_ADULT_DOMAIN_BLOCKS = "adult_domain_blocks"
    private const val CHROME_PACKAGE = "com.android.chrome"
    private const val ADULT_DNS_HOST = "adult-filter-dns.cleanbrowsing.org"
    private const val CHROME_INCOGNITO_DISABLED = 1
    private const val TAG = "WebProtection"

    private val bypassPackages = setOf(
        "com.protonvpn.android",
        "com.nordvpn.android",
        "com.expressvpn.vpn",
        "com.privateinternetaccess.android",
        "com.surfshark.vpnclient.android",
        "com.windscribe.vpn",
        "com.tunnelbear.android",
        "com.vyprvpn.android",
        "net.mullvad.mullvadvpn",
        "org.torproject.android",
        "com.psiphon3.subscription",
        "com.cloudflare.onedotonedotonedotone",
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "org.mozilla.fenix",
        "com.brave.browser",
        "com.microsoft.emmx",
        "com.opera.browser",
        "com.opera.mini.native",
        "org.torproject.torbrowser",
        "com.sec.android.app.sbrowser",
        "com.duckduckgo.mobile.android",
        "com.vivaldi.browser",
        "com.kiwibrowser.browser",
        "com.ecosia.android"
    )

    private val packageDomains = mapOf(
        "com.instagram.android" to setOf("instagram.com", "threads.net"),
        "com.google.android.youtube" to setOf("youtube.com", "youtu.be", "youtube-nocookie.com"),
        "com.zhiliaoapp.musically" to setOf("tiktok.com"),
        "com.ss.android.ugc.trill" to setOf("tiktok.com"),
        "com.facebook.katana" to setOf("facebook.com", "fb.com", "messenger.com"),
        "com.facebook.orca" to setOf("facebook.com", "messenger.com"),
        "com.twitter.android" to setOf("x.com", "twitter.com", "t.co"),
        "com.snapchat.android" to setOf("snapchat.com"),
        "com.reddit.frontpage" to setOf("reddit.com", "redd.it"),
        "com.discord" to setOf("discord.com", "discord.gg"),
        "com.whatsapp" to setOf("whatsapp.com"),
        "org.telegram.messenger" to setOf("telegram.org", "t.me"),
        "com.linkedin.android" to setOf("linkedin.com"),
        "com.pinterest" to setOf("pinterest.com"),
        "com.tumblr" to setOf("tumblr.com"),
        "tv.twitch.android.app" to setOf("twitch.tv")
    )

    fun blockAdultSites(context: Context): Boolean =
        preferences(context).getBoolean(KEY_BLOCK_ADULT, false)

    fun blockBypassApps(context: Context): Boolean =
        preferences(context).getBoolean(KEY_BLOCK_BYPASS_APPS, false)

    fun blockSealedAppSites(context: Context): Boolean =
        preferences(context).getBoolean(KEY_BLOCK_SEALED_APP_SITES, true)

    fun isPackageHiddenByBypassProtection(context: Context, packageName: String): Boolean =
        blockBypassApps(context) &&
            packageName in preferences(context)
                .getStringSet(KEY_MANAGED_BYPASS_PACKAGES, emptySet())
                .orEmpty()

    fun adultDomainBlockCount(context: Context): Int =
        adultBlockedDomains(context).size

    fun adultDomainsForEditing(context: Context): Set<String> {
        check(!WebProtectionLockManager.isLocked(context, WebProtectionSetting.ADULT_SITES)) {
            "Unlock adult-site protection with its recovery phrase before viewing or editing Chrome domain blocks."
        }
        return adultBlockedDomains(context)
    }

    fun updateAdultDomainBlocks(context: Context, domains: Set<String>) {
        check(!WebProtectionLockManager.isLocked(context, WebProtectionSetting.ADULT_SITES)) {
            "Unlock adult-site protection with its recovery phrase before viewing or editing Chrome domain blocks."
        }
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        check(dpm.isDeviceOwnerApp(context.packageName)) {
            "Set Fortress Vault as the device owner before editing Chrome domain blocks."
        }
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "Chrome domain blocking requires Android 10 or newer."
        }
        val chromeInstalled = isChromeInstalled(context)
        check(chromeInstalled != null) {
            "Android could not verify Google Chrome's installation status."
        }
        check(chromeInstalled) {
            "Install Google Chrome before editing domain blocks."
        }
        val normalized = domains.map(::normalizeWebsiteDomain).toSet()
        val previous = adultBlockedDomains(context)
        if (normalized == previous) return
        saveAdultBlockedDomains(context, normalized)
        if (applyChromeWebsitePolicy(context, force = true)) {
            return
        }

        val rollbackError = runCatching {
            saveAdultBlockedDomains(context, previous)
            check(applyChromeWebsitePolicy(context, force = true)) {
                "Chrome did not confirm restoration of the previous website policy."
            }
        }.exceptionOrNull()
        if (rollbackError != null) {
            throw IllegalStateException(
                "Chrome did not confirm the domain changes, and the previous list could not be restored.",
                rollbackError
            )
        }
        throw IllegalStateException("Chrome did not confirm the requested domain changes. The previous list was restored.")
    }

    fun hasSealedWebsiteTargets(context: Context): Boolean =
        hasCustomWebsiteRules(context) ||
            (blockSealedAppSites(context) && VaultManager.activeSeals(context).any { seal ->
                seal.packages.any { it in packageDomains }
            })

    fun hasCustomWebsiteRules(context: Context): Boolean =
        VaultManager.activeSeals(context).any { seal ->
            seal.websiteDomains.any { (packageName, domains) ->
                packageName in seal.packages && domains.isNotEmpty()
            }
        }

    fun isWebFilteringActive(context: Context): Boolean =
        blockAdultSites(context) || blockBypassApps(context) || hasSealedWebsiteTargets(context)

    fun isSettingEnabled(context: Context, setting: WebProtectionSetting): Boolean =
        when (setting) {
            WebProtectionSetting.ADULT_SITES -> blockAdultSites(context)
            WebProtectionSetting.CHROME_ONLY -> blockBypassApps(context)
            WebProtectionSetting.SEALED_APP_WEBSITES -> blockSealedAppSites(context)
        }

    fun setBlockAdultSites(context: Context, enabled: Boolean): WebProtectionUpdate {
        if (WebProtectionLockManager.isLocked(context, WebProtectionSetting.ADULT_SITES)) {
            return WebProtectionUpdate(false, "Enter this setting's recovery phrase before changing it.")
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return WebProtectionUpdate(false, "Adult-site filtering requires Android 10 (API 29) or newer.")
        }
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        if (!dpm.isDeviceOwnerApp(context.packageName)) {
            return WebProtectionUpdate(false, "Set Fortress Vault as the device owner before enabling adult-site filtering.")
        }
        val admin = FortressAdminReceiver.getComponentName(context)
        val prefs = preferences(context)
        val previous = blockAdultSites(context)
        val status = try {
            val status = if (enabled) dpm.setGlobalPrivateDnsModeSpecifiedHost(admin, ADULT_DNS_HOST)
            else dpm.setGlobalPrivateDnsModeOpportunistic(admin)
            status
        } catch (error: Exception) {
            Log.e(TAG, "Android could not update Private DNS", error)
            return WebProtectionUpdate(
                false,
                "Android couldn't update Private DNS: ${error.message ?: "check the device-owner policy and try again."}"
            )
        }
        if (status != DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR) {
            Log.e(TAG, "Android rejected the Private DNS policy with status $status")
            val message = when (status) {
                DevicePolicyManager.PRIVATE_DNS_SET_ERROR_HOST_NOT_SERVING ->
                    "Android could not reach $ADULT_DNS_HOST. Check your internet connection and that the filtering DNS service is reachable."
                DevicePolicyManager.PRIVATE_DNS_SET_ERROR_FAILURE_SETTING ->
                    "Android rejected the Private DNS change. Confirm Fortress is still the device owner and try again."
                else -> "Android couldn't apply the Private DNS policy (status $status). Check connectivity to the filtering DNS service and try again."
            }
            return WebProtectionUpdate(false, message)
        }
        prefs.edit()
            .putBoolean(KEY_BLOCK_ADULT, enabled)
            .putBoolean(KEY_APPLIED_ADULT, enabled)
            .apply()
        if (tryApply(context)) return WebProtectionUpdate(true)

        try {
            if (previous) dpm.setGlobalPrivateDnsModeSpecifiedHost(admin, ADULT_DNS_HOST)
            else dpm.setGlobalPrivateDnsModeOpportunistic(admin)
        } catch (error: Exception) {
            Log.e(TAG, "Could not roll back Private DNS after another policy failed", error)
        }
        prefs.edit()
            .putBoolean(KEY_BLOCK_ADULT, previous)
            .putBoolean(KEY_APPLIED_ADULT, previous)
            .apply()
        tryApply(context)
        return WebProtectionUpdate(
            false,
            "Private DNS responded, but Android rejected another required web-protection policy. Confirm Fortress is device owner and retry."
        )
    }

    fun setBlockBypassApps(context: Context, enabled: Boolean): Boolean {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        if (!dpm.isDeviceOwnerApp(context.packageName)) return false
        if (WebProtectionLockManager.isLocked(context, WebProtectionSetting.CHROME_ONLY)) return false
        val prefs = preferences(context)
        val previous = blockBypassApps(context)
        prefs.edit().putBoolean(KEY_BLOCK_BYPASS_APPS, enabled).apply()
        if (tryApply(context)) return true
        prefs.edit().putBoolean(KEY_BLOCK_BYPASS_APPS, previous).apply()
        tryApply(context)
        return false
    }

    fun setBlockSealedAppSites(context: Context, enabled: Boolean): Boolean {
        if (WebProtectionLockManager.isLocked(context, WebProtectionSetting.SEALED_APP_WEBSITES)) return false
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        if (!dpm.isDeviceOwnerApp(context.packageName)) return false
        val prefs = preferences(context)
        val previous = blockSealedAppSites(context)
        prefs.edit().putBoolean(KEY_BLOCK_SEALED_APP_SITES, enabled).apply()
        val applied = tryApply(context)
        if (applied) return true
        prefs.edit().putBoolean(KEY_BLOCK_SEALED_APP_SITES, previous).apply()
        tryApply(context)
        return false
    }

    fun apply(context: Context, force: Boolean = false): Boolean {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        if (!dpm.isDeviceOwnerApp(context.packageName)) return false
        val chromeApplied = try {
            applyChromePolicy(context, force)
        } catch (error: Exception) {
            Log.e(TAG, "Could not apply Chrome web restrictions", error)
            false
        }
        val bypassApplied = try {
            applyBypassProtection(context, force)
        } catch (error: Exception) {
            Log.e(TAG, "Could not apply VPN and alternate-browser restrictions", error)
            false
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return chromeApplied && bypassApplied && !blockAdultSites(context)

        val enabled = blockAdultSites(context)
        val admin = FortressAdminReceiver.getComponentName(context)
        val prefs = preferences(context)
        val dnsApplied = if (!force && prefs.getBoolean(KEY_APPLIED_ADULT, !enabled) == enabled) {
            true
        } else {
            val status = try {
                val status = if (enabled) dpm.setGlobalPrivateDnsModeSpecifiedHost(admin, ADULT_DNS_HOST)
                else dpm.setGlobalPrivateDnsModeOpportunistic(admin)
                status
            } catch (error: Exception) {
                Log.e(TAG, "Could not re-apply the Private DNS policy", error)
                null
            }
            val applied = status == DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR
            if (!applied) Log.e(TAG, "Private DNS policy returned status $status")
            if (applied) prefs.edit().putBoolean(KEY_APPLIED_ADULT, enabled).apply()
            applied
        }
        return chromeApplied && bypassApplied && dnsApplied
    }

    fun applyChromeWebsitePolicy(context: Context, force: Boolean = true): Boolean =
        try {
            val chromeApplied = applyChromePolicy(context, force)
            val bypassApplied = applyBypassProtection(context, force)
            chromeApplied && bypassApplied
        } catch (error: Exception) {
            Log.e(TAG, "Could not apply website restrictions", error)
            false
        }

    private fun applyBypassProtection(context: Context, force: Boolean): Boolean {
        val prefs = preferences(context)
        val enabled = blockBypassApps(context)
        val sealedPackages = VaultManager.blockedPackages(context)
        val needsReconcile = force ||
            prefs.getBoolean(KEY_APPLIED_BYPASS, !enabled) != enabled ||
            (enabled && prefs.getStringSet(KEY_APPLIED_SEALED_PACKAGES, emptySet()).orEmpty() != sealedPackages)
        if (!needsReconcile) return true

        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val admin = FortressAdminReceiver.getComponentName(context)
        val restrictionApplied = try {
            if (enabled) dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_VPN)
            else dpm.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_VPN)
            true
        } catch (error: Exception) {
            Log.e(TAG, "Could not ${if (enabled) "block" else "allow"} VPN configuration", error)
            false
        }
        if (!restrictionApplied) return false

        val managed = prefs.getStringSet(KEY_MANAGED_BYPASS_PACKAGES, emptySet()).orEmpty().toMutableSet()
        val previouslyHidden = prefs.getStringSet(KEY_PREVIOUSLY_HIDDEN_PACKAGES, emptySet()).orEmpty().toMutableSet()
        var applicationsApplied = true
        if (enabled) {
            val packagesToHide = bypassPackages + installedBrowserPackages(context) - CHROME_PACKAGE
            packagesToHide.forEach { packageName ->
                val installed = try {
                    context.packageManager.getPackageInfo(packageName, 0)
                    true
                } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                    false
                } catch (error: Exception) {
                    Log.e(TAG, "Could not check whether bypass app $packageName is installed", error)
                    applicationsApplied = false
                    false
                }
                if (!installed) return@forEach
                try {
                    if (packageName !in managed && dpm.isApplicationHidden(admin, packageName)) {
                        previouslyHidden += packageName
                    }
                    if (dpm.setApplicationHidden(admin, packageName, true)) {
                        managed += packageName
                    } else {
                        applicationsApplied = false
                        Log.e(TAG, "Android refused to hide bypass app $packageName")
                    }
                } catch (error: Exception) {
                    applicationsApplied = false
                    Log.e(TAG, "Could not hide bypass app $packageName", error)
                }
            }
        } else {
            val failedRestores = mutableSetOf<String>()
            managed.filterNot { it in previouslyHidden || it in sealedPackages }.forEach { packageName ->
                try {
                    if (!dpm.setApplicationHidden(admin, packageName, false)) {
                        applicationsApplied = false
                        failedRestores += packageName
                        Log.e(TAG, "Android refused to restore bypass app $packageName")
                    }
                } catch (error: Exception) {
                    applicationsApplied = false
                    failedRestores += packageName
                    Log.e(TAG, "Could not restore bypass app $packageName", error)
                }
            }
            managed.clear()
            managed.addAll(failedRestores)
            previouslyHidden.retainAll(failedRestores)
        }

        prefs.edit().apply {
            putStringSet(KEY_MANAGED_BYPASS_PACKAGES, managed)
            putStringSet(KEY_PREVIOUSLY_HIDDEN_PACKAGES, previouslyHidden)
            if (applicationsApplied) {
                putStringSet(KEY_APPLIED_SEALED_PACKAGES, sealedPackages)
                putBoolean(KEY_APPLIED_BYPASS, enabled)
            }
        }.apply()
        return applicationsApplied
    }

    private fun installedBrowserPackages(context: Context): Set<String> {
        return try {
            val browserIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER)
            context.packageManager.queryIntentActivities(browserIntent, PackageManager.MATCH_ALL)
                .mapNotNull { it.activityInfo?.packageName }
                .toSet()
        } catch (error: Exception) {
            Log.e(TAG, "Could not identify installed browser apps", error)
            throw error
        }
    }

    private fun tryApply(context: Context): Boolean = try {
        apply(context, force = true)
    } catch (error: Exception) {
        Log.e(TAG, "Could not apply web-protection policies", error)
        false
    }

    private fun applyChromePolicy(context: Context, force: Boolean): Boolean {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        if (!dpm.isDeviceOwnerApp(context.packageName)) return false
        val chromeInstalled = isChromeInstalled(context)
        if (chromeInstalled == null) return false
        val prefs = preferences(context)
        val sealedPackages = VaultManager.blockedPackages(context)
        val seals = VaultManager.activeSeals(context)
        val customDomains = seals.flatMap { seal ->
            seal.websiteDomains
                .filterKeys { it in seal.packages && it in sealedPackages }
                .values
                .flatten()
        }
        val knownDomains = if (blockSealedAppSites(context)) {
            sealedPackages.flatMap { packageDomains[it].orEmpty() }
        } else {
            emptyList()
        }
        val adultCustomDomains = if (blockAdultSites(context)) adultBlockedDomains(context) else emptySet()
        val domains = (knownDomains + customDomains + adultCustomDomains).toSortedSet()
        val patterns = domains.toSortedSet()
        val encoded = patterns.joinToString("\n")
        val disableIncognito = blockAdultSites(context) || patterns.isNotEmpty()
        if (!chromeInstalled) return !disableIncognito
        if (!force &&
            prefs.getString(KEY_APPLIED_CHROME_BLOCKLIST, null) == encoded &&
            prefs.getBoolean(KEY_APPLIED_CHROME_INCOGNITO_POLICY, !disableIncognito) == disableIncognito
        ) return true

        val restrictions = Bundle().apply {
            if (patterns.isNotEmpty()) putStringArray("URLBlocklist", patterns.toTypedArray())
            if (blockAdultSites(context)) {
                putString("DnsOverHttpsMode", "off")
                putBoolean("ForceGoogleSafeSearch", true)
            }
            if (disableIncognito) putInt("IncognitoModeAvailability", CHROME_INCOGNITO_DISABLED)
        }
        val applied = try {
            dpm.setApplicationRestrictions(
                FortressAdminReceiver.getComponentName(context),
                CHROME_PACKAGE,
                restrictions
            )
            val savedRestrictions = dpm.getApplicationRestrictions(
                FortressAdminReceiver.getComponentName(context),
                CHROME_PACKAGE
            )
            val incognitoApplied = if (disableIncognito) {
                savedRestrictions.getInt("IncognitoModeAvailability", -1) == CHROME_INCOGNITO_DISABLED
            } else {
                !savedRestrictions.containsKey("IncognitoModeAvailability")
            }
            val blocklistApplied = if (patterns.isNotEmpty()) {
                savedRestrictions.getStringArray("URLBlocklist")?.toSet() == patterns
            } else {
                !savedRestrictions.containsKey("URLBlocklist")
            }
            if (!incognitoApplied || !blocklistApplied) {
                Log.e(
                    TAG,
                    "Android did not persist Chrome policies " +
                        "(keys=${savedRestrictions.keySet()}, Incognito applied=$incognitoApplied, blocklist applied=$blocklistApplied)"
                )
            }
            incognitoApplied && blocklistApplied
        } catch (error: Exception) {
            Log.e(TAG, "Android rejected Chrome application restrictions", error)
            false
        }
        if (applied) prefs.edit()
            .putString(KEY_APPLIED_CHROME_BLOCKLIST, encoded)
            .putBoolean(KEY_APPLIED_CHROME_INCOGNITO_POLICY, disableIncognito)
            .apply()
        return applied
    }

    private fun isChromeInstalled(context: Context): Boolean? =
        try {
            context.packageManager.getPackageInfo(CHROME_PACKAGE, 0)
            true
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            false
        } catch (error: Exception) {
            Log.e(TAG, "Could not check whether Chrome is installed", error)
            null
        }

    private fun adultBlockedDomains(context: Context): Set<String> {
        val persisted = PersistentVaultStore.readAdultDomainBlocks(context)
        val json = persisted ?: preferences(context).getString(KEY_ADULT_DOMAIN_BLOCKS, null)
        if (json.isNullOrBlank()) return emptySet()
        return try {
            val array = JSONArray(json)
            buildSet {
                for (index in 0 until array.length()) {
                    add(normalizeWebsiteDomain(array.getString(index)))
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "Could not read the saved Chrome domain blocks", error)
            throw IllegalStateException("Saved Chrome domain-block data is damaged.", error)
        }
    }

    private fun saveAdultBlockedDomains(context: Context, domains: Set<String>) {
        val json = JSONArray(domains.sorted()).toString()
        PersistentVaultStore.writeAdultDomainBlocks(context, json)
        val mirrored = preferences(context).edit()
            .putString(KEY_ADULT_DOMAIN_BLOCKS, json)
            .commit()
        if (!mirrored) {
            Log.w(TAG, "Could not mirror Chrome domain blocks locally; the Device Owner record remains authoritative.")
        }
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}