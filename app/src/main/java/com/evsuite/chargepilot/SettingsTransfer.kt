package com.evsuite.chargepilot

import android.content.Context
import com.evsuite.chargepilot.route.DestinationFavorites
import com.evsuite.chargepilot.route.OrsGeocode
import com.evsuite.chargepilot.route.RoutingConfig
import com.evsuite.chargepilot.route.RoutingCredentials
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * Every setting the driver owns, in one JSON file on a USB stick.
 *
 * There used to be a `key = value` file for the keys and the destinations, and nothing at all
 * for the car's own figures: a driver setting up a second car — or the unstable channel, which
 * is a separate application id with its own preferences — got half their configuration back and
 * retyped the rest on a head unit keyboard. One file now carries all of it.
 *
 * ```json
 * {
 *   "format": "evchargepilot-settings",
 *   "version": 1,
 *   "routing": { "ors_api_key": "5b3ce35...", "ors_base_url": "https://api.heigit.org" },
 *   "vehicle": { "usable_capacity_kwh_when_new": 61.7, "state_of_health_percent": 92.0 },
 *   "destinations": [ { "label": "Home", "longitude": 5.4474, "latitude": 43.5297 } ]
 * }
 * ```
 *
 * **The file carries the API keys in clear text**, because that is what a file this app can
 * import back has to be. The stick is then the secret: the `note` field says so to whoever opens
 * it on a laptop, and both screens say so when they write it.
 *
 * Absent sections are left alone rather than reset, the rule [RoutingCredentials.apply] already
 * holds to: a file carrying only a key must not blank a self-hosted base URL, and a file written
 * by a car still on the specification-sheet defaults must not overwrite a measured pack.
 *
 * Read by hand rather than by reflection: the release build is minified, and a data class
 * mapped by field name is a data class one shrinker rule away from decoding to nulls. Reading
 * field by field also puts every value through the validation the rest of the app relies on —
 * [RoutingConfig.validBaseUrl], [RoutingConfig.place], [VehicleSettings.sanitized] — so a
 * hand-edited file is a trust boundary and not a way to route the car off the planet.
 */
object SettingsTransfer {

    /** One fixed name, overwritten: dated exports mean an import picking the oldest one. */
    const val FILE_NAME = "evchargepilot-settings.json"

    /** What this file is, so a wrong pick is refused rather than half-read. */
    const val FORMAT = "evchargepilot-settings"

    const val VERSION = 1

    private const val NOTE =
        "EVChargePilot settings, API keys in clear text included: this USB stick is now a secret."

    /**
     * @param routing keys, base URLs and the saved destinations, which travel with them.
     * @param vehicle the car's figures, or null when the file carried none.
     */
    data class Settings(
        val routing: RoutingConfig = RoutingConfig(),
        val vehicle: VehicleSettings.Values? = null,
    ) {
        /** Nothing usable in it — an empty file, or a file that was never a settings file. */
        fun isEmpty(): Boolean = routing.isEmpty() && vehicle == null
    }

    /**
     * What this car is configured with.
     *
     * The vehicle figures only when they are the driver's own: exporting the defaults would
     * write the specification sheet over a second car whose pack has been measured.
     */
    fun snapshot(context: Context): Settings = Settings(
        routing = RoutingCredentials.snapshot(context)
            .copy(favorites = DestinationFavorites.all(context)),
        vehicle = VehicleSettings.read(context).takeIf { !it.isDefault },
    )

    /**
     * Applies [settings] to this car.
     *
     * @return how many of the file's destinations the list actually took, which is not how many
     * it carried: a list already at its cap refuses the rest.
     */
    fun apply(context: Context, settings: Settings): Int {
        RoutingCredentials.apply(context, settings.routing)
        settings.vehicle?.let { VehicleSettings.write(context, it) }
        return settings.routing.favorites.count { DestinationFavorites.save(context, it) }
    }

    /**
     * The settings in the file the driver browsed to, or empty ones when it holds none.
     *
     * Size is checked before reading, so a wrong pick — a film sitting on the stick — is never
     * slurped in. A file that is not JSON is still tried as the `key = value` format earlier
     * versions exported, because the sticks carrying those are in gloveboxes already.
     */
    fun read(file: File): Settings {
        if (!file.isFile || !file.canRead()) return Settings()
        if (file.length() <= 0 || file.length() > RoutingConfig.MAX_FILE_BYTES) return Settings()
        val text = runCatching {
            file.inputStream().use { stream ->
                val buffer = ByteArray(RoutingConfig.MAX_FILE_BYTES)
                var filled = 0
                while (filled < buffer.size) {
                    val read = stream.read(buffer, filled, buffer.size - filled)
                    if (read == -1) break
                    filled += read
                }
                String(buffer, 0, filled, StandardCharsets.UTF_8)
            }
        }.getOrNull() ?: return Settings()
        return if (text.trimStart().startsWith("{")) decode(text)
        else Settings(routing = RoutingConfig.parse(text))
    }

    /**
     * Writes [settings] into [directory], replacing an earlier export. Temp file then rename, so
     * a stick pulled mid-write leaves either the old settings or the new ones, never half a key.
     */
    fun write(directory: File, settings: Settings): File? {
        if (!directory.isDirectory || settings.isEmpty()) return null
        val target = File(directory, FILE_NAME)
        val temp = File(directory, ".$FILE_NAME.${System.nanoTime()}.tmp")
        return try {
            FileOutputStream(temp).use { output ->
                output.write(encode(settings).toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            // FAT has no atomic replace: the old file goes before the new one takes its name.
            target.delete()
            if (temp.renameTo(target)) target else null
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    /** Pretty-printed, because the driver may well open it on a laptop to check a value. */
    fun encode(settings: Settings): String {
        val root = JsonObject()
        root.addProperty("format", FORMAT)
        root.addProperty("version", VERSION)
        root.addProperty("note", NOTE)
        val routing = JsonObject()
        settings.routing.apiKey?.let { routing.addProperty(ORS_API_KEY, it) }
        settings.routing.baseUrl?.let { routing.addProperty(ORS_BASE_URL, it) }
        settings.routing.chargerApiKey?.let { routing.addProperty(OCM_API_KEY, it) }
        settings.routing.chargerBaseUrl?.let { routing.addProperty(OCM_BASE_URL, it) }
        // Absent, not empty: importing this back must not blank what it never carried.
        if (routing.size() > 0) root.add("routing", routing)
        settings.vehicle?.let { values ->
            val vehicle = JsonObject()
            vehicle.addProperty(CAPACITY, values.usableCapacityKwhWhenNew)
            vehicle.addProperty(HEALTH, values.stateOfHealthPercent)
            vehicle.addProperty(MIN_POWER, values.minChargerPowerKw)
            vehicle.addProperty(RESERVE, values.reservePercent)
            root.add("vehicle", vehicle)
        }
        if (settings.routing.favorites.isNotEmpty()) {
            val destinations = JsonArray()
            settings.routing.favorites.forEach { place ->
                val entry = JsonObject()
                entry.addProperty("label", place.label)
                entry.addProperty("longitude", place.longitude)
                entry.addProperty("latitude", place.latitude)
                destinations.add(entry)
            }
            root.add("destinations", destinations)
        }
        // No HTML escaping: a base64 key ends in `=`, and a driver opening this on a laptop
        // should read their key, not `\u003d`.
        return GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root) +
            "\n"
    }

    /** Empty settings when [text] is not this format, rather than a partly-applied car. */
    fun decode(text: String): Settings {
        val root = runCatching { JsonParser.parseString(text) }.getOrNull()?.asJsonObjectOrNull()
            ?: return Settings()
        if (root.string("format") != FORMAT) return Settings()
        val routing = root.get("routing")?.asJsonObjectOrNull()
        val vehicle = root.get("vehicle")?.asJsonObjectOrNull()
        return Settings(
            routing = RoutingConfig(
                apiKey = routing?.string(ORS_API_KEY),
                baseUrl = routing?.string(ORS_BASE_URL)?.let { RoutingConfig.validBaseUrl(it) },
                chargerApiKey = routing?.string(OCM_API_KEY),
                chargerBaseUrl = routing?.string(OCM_BASE_URL)
                    ?.let { RoutingConfig.validBaseUrl(it) },
                favorites = destinations(root.get("destinations")),
            ),
            vehicle = vehicle?.let {
                VehicleSettings.sanitized(
                    VehicleSettings.Values(
                        usableCapacityKwhWhenNew = it.number(CAPACITY)
                            ?: VehicleSettings.DEFAULT_CAPACITY_KWH,
                        stateOfHealthPercent = it.number(HEALTH)
                            ?: VehicleSettings.DEFAULT_HEALTH_PERCENT,
                        minChargerPowerKw = it.number(MIN_POWER)
                            ?: VehicleSettings.DEFAULT_MIN_POWER_KW,
                        reservePercent = it.number(RESERVE)
                            ?: VehicleSettings.DEFAULT_RESERVE_PERCENT,
                    )
                )
            },
        )
    }

    /** The cap the list holds to, applied to the file so a huge one is truncated, not refused. */
    private fun destinations(element: JsonElement?): List<OrsGeocode.Place> {
        val array = element as? JsonArray ?: return emptyList()
        return array.mapNotNull { entry ->
            val place = entry.asJsonObjectOrNull() ?: return@mapNotNull null
            val label = place.string("label")?.trim() ?: return@mapNotNull null
            val longitude = place.number("longitude") ?: return@mapNotNull null
            val latitude = place.number("latitude") ?: return@mapNotNull null
            RoutingConfig.place(label, longitude, latitude)
        }.take(RoutingConfig.MAX_DESTINATIONS)
    }

    private fun JsonElement.asJsonObjectOrNull(): JsonObject? = this as? JsonObject

    private fun JsonObject.string(name: String): String? =
        runCatching { get(name)?.asString }.getOrNull()?.trim()?.ifEmpty { null }

    private fun JsonObject.number(name: String): Double? =
        runCatching { get(name)?.asDouble }.getOrNull()?.takeIf { it.isFinite() }

    private const val ORS_API_KEY = "ors_api_key"
    private const val ORS_BASE_URL = "ors_base_url"
    private const val OCM_API_KEY = "ocm_api_key"
    private const val OCM_BASE_URL = "ocm_base_url"
    private const val CAPACITY = "usable_capacity_kwh_when_new"
    private const val HEALTH = "state_of_health_percent"
    private const val MIN_POWER = "min_charger_power_kw"
    private const val RESERVE = "reserve_percent"
}
