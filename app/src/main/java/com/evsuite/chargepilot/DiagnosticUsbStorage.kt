package com.evsuite.chargepilot

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import java.io.File

/**
 * Volumes EVChargePilot can reach without a broad storage permission.
 *
 * **Discovery does not try to prove a volume is removable.** It used to, and on the MG4 head unit
 * that is why nothing was ever found: `Environment.isExternalStorageRemovable` and
 * `StorageVolume.isRemovable` both answer for a stick this car mounts outside the framework's
 * knowledge, and a mount named `/storage/MYSTICK` matches no naming rule anyone can write down.
 * EVTasker's browser has worked on this car since it stopped asking the question, so this asks it
 * the other way round — [isEmulated] excludes the one volume that must never receive an export,
 * primary emulated storage, and everything else is offered with the sticks sorted first.
 *
 * Three sources, deduplicated by canonical path, in order of trust:
 *  1. `getExternalFilesDirs()` walked up to its volume — right whenever the platform reports the
 *     volume at all, and needs no permission at any API level.
 *  2. [StorageManager]'s volume list — catches a stick the platform mounted but gave this app no
 *     `Android/data` directory on.
 *  3. The raw mount points below — catches this head unit, which does neither.
 */
object DiagnosticUsbStorage {

    private val volumeParents = listOf("/storage", "/mnt/media_rw", "/mnt/usb")
    private val directVolumes = listOf("/mnt/usbotg", "/mnt/udisk", "/udisk", "/mnt/external_sd")
    private val ignoredNames = setOf("emulated", "self", "container", "enc_emulated", "knox-emulated")

    /** Mounted volumes, removable first; primary emulated storage is never offered. */
    fun roots(context: Context): List<File> = runCatching { discoverRoots(context) }
        .getOrDefault(emptyList())

    private fun discoverRoots(context: Context): List<File> {
        val roots = LinkedHashMap<String, Pair<File, Boolean>>()

        fun add(dir: File, removable: Boolean) {
            if (!isListable(dir) || isEmulated(dir)) return
            // First source wins: it also carries the more trustworthy removable answer.
            roots.putIfAbsent(canonical(dir), dir to removable)
        }

        context.getExternalFilesDirs(null).filterNotNull().forEach { appDir ->
            add(appVisibleRoot(appDir), isRemovable(appDir))
        }
        storageManagerVolumes(context).forEach { (dir, removable) -> add(dir, removable) }
        volumeParents.forEach { parent ->
            runCatching { File(parent).listFiles() }.getOrNull()?.forEach { child ->
                if (!child.isHidden && child.name !in ignoredNames) add(child, removable = true)
            }
        }
        directVolumes.forEach { add(File(it), removable = true) }
        return roots.values
            .sortedWith(compareByDescending<Pair<File, Boolean>> { it.second }
                .thenBy { it.first.name.lowercase() })
            .map { it.first }
    }

    /**
     * Prefer the chosen directory. If its root is read-only, use this app's private folder on
     * the same removable volume; the report still lands on the USB stick without extra rights.
     */
    fun writableTarget(context: Context, chosen: File): File? =
        runCatching {
            if (isEmulated(chosen)) return null
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

    private fun storageManagerVolumes(context: Context): List<Pair<File, Boolean>> = runCatching {
        val manager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            ?: return emptyList()
        manager.storageVolumes.mapNotNull { volume ->
            volumeDirectory(volume)?.let { it to volume.isRemovable }
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

    /** Ordering only: a stick this car mounts itself answers false, which costs nothing but rank. */
    private fun isRemovable(dir: File): Boolean = try {
        Environment.isExternalStorageRemovable(dir)
    } catch (_: IllegalArgumentException) {
        false
    }

    /**
     * The one exclusion left, and the only one a security rule needs.
     *
     * SECURITY.md promises the routing key leaves on a stick and never into storage the driver
     * cannot unplug; a diagnostic bundle carries vehicle data and owes the same. Asking whether a
     * volume *is* internal is answerable — a throw or a false means "not internal", which is the
     * permissive direction and the one that stopped this finding nothing.
     */
    private fun isEmulated(dir: File): Boolean = try {
        Environment.isExternalStorageEmulated(dir)
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun canonical(file: File): String =
        runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)

    private fun isListable(directory: File): Boolean =
        runCatching { directory.isDirectory && directory.listFiles() != null }.getOrDefault(false)

}
