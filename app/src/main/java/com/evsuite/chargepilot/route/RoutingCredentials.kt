package com.evsuite.chargepilot.route

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.evsuite.hardware.AppLogger

/**
 * Where the routing key lives: an AndroidKeyStore-backed encrypted preferences file.
 *
 * The same choice EVABRPUploader made for the ABRP credentials, for the same reason — a
 * plaintext preferences file is what `adb backup` hands out — and with the same fallback, which
 * matters more than the encryption: a head unit with a broken keystore must end up with a
 * working app, not a crash in a car.
 *
 * **The key never leaves this file.** Not into `AppLogger`, not into a diagnostic export, not
 * into the USB bundle. `DiagnosticExporter` reads nothing from here and must keep not reading
 * anything from here.
 */
object RoutingCredentials {

    /**
     * @param header the request header the key travels in. Two services, two names — ORS reads
     *   `Authorization`, Open Charge Map reads `X-API-Key` — and neither reads a query string,
     *   which is the part that is not negotiable.
     */
    data class Values(
        val apiKey: String,
        val baseUrl: String,
        val header: String = "Authorization",
    )

    private const val TAG = "RoutingCredentials"
    private const val FILE_NAME = "chargepilot_routing"
    private const val KEY_API_KEY = "ors_api_key"
    private const val KEY_BASE_URL = "ors_base_url"
    private const val KEY_CHARGER_API_KEY = "ocm_api_key"
    private const val KEY_CHARGER_BASE_URL = "ocm_base_url"

    @Volatile
    private var instance: SharedPreferences? = null

    @Synchronized
    private fun preferences(context: Context): SharedPreferences {
        instance?.let { return it }
        val app = context.applicationContext
        val prefs = try {
            val masterKey = MasterKey.Builder(app)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                app,
                FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            AppLogger.w(TAG, "encrypted preferences unavailable: ${e.javaClass.simpleName}")
            app.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        }
        instance = prefs
        return prefs
    }

    /** Configured credentials, or null — a base URL with no key routes nothing. */
    fun read(context: Context): Values? {
        val prefs = preferences(context)
        val key = prefs.getString(KEY_API_KEY, null)?.trim().orEmpty()
        if (key.isEmpty()) return null
        val base = prefs.getString(KEY_BASE_URL, null)?.trim().orEmpty()
            .ifEmpty { RoutingConfig.DEFAULT_BASE_URL }
        return Values(key, base)
    }

    /**
     * The charger service's own key, or null. Separate from the routing key on purpose: a driver
     * who configures one gets that feature and is not blocked from routing because they have not
     * signed up for the other.
     */
    fun readCharger(context: Context): Values? {
        val prefs = preferences(context)
        val key = prefs.getString(KEY_CHARGER_API_KEY, null)?.trim().orEmpty()
        if (key.isEmpty()) return null
        val base = prefs.getString(KEY_CHARGER_BASE_URL, null)?.trim().orEmpty()
            .ifEmpty { OpenChargeMap.DEFAULT_BASE_URL }
        return Values(key, base, OpenChargeMap.HEADER)
    }

    fun isConfigured(context: Context): Boolean = read(context) != null

    fun isChargerConfigured(context: Context): Boolean = readCharger(context) != null

    fun baseUrl(context: Context): String =
        preferences(context).getString(KEY_BASE_URL, null)?.trim()?.ifEmpty { null }
            ?: RoutingConfig.DEFAULT_BASE_URL

    /** Applies only what was set, so importing a key does not reset a self-hosted base URL. */
    fun apply(context: Context, config: RoutingConfig) {
        preferences(context).edit().apply {
            config.apiKey?.let { putString(KEY_API_KEY, it) }
            config.baseUrl?.let { putString(KEY_BASE_URL, it) }
            config.chargerApiKey?.let { putString(KEY_CHARGER_API_KEY, it) }
            config.chargerBaseUrl?.let { putString(KEY_CHARGER_BASE_URL, it) }
        }.apply()
    }

    fun clear(context: Context) {
        preferences(context).edit()
            .remove(KEY_API_KEY)
            .remove(KEY_BASE_URL)
            .remove(KEY_CHARGER_API_KEY)
            .remove(KEY_CHARGER_BASE_URL)
            .apply()
    }
}
