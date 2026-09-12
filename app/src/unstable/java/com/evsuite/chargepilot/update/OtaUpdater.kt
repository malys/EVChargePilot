package com.evsuite.chargepilot.update

import android.content.Context
import android.os.Environment
import com.evsuite.hardware.AppLogger
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import org.json.JSONObject

/**
 * Unstable-channel update check and download. The APK is never installed from here.
 *
 * EVChargePilot holds no install capability — no `REQUEST_INSTALL_PACKAGES`, no privileged
 * `pm install`, no system UID — so the most this channel can do, and all it does, is put a
 * verified archive where the driver can find it and say so. Installing it is a deliberate
 * tap in the head unit's own package installer. That is the shape the suite audit reviewed
 * for EVProfile's unstable channel, and this is the same shape with the install half absent.
 *
 * Security controls, all failing closed:
 *  - the APK URL comes out of a remote JSON document and is never trusted: `https` only and
 *    an exact-match host allowlist, re-checked on the initial URL and on every redirect hop;
 *  - a size ceiling on the declared *and* the transferred length, so a hostile asset cannot
 *    fill the head unit's storage;
 *  - the download lands in app-private cache and only reaches shared storage after its
 *    signing certificate is proven identical to the running app's.
 */
internal object OtaUpdater {

    private const val TAG = "EV_UPDATE"
    private const val CACHE_PREFIX = "EVChargePilot-ota-"
    private const val ASSET_PREFIX = "EVChargePilot-unstable-"

    /**
     * The unstable channel is one rolling pre-release, always tagged `unstable` and
     * overwritten by every build (`.github/workflows/unstable.yml`), so the version lives in
     * the asset name and this endpoint is a single fixed document rather than a release list.
     */
    private const val RELEASE_API =
        "https://api.github.com/repos/malys/EVChargePilot/releases/tags/unstable"

    private const val TIMEOUT_MS = 15_000
    private const val MAX_REDIRECTS = 6

    /** The unstable APK is ~5 MB. Beyond this we refuse rather than fill the car's disk. */
    private const val MAX_APK_BYTES = 100L * 1024 * 1024

    /**
     * Hosts an update may come from. The two `githubusercontent.com` entries are the CDNs
     * GitHub redirects a release-asset download to; without them every download is refused.
     * Matching is exact, never a suffix test — `github.com.attacker.net` is not GitHub.
     */
    private val ALLOWED_HOSTS = setOf(
        "api.github.com",
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com"
    )

    data class Update(val versionName: String, val apkUrl: String)

    /** True if [url] is `https` and points at an allowed host. Everything else is refused. */
    fun isAllowedUrl(url: String): Boolean {
        val uri = try { URI(url) } catch (_: Exception) { return false }
        if (uri.scheme?.equals("https", ignoreCase = true) != true) return false
        val host = uri.host?.lowercase(Locale.US) ?: return false
        return host in ALLOWED_HOSTS
    }

    /**
     * Numeric core of a version: `"0.2.0.43-unstable"` → `[0, 2, 0, 43]`. A segment carrying
     * no digits becomes 0 rather than being dropped, so the segments after it do not shift
     * left and compare against the wrong position.
     */
    fun segments(version: String): List<Int> =
        version.trimStart('v', 'V')
            .substringBefore('+')
            .substringBefore('-')
            .split('.')
            .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

    /** True if [remote] is strictly higher than [current]. Equal versions are not updates. */
    fun isNewer(remote: String, current: String): Boolean {
        val r = segments(remote)
        val c = segments(current)
        for (index in 0 until maxOf(r.size, c.size)) {
            val remotePart = r.getOrElse(index) { 0 }
            val currentPart = c.getOrElse(index) { 0 }
            if (remotePart > currentPart) return true
            if (remotePart < currentPart) return false
        }
        return false
    }

    /** Version carried by an asset name: `"EVChargePilot-unstable-0.2.0.43.apk"` → `"0.2.0.43"`. */
    fun versionFromAssetName(assetName: String): String? {
        if (!assetName.startsWith(ASSET_PREFIX, ignoreCase = true)) return null
        return Regex("-(\\d[0-9.]*?)\\.apk$", RegexOption.IGNORE_CASE)
            .find(assetName)?.groupValues?.get(1)
    }

    /**
     * Name the downloaded APK is published under. The version comes from a remote asset name,
     * so it is reduced to a safe character set before it ever reaches a path — a version of
     * `"../../etc"` must not be able to choose where the file lands.
     */
    fun fileName(versionName: String?): String {
        val safe = if (versionName.isNullOrBlank()) "unknown" else versionName
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9._-]"), "_")
        return "$ASSET_PREFIX$safe.apk"
    }

    /**
     * Asks GitHub for the rolling pre-release and returns it when it beats [currentVersion].
     * Blocking network work — never call this from the main thread.
     */
    fun check(currentVersion: String): Update? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(RELEASE_API).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "EVChargePilot-Android")
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
            }
            if (connection.responseCode != 200) {
                AppLogger.w(TAG, "Release API returned ${connection.responseCode}")
                return null
            }
            val json = JSONObject(
                connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            )
            // A tag that stopped being a pre-release is not this channel: a stable APK carries
            // a different application id and could not update an unstable install anyway.
            if (!json.optBoolean("prerelease", false)) {
                AppLogger.w(TAG, "Tag 'unstable' is not a pre-release — ignored")
                return null
            }
            val assets = json.optJSONArray("assets") ?: return null
            for (index in 0 until assets.length()) {
                val asset = assets.getJSONObject(index)
                val version = versionFromAssetName(asset.optString("name", "")) ?: continue
                if (!isNewer(version, currentVersion)) continue
                val url = asset.optString("browser_download_url", "")
                if (!isAllowedUrl(url)) {
                    AppLogger.w(TAG, "Rejected update URL from an unexpected host: $url")
                    continue
                }
                return Update(version, url)
            }
            null
        } catch (e: Exception) {
            AppLogger.w(TAG, "Update check failed: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Downloads into app-private cache, following redirects by hand so that every hop is
     * re-checked. Returns null on any refusal or failure; the partial file never survives.
     */
    fun download(context: Context, update: Update): File? {
        if (!isAllowedUrl(update.apkUrl)) {
            AppLogger.w(TAG, "Refusing to download from ${update.apkUrl}")
            return null
        }
        val temporary = File.createTempFile(CACHE_PREFIX, ".apk", context.cacheDir)
        var current = update.apkUrl
        var kept = false
        try {
            repeat(MAX_REDIRECTS) {
                if (!isAllowedUrl(current)) return null
                val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "EVChargePilot-Android")
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                }
                try {
                    val status = connection.responseCode
                    if (status in 300..399) {
                        val location = connection.getHeaderField("Location") ?: return null
                        // Resolved against the current URL: a `Location` may be relative, and
                        // a bare path would otherwise fail the allowlist for the wrong reason.
                        current = URI(current).resolve(location).toString()
                        return@repeat
                    }
                    if (status != HttpURLConnection.HTTP_OK) {
                        AppLogger.w(TAG, "Update download returned $status")
                        return null
                    }
                    // The declared length is a claim; the transferred length is the fact.
                    // Both are capped, so neither a lying header nor a chunked response can
                    // fill the head unit's storage.
                    if (connection.contentLengthLong > MAX_APK_BYTES) return null
                    var written = 0L
                    connection.inputStream.use { input ->
                        temporary.outputStream().use { output ->
                            val buffer = ByteArray(8_192)
                            while (true) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                written += read
                                if (written > MAX_APK_BYTES) return null
                                output.write(buffer, 0, read)
                            }
                            output.fd.sync()
                        }
                    }
                    if (written == 0L) return null
                    kept = true
                    return temporary
                } finally {
                    connection.disconnect()
                }
            }
            AppLogger.w(TAG, "Update download exceeded $MAX_REDIRECTS redirects")
            return null
        } catch (e: Exception) {
            AppLogger.w(TAG, "Update download failed: ${e.message}")
            return null
        } finally {
            // Only a returned file survives. A partial body left in the cache after a refused
            // redirect or a dropped connection is an unverified archive on disk, which is
            // exactly what the signature check exists to keep out of the driver's reach.
            if (!kept) temporary.delete()
        }
    }

    /**
     * Verifies [apk] against the running app's signing certificate and, only then, copies it
     * to the download folder. Returns the published file, or null when anything refused —
     * in which case the archive is deleted rather than left for someone to find and install.
     */
    fun publish(context: Context, apk: File, versionName: String): File? {
        try {
            if (!ApkSignature.matchesRunningApp(context, apk)) return null
            val directory = downloadDirectory(context) ?: return null
            val target = File(directory, fileName(versionName))
            // Unique per version, so publishing is a plain copy: no delete-then-rename, and
            // therefore no window in which the driver sees a half-written APK.
            val staging = File(directory, "${target.name}.part")
            staging.delete()
            apk.copyTo(staging, overwrite = true)
            if (!staging.renameTo(target)) {
                staging.delete()
                return null
            }
            purgeOlderApks(directory, keep = target)
            return target
        } catch (e: Exception) {
            AppLogger.w(TAG, "Publishing the update failed: ${e.message}")
            return null
        } finally {
            apk.delete()
        }
    }

    /**
     * Where a downloaded update is left for the driver: the head unit's own `Download` folder.
     *
     * Reaching it on API 28 costs `WRITE_EXTERNAL_STORAGE`, which the unstable manifest
     * declares and the dashboard asks for. The grant is re-read here, at the moment of
     * writing, rather than trusted from the request — and `canWrite` is the ground truth, not
     * the permission: from API 29 scoped storage refuses the write with the permission held.
     *
     * A refusal is a supported state, not a failure: the APK goes to this app's own `Download`
     * directory on the same volume, which needs no permission on any API level and which an
     * uninstall takes with it. The dialog names whichever path was used, so the driver is never
     * sent to look in the wrong folder.
     */
    fun downloadDirectory(context: Context): File? {
        val public = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (public != null) {
            if (!public.isDirectory) public.mkdirs()
            if (public.isDirectory && public.canWrite()) return public
            AppLogger.w(TAG, "Public Download folder not writable — using the app's own")
        }
        return context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?.also { if (!it.isDirectory) it.mkdirs() }
    }

    /** Keeps one published APK. A car's storage is small and every older build is dead weight. */
    private fun purgeOlderApks(directory: File, keep: File) {
        directory.listFiles { file ->
            file.isFile &&
                file != keep &&
                file.name.startsWith(ASSET_PREFIX, ignoreCase = true) &&
                file.name.endsWith(".apk", ignoreCase = true)
        }?.forEach { stale ->
            if (!stale.delete()) AppLogger.w(TAG, "Could not delete old update ${stale.name}")
        }
    }
}
