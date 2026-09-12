package com.evsuite.chargepilot.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.evsuite.hardware.AppLogger
import java.io.File
import java.security.MessageDigest

/**
 * Signing-certificate fingerprints, used to prove a downloaded APK was signed by the same
 * key as the running app. Unstable channel only.
 *
 * Every method fails closed: an unreadable archive, a missing signature or a failed platform
 * call returns an empty set, and [matchesRunningApp] never matches an empty set — so
 * "could not verify" and "does not match" are the same answer, and both refuse.
 */
internal object ApkSignature {

    private const val TAG = "EV_UPDATE"

    /** True when [apk] carries exactly the certificates the installed app carries. */
    fun matchesRunningApp(context: Context, apk: File): Boolean {
        val archive = ofArchive(context, apk)
        val installed = ofInstalled(context)
        val ok = archive.isNotEmpty() && installed.isNotEmpty() && archive == installed
        if (!ok) {
            AppLogger.w(
                TAG,
                "APK signature mismatch — update refused " +
                    "(archive=${archive.size} cert(s), installed=${installed.size} cert(s))"
            )
        }
        return ok
    }

    private fun ofArchive(context: Context, apk: File): Set<String> = try {
        digests(
            context.packageManager.getPackageArchiveInfo(
                apk.absolutePath,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
        )
    } catch (e: Exception) {
        AppLogger.w(TAG, "Cannot read archive signature: ${e.message}")
        emptySet()
    }

    private fun ofInstalled(context: Context): Set<String> = try {
        digests(
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
        )
    } catch (e: Exception) {
        AppLogger.w(TAG, "Cannot read own signature: ${e.message}")
        emptySet()
    }

    // minSdk is 28, so SigningInfo always exists and the deprecated `signatures` array is
    // never needed. A multi-signer APK reports its current signers; a single-signer one
    // reports its rotation history, which is what lets a rotated key still match.
    private fun digests(info: PackageInfo?): Set<String> {
        val signingInfo = info?.signingInfo ?: return emptySet()
        val signatures = if (signingInfo.hasMultipleSigners()) {
            signingInfo.apkContentsSigners
        } else {
            signingInfo.signingCertificateHistory
        } ?: return emptySet()
        val sha256 = MessageDigest.getInstance("SHA-256")
        return signatures.mapNotNull { signature ->
            signature?.toByteArray()?.let { bytes ->
                sha256.digest(bytes).joinToString("") { "%02x".format(it) }
            }
        }.toSet()
    }
}
