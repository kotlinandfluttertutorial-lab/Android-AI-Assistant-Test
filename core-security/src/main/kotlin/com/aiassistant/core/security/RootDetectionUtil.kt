/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-security
 * File       : RootDetectionUtil.kt
 * Purpose    : RootDetectionUtil — core-security module component
 *
 * Architecture Layer : Core-Security
 * Pattern Used       : Kotlin Class
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Hilt dependency injection
 *
 * Dependencies:
 *   - See import statements below
 * ============================================================
 */

/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-security
 * File       : RootDetectionUtil.kt
 * Purpose    : RootDetectionUtil — core-security module component
 *
 * Architecture Layer : Core-Security
 * Pattern Used       : Kotlin Class
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Hilt dependency injection
 *
 * Dependencies:
 *   - See import statements below
 * ============================================================
 */
/**
 * RootDetectionUtil.kt â€” core-security module
 *
 * Provides heuristic root-detection and emulator-detection checks to warn users
 * when the device may be compromised. Detection is advisory â€” it does not block
 * the app, but callers can choose to surface a warning dialog or restrict sensitive
 * features accordingly.
 *
 * Checks performed:
 *   1. Presence of `su` binary on common PATH locations
 *   2. Build tag containing "test-keys" (AOSP engineering/debug builds)
 *   3. Presence of known root management app packages (Magisk, SuperSU, etc.)
 *   4. Emulator detection via known build fingerprints / hardware identifiers
 *
 * Note: No root-detection heuristic is foolproof. Determined attackers can
 * hide root indicators. This check provides a reasonable baseline warning
 * for the majority of rooted consumer devices.
 */
package com.aiassistant.core.security

import android.content.Context
import android.os.Build
import java.io.File

/**
 * Represents the integrity status of the current device.
 *
 * Callers should surface a warning for [Rooted] and [Emulator] statuses
 * when handling sensitive data, but these do NOT hard-block the application.
 */
sealed class RootStatus {
    /** No root indicators or emulator fingerprints detected. */
    data object Clean : RootStatus()

    /**
     * One or more root indicators were detected (su binary, test-keys build,
     * or a known root management app is installed).
     */
    data class Rooted(val indicators: List<RootIndicator>) : RootStatus()

    /**
     * The app is running inside an emulator or virtual device, which may
     * indicate an automated testing/reversing environment.
     */
    data object Emulator : RootStatus()
}

/** Describes which specific root check triggered a [RootStatus.Rooted] result. */
enum class RootIndicator {
    SU_BINARY_FOUND,
    TEST_KEYS_BUILD_TAG,
    ROOT_MANAGEMENT_APP_INSTALLED
}

/**
 * Utility object for runtime device integrity checks.
 */
object RootDetectionUtil {

    // Known root management app package names
    private val ROOT_PACKAGES = setOf(
        "com.topjohnwu.magisk", // Magisk
        "eu.chainfire.supersu", // SuperSU
        "com.koushikdutta.superuser", // Koush Superuser
        "com.noshufou.android.su", // Superuser (legacy)
        "com.thirdparty.superuser",
        "com.yellowes.su",
        "com.kingroot.kinguser", // KingRoot
        "com.kingo.root", // KingoRoot
        "com.smedialink.oneclickapps",
        "com.zhiqupk.root.global",
        "com.alephzain.framaroot", // Framaroot
        "com.saurik.substrate", // Cydia Substrate
        "de.robv.android.xposed.installer" // Xposed Framework
    )

    // Directories searched for `su` binary
    private val SU_PATHS = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/system/su",
        "/system/bin/.ext/.su",
        "/system/usr/we-need-root/su-backup",
        "/system/xbin/mu",
        "/data/local/su",
        "/data/local/xbin/su",
        "/data/local/bin/su"
    )

    // Known emulator build fingerprint substrings
    private val EMULATOR_FINGERPRINTS = setOf(
        "generic",
        "unknown",
        "google_sdk",
        "Emulator",
        "Android SDK built for x86"
    )

    /**
     * Evaluates device integrity and returns a [RootStatus].
     *
     * Evaluation order:
     * 1. Emulator detection (takes precedence â€” an emulated device may also
     *    appear rooted, but the emulator status is more actionable).
     * 2. Root checks â€” if any indicator fires, returns [RootStatus.Rooted]
     *    with the list of triggered [RootIndicator]s.
     * 3. Falls back to [RootStatus.Clean] when no issues are found.
     *
     * This method never throws; all internal checks are wrapped in try/catch.
     *
     * @param context used to query the package manager for root app packages
     */
    fun getDeviceStatus(context: Context): RootStatus {
        if (isEmulator()) return RootStatus.Emulator

        val indicators = buildList {
            if (hasSuBinary()) add(RootIndicator.SU_BINARY_FOUND)
            if (hasTestKeysBuildTag()) add(RootIndicator.TEST_KEYS_BUILD_TAG)
            if (hasRootManagementApp(context)) add(RootIndicator.ROOT_MANAGEMENT_APP_INSTALLED)
        }

        return if (indicators.isNotEmpty()) RootStatus.Rooted(indicators) else RootStatus.Clean
    }

    /**
     * Convenience wrapper â€” returns true when [getDeviceStatus] is anything other
     * than [RootStatus.Clean]. Useful for simple boolean guards.
     *
     * @param context used to query the package manager for root app packages
     */
    fun isDeviceCompromised(context: Context): Boolean = getDeviceStatus(context) != RootStatus.Clean

    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    // Internal checks
    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Checks for the presence of the `su` binary in common filesystem locations.
     */
    internal fun hasSuBinary(): Boolean = SU_PATHS.any { path ->
        try {
            File(path).exists()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Checks the build tags for "test-keys", which indicates a non-release
     * Android build often associated with rooted or unlocked bootloader devices.
     */
    internal fun hasTestKeysBuildTag(): Boolean = try {
        Build.TAGS?.contains("test-keys") == true
    } catch (_: Exception) {
        false
    }

    /**
     * Checks whether any known root management app is installed on the device.
     *
     * @param context used to access the PackageManager
     */
    internal fun hasRootManagementApp(context: Context): Boolean {
        val packageManager = context.packageManager
        return ROOT_PACKAGES.any { packageName ->
            try {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * Checks whether the app is running inside an emulator using build metadata.
     *
     * Inspects [Build.FINGERPRINT], [Build.MODEL], [Build.MANUFACTURER],
     * [Build.BRAND], [Build.DEVICE], and [Build.PRODUCT] for known emulator
     * identifiers.
     */
    internal fun isEmulator(): Boolean = try {
        val fingerprint = Build.FINGERPRINT.orEmpty()
        val isKnownFingerprint = EMULATOR_FINGERPRINTS.any { fingerprint.contains(it, ignoreCase = true) }
        val isGenericBrand = Build.BRAND.startsWith("generic") || Build.BRAND == "Android"
        val isGenericDevice = Build.DEVICE.startsWith("generic")
        val isEmulatorProduct = Build.PRODUCT.contains("sdk", ignoreCase = true) ||
            Build.PRODUCT.contains("emulator", ignoreCase = true) ||
            Build.PRODUCT.contains("simulator", ignoreCase = true)
        val isGoogleApiEmulator = Build.MODEL.contains("google_sdk", ignoreCase = true) ||
            Build.MODEL.contains("Emulator", ignoreCase = true) ||
            Build.MODEL.contains("Android SDK built for x86", ignoreCase = true)

        isKnownFingerprint ||
            isGenericBrand ||
            isGenericDevice ||
            isEmulatorProduct ||
            isGoogleApiEmulator
    } catch (_: Exception) {
        false
    }
}
