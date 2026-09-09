package com.evsuite.chargepilot

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.evsuite.chargepilot.databinding.ActivityDestinationBinding
import com.evsuite.chargepilot.route.DestinationFavorites
import com.evsuite.chargepilot.route.LocationSource
import com.evsuite.chargepilot.route.OrsGeocode
import com.evsuite.chargepilot.route.RoutingCredentials
import com.evsuite.chargepilot.route.RoutingTransport
import com.google.android.material.button.MaterialButton
import java.io.File
import java.util.concurrent.Executors

/**
 * Where the driver says where they are going.
 *
 * It was a text field and two short lists stacked under the charging plan, on a panel 1920 dp
 * wide and about 648 dp tall: the results landed below the fold, the favourites below those, and
 * choosing a destination meant scrolling a screen whose top half was a plan that did not exist
 * yet. A destination is a step, not a footnote, so it gets the panel — searched on the left,
 * saved on the right, each column scrolling on its own.
 *
 * It plans nothing and asks for no position. It answers one question, hands the place back to
 * [ChargeStopActivity] and closes, which is also why it needs no vehicle binding: nothing here
 * depends on the car being parked, only on someone typing.
 */
class DestinationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDestinationBinding

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "chargepilot-destination")
    }

    private val geocode = RoutingTransport(OrsGeocode.quota())

    private var places: List<OrsGeocode.Place> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDestinationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.backAction.setOnClickListener { finish() }
        binding.searchAction.setOnClickListener { search() }
        binding.favoritesImportAction.setOnClickListener { importConfig() }
        binding.favoritesExportAction.setOnClickListener { exportConfig() }
        showFavorites()
        announce(getString(R.string.destination_prompt))
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun search() {
        val text = binding.destinationInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            announce(getString(R.string.charge_stop_destination_missing))
            return
        }
        val credentials = RoutingCredentials.read(this)
        if (credentials == null) {
            announce(getString(R.string.charge_stop_not_configured))
            return
        }
        // A fix if one is already held, never a request for one: this screen turns words into
        // coordinates, and the position only sorts the answers by how near they are.
        val near = LocationSource.lastKnown(this)
        announce(getString(R.string.charge_stop_searching))
        worker.execute {
            val result = geocode.get(credentials, OrsGeocode.PATH, OrsGeocode.query(text, near))
            val found = (result as? RoutingTransport.Result.Ok)?.let { OrsGeocode.parse(it.body) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                places = found.orEmpty()
                showPlaces()
                announce(
                    when {
                        result is RoutingTransport.Result.Refused -> refusal(result)
                        places.isEmpty() -> getString(R.string.charge_stop_no_results)
                        else -> getString(R.string.charge_stop_choose)
                    }
                )
            }
        }
    }

    /** One button per answer: a head unit list the driver reads once and taps once. */
    private fun showPlaces() {
        binding.destinationResults.removeAllViews()
        places.forEach { place ->
            binding.destinationResults.addView(
                row(binding.destinationResults, place) { saveFavorite(place) }
            )
        }
    }

    /** One button per saved place; a long-press removes it rather than choosing it by mistake. */
    private fun showFavorites() {
        val favorites = DestinationFavorites.all(this)
        binding.destinationFavoritesHint.setText(
            if (favorites.isEmpty()) R.string.charge_stop_favorites_empty
            else R.string.charge_stop_favorites_hint
        )
        binding.destinationFavorites.removeAllViews()
        favorites.forEach { place ->
            binding.destinationFavorites.addView(
                row(binding.destinationFavorites, place) {
                    DestinationFavorites.remove(this, place.label)
                    showFavorites()
                    announce(getString(R.string.charge_stop_favorites_removed))
                }
            )
        }
    }

    private fun row(
        parent: android.view.ViewGroup,
        place: OrsGeocode.Place,
        onLongPress: () -> Unit,
    ): MaterialButton =
        (layoutInflater.inflate(R.layout.row_destination_result, parent, false) as MaterialButton)
            .apply {
                text = place.label
                setOnClickListener { choose(place) }
                setOnLongClickListener { onLongPress(); true }
            }

    /** The one thing this screen answers: the place goes back to the caller and it closes. */
    private fun choose(place: OrsGeocode.Place) {
        setResult(
            Activity.RESULT_OK,
            Intent()
                .putExtra(EXTRA_LABEL, place.label)
                .putExtra(EXTRA_LONGITUDE, place.longitude)
                .putExtra(EXTRA_LATITUDE, place.latitude),
        )
        finish()
    }

    private fun saveFavorite(place: OrsGeocode.Place) {
        val saved = DestinationFavorites.save(this, place)
        announce(
            if (saved) getString(R.string.charge_stop_favorites_saved)
            else getString(
                R.string.charge_stop_favorites_save_failed, DestinationFavorites.MAX_FAVORITES
            )
        )
        showFavorites()
    }

    /**
     * Browsed, not listed, same as [RoutingSettingsActivity]'s import: this head unit's system
     * picker answers "no apps can perform this action".
     *
     * One file, the same one the routing key screen writes: a driver setting up a second car
     * plugs in one stick and browses to one file. Two files meant two chances to carry only half
     * of what makes the app usable.
     */
    private fun importConfig() {
        worker.execute {
            val roots = DiagnosticUsbStorage.roots(this)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (roots.isEmpty()) {
                    announce(getString(R.string.charge_stop_favorites_import_none))
                } else {
                    StorageBrowserDialog.pickFile(
                        this, roots, R.string.charge_stop_favorites_import_pick
                    ) { importFile(it) }
                }
            }
        }
    }

    private fun importFile(file: File) {
        worker.execute {
            val settings = SettingsTransfer.read(file)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (settings.isEmpty()) {
                    // The path they chose, so "this one is not it" is the answer, not silence.
                    announce(getString(R.string.charge_stop_favorites_import_unusable, file.name))
                    return@runOnUiThread
                }
                // The keys and the car's figures in the same file are applied too, so importing
                // on this screen configures the car exactly as the routing key screen does.
                // The count is what was stored, not what was read: a list already at its cap
                // refuses the rest, and counting those would report a favourite that is not there.
                val saved = SettingsTransfer.apply(this, settings)
                val carried = settings.routing.favorites.size
                showFavorites()
                announce(
                    when {
                        carried == 0 ->
                            getString(R.string.charge_stop_favorites_import_keys, file.name)
                        saved == carried ->
                            getString(R.string.charge_stop_favorites_import_done, saved, file.name)
                        else -> getString(
                            R.string.charge_stop_favorites_import_partial,
                            saved, carried, DestinationFavorites.MAX_FAVORITES,
                        )
                    }
                )
            }
        }
    }

    /**
     * The keys, the destinations and the car's own figures, in one file, on a removable volume
     * only.
     *
     * The file carries the keys in clear text — that is what a file this app can import back has
     * to be — so the announcement says so on this screen as well as on the routing key screen.
     */
    private fun exportConfig() {
        val settings = SettingsTransfer.snapshot(this)
        if (settings.isEmpty()) {
            announce(getString(R.string.charge_stop_favorites_export_empty))
            return
        }
        worker.execute {
            val roots = DiagnosticUsbStorage.roots(this)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (roots.isEmpty()) {
                    announce(getString(R.string.charge_stop_favorites_export_failed))
                } else {
                    StorageBrowserDialog.pickFolder(
                        this, roots, R.string.charge_stop_favorites_export_pick
                    ) { writeExport(it, settings) }
                }
            }
        }
    }

    private fun writeExport(directory: File, settings: SettingsTransfer.Settings) {
        worker.execute {
            val written = DiagnosticUsbStorage.writableTarget(this, directory)
                ?.let { SettingsTransfer.write(it, settings) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (written == null) {
                    announce(getString(R.string.charge_stop_favorites_export_failed))
                } else {
                    // The file name, never its contents: the contents are the keys.
                    announce(getString(R.string.charge_stop_favorites_export_done, written.name))
                }
            }
        }
    }

    /**
     * A refusal the driver can act on. The detail travels only where it means something to
     * them — seconds to wait, a status code — never the transport's own English reason string.
     */
    private fun refusal(result: RoutingTransport.Result.Refused): String = when (result.reason) {
        RoutingTransport.Reason.NOT_CONFIGURED -> getString(R.string.charge_stop_not_configured)
        RoutingTransport.Reason.BUSY -> getString(R.string.routing_refused_busy)
        RoutingTransport.Reason.QUOTA_MINUTE ->
            getString(R.string.routing_refused_quota_minute, result.detail.orEmpty())
        RoutingTransport.Reason.QUOTA_DAY -> getString(R.string.routing_refused_quota_day)
        RoutingTransport.Reason.TRANSPORT -> getString(R.string.routing_refused_transport)
        RoutingTransport.Reason.SERVER_DAILY_LIMIT -> getString(R.string.routing_refused_server_day)
        RoutingTransport.Reason.SERVER_RATE_LIMIT ->
            getString(R.string.routing_refused_server_minute)
        RoutingTransport.Reason.SERVER_REJECTED ->
            getString(R.string.routing_refused_server, result.detail.orEmpty())
        RoutingTransport.Reason.UNREADABLE -> getString(R.string.routing_refused_unreadable)
    }

    private fun announce(text: String) {
        binding.destinationStatus.text = text
    }

    companion object {

        private const val EXTRA_LABEL = "label"
        private const val EXTRA_LONGITUDE = "longitude"
        private const val EXTRA_LATITUDE = "latitude"

        /** The chosen place out of a result, or null when the driver came back without one. */
        fun place(data: Intent?): OrsGeocode.Place? {
            val label = data?.getStringExtra(EXTRA_LABEL) ?: return null
            val longitude = data.getDoubleExtra(EXTRA_LONGITUDE, Double.NaN)
            val latitude = data.getDoubleExtra(EXTRA_LATITUDE, Double.NaN)
            if (!longitude.isFinite() || !latitude.isFinite()) return null
            return OrsGeocode.Place(label, longitude, latitude)
        }

        fun intent(context: Context): Intent = Intent(context, DestinationActivity::class.java)
    }
}
