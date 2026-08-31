package com.evsuite.chargepilot

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import java.io.File

/** Removable volumes EVChargePilot can reach without a broad storage permission. */
object DiagnosticUsbStorage {

    private val volumeParents = listOf("/storage", "/mnt/media_rw", "/mnt/usb")
    private val directVolumes = listOf("/mnt/usbotg", "/mnt/udisk", "/udisk", "/mnt/external_sd")
    private val ignoredNames = setOf("emulated", "self", "container", "enc_emulated", "knox-emulated")

    /** Mounted removable roots only; internal AAOS storage is deliberately never offered. */
    fun roots(context: Context): List<File> = runCatching { discoverRoots(context) }
        .getOrDefault(emptyList())

    private fun discoverRoots(context: Context): List<File> {
        val roots = LinkedHashMap<String, File>()

        fun add(dir: File) {
            if (!isListable(dir)) return
            roots.putIfAbsent(canonical(dir), dir)
        }

        context.getExternalFilesDirs(null).filterNotNull().forEach { appDir ->
            if (isRemovable(appDir)) add(appVisibleRoot(appDir))
        }
        storageManagerVolumes(context).forEach(::add)
        volumeParents.forEach { parent ->
            runCatching { File(parent).listFiles() }.getOrNull()?.forEach { child ->
                if (!child.isHidden && child.name !in ignoredNames && rawPathProvesRemovable(parent, child)) {
                    add(child)
                }
            }
        }
        directVolumes.forEach { add(File(it)) }
        return roots.values.sortedBy { it.name.lowercase() }
    }

    /**
     * Prefer the chosen directory. If its root is read-only, use this app's private folder on
     * the same removable volume; the report still lands on the USB stick without extra rights.
     */
    fun writableTarget(context: Context, chosen: File): File? =
        runCatching {
            val picked = canonical(chosen)
            if (roots(context).none { canonical(it) == picked }) return null
            writableTarget(chosen, context.getExternalFilesDirs(null).filterNotNull())
        }.getOrNull()

    internal fun writableTarget(chosen: File, appDirectories: List<File>): File? {
        if (canWriteInto(chosen)) return chosen
        val picked = canonical(chosen)
        for (appDir in appDirectories) {
            val volume = volumeRoot(appDir) ?: continue
            val volumePath = canonical(volume)
            if (picked != volumePath && !picked.startsWith("$volumePath${File.separator}")) continue
            runCatching { appDir.mkdirs() }
            if (canWriteInto(appDir)) return appDir
        }
        return null
    }

    /** Permission bits are unreliable on FAT; a unique create/delete probe is authoritative. */
    private fun canWriteInto(dir: File): Boolean {
        if (!dir.isDirectory) return false
        val probe = File(dir, ".evchargepilot-write-${System.nanoTime()}")
        var created = false
        return try {
            probe.createNewFile().also { created = it }
        } catch (_: Exception) {
            false
        } finally {
            if (created) runCatching { probe.delete() }
        }
    }

    private fun storageManagerVolumes(context: Context): List<File> = runCatching {
        val manager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            ?: return emptyList()
        manager.storageVolumes.mapNotNull { volume ->
            if (volume.isRemovable) volumeDirectory(volume) else null
        }
    }.getOrDefault(emptyList())

    private fun volumeDirectory(volume: StorageVolume): File? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            volume.directory
        } else {
            runCatching {
                StorageVolume::class.java.getMethod("getPath").invoke(volume) as? String
            }.getOrNull()?.let(::File)
        }

    private fun volumeRoot(appDirectory: File): File? {
        var root: File? = appDirectory
        repeat(4) { root = root?.parentFile }
        return root?.takeIf { it.isDirectory }
    }

    /** A non-system app may list its USB folder even when the same volume's root is opaque. */
    internal fun appVisibleRoot(
        appDirectory: File,
        canList: (File) -> Boolean = ::isListable,
    ): File = volumeRoot(appDirectory)?.takeIf(canList) ?: appDirectory

    private fun isRemovable(dir: File): Boolean = try {
        Environment.isExternalStorageRemovable(dir)
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun canonical(file: File): String =
        runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)

    private fun isListable(directory: File): Boolean =
        runCatching { directory.isDirectory && directory.listFiles() != null }.getOrDefault(false)

    /** Raw fallbacks count only when their mount naming itself identifies removable media. */
    private fun rawPathProvesRemovable(parent: String, child: File): Boolean = when (parent) {
        "/mnt/usb" -> true
        "/storage", "/mnt/media_rw" ->
            REMOVABLE_VOLUME_NAME.matches(child.name) ||
                child.name.contains("usb", ignoreCase = true) ||
                child.name.contains("udisk", ignoreCase = true)
        else -> false
    }

    private val REMOVABLE_VOLUME_NAME = Regex("^[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}$")
}
