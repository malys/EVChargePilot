package com.evsuite.chargepilot

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.TimeZone

/** Stable build/runtime identity needed to reproduce a head-unit observation. */
internal object DiagnosticRuntimeContext {

    fun collect(context: Context, nowMs: Long = System.currentTimeMillis()): List<String> {
        val packageInfo = runCatching {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_PERMISSIONS,
            )
        }.getOrNull()
        val apk = File(context.applicationInfo.sourceDir)
        val runtime = Runtime.getRuntime()
        return buildList {
            add("application_id=${context.packageName}")
            add("version_name=${packageInfo?.versionName ?: "unavailable"}")
            add("version_code=${packageInfo?.longVersionCode ?: -1L}")
            add("build_channel=${if (context.packageName.endsWith(".unstable")) "unstable" else "stable"}")
            add(
                "build_debuggable=" +
                    ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
            )
            add("apk_bytes=${apk.takeIf(File::isFile)?.length() ?: -1L}")
            add("apk_sha256=${sha256(apk) ?: "unavailable"}")
            add("signer_sha256=${signerHashes(packageInfo).ifEmpty { listOf("unavailable") }.joinToString(",")}")
            add("generated_epoch_ms=$nowMs")
            add("process_uptime_ms=${SystemClock.elapsedRealtime()}")
            add("timezone=${TimeZone.getDefault().id}")
            add("timezone_offset_ms=${TimeZone.getDefault().getOffset(nowMs)}")
            add("locale=${Locale.getDefault().toLanguageTag()}")
            add("android_sdk=${Build.VERSION.SDK_INT}")
            add("android_release=${Build.VERSION.RELEASE}")
            add("build_fingerprint=${Build.FINGERPRINT}")
            add("manufacturer=${Build.MANUFACTURER}")
            add("model=${Build.MODEL}")
            add("device=${Build.DEVICE}")
            add("hardware=${Build.HARDWARE}")
            add("board=${Build.BOARD}")
            add("abis=${Build.SUPPORTED_ABIS.joinToString(",")}")
            add("heap_used_bytes=${runtime.totalMemory() - runtime.freeMemory()}")
            add("heap_committed_bytes=${runtime.totalMemory()}")
            add("heap_max_bytes=${runtime.maxMemory()}")
            add("internal_files_bytes=${directoryBytes(context.filesDir)}")
            add("internal_usable_bytes=${context.filesDir.usableSpace}")
            add("internal_total_bytes=${context.filesDir.totalSpace}")
            packageInfo?.requestedPermissions.orEmpty().forEachIndexed { index, permission ->
                val granted = packageInfo?.requestedPermissionsFlags
                    ?.getOrNull(index)
                    ?.and(PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
                add("permission.$index=$permission;granted=$granted")
            }
            add("privacy_excluded=VIN,serial,location,accounts")
        }
    }

    private fun signerHashes(packageInfo: android.content.pm.PackageInfo?): List<String> =
        packageInfo?.signingInfo?.apkContentsSigners.orEmpty().map { signature ->
            DiagnosticExporter.sha256(signature.toByteArray())
        }

    private fun sha256(file: File): String? {
        if (!file.isFile) return null
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
        }.getOrNull()
    }

    /** Only files owned by this app; bounded evidence/trip/model stores keep this traversal small. */
    private fun directoryBytes(root: File): Long = runCatching {
        root.walkTopDown().filter(File::isFile).sumOf(File::length)
    }.getOrDefault(-1L)
}
