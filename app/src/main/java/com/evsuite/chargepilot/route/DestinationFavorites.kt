package com.evsuite.chargepilot.route

import android.content.Context
import java.util.Locale

/**
 * Destinations the driver has saved, so a place searched once can be routed to again without
 * retyping it on this head unit's keyboard.
 *
 * There is no MG4 Navigator list to sync with: CP-056's drive on SWI68 found no activity on
 * this head unit declaring a navigation intent, and EVTasker's own [com.evsuite.tasker.store]
 * package records the same finding — the SAIC map app publishes no provider and no intent for
 * its favourites, so there is nothing to read or write. This is EVChargePilot's own list, the
 * same shape EVTasker keeps one of, made portable by [DestinationFavoritesExport] and
 * [DestinationFavoritesImport] instead.
 *
 * Plain preferences, unlike [RoutingCredentials]: a saved address is not a secret the way an
 * API key is. Stored as `label` -> "longitude,latitude", the coordinate order [OrsGeocode.Place]
 * already returns, so nothing here re-encodes what the geocoder handed back.
 */
object DestinationFavorites {

    private const val PREFS = "chargepilot_destination_favorites"

    /** Enough for a driver's own list; an imported file cannot grow it past this. */
    const val MAX_FAVORITES = 100

    /** Every saved place, alphabetical so the list on screen does not reorder itself. */
    fun all(context: Context): List<OrsGeocode.Place> =
        prefs(context).all
            .mapNotNull { (label, value) -> (value as? String)?.let { parse(label, it) } }
            .sortedBy { it.label.lowercase(Locale.US) }

    /**
     * Saves [place] under its own label, replacing an earlier favourite of that label.
     *
     * @return false when the label is blank, or the list is already full and this label is new.
     */
    fun save(context: Context, place: OrsGeocode.Place): Boolean {
        val label = place.label.trim()
        if (label.isEmpty()) return false
        val prefs = prefs(context)
        if (!prefs.contains(label) && prefs.all.size >= MAX_FAVORITES) return false
        prefs.edit().putString(label, format(place)).apply()
        return true
    }

    fun remove(context: Context, label: String) {
        prefs(context).edit().remove(label).apply()
    }

    fun isSaved(context: Context, label: String): Boolean = prefs(context).contains(label)

    private fun format(place: OrsGeocode.Place) = "${place.longitude},${place.latitude}"

    private fun parse(label: String, value: String): OrsGeocode.Place? {
        val separator = value.indexOf(',')
        if (separator <= 0) return null
        val longitude = value.substring(0, separator).trim().toDoubleOrNull() ?: return null
        val latitude = value.substring(separator + 1).trim().toDoubleOrNull() ?: return null
        return OrsGeocode.Place(label, longitude, latitude)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
