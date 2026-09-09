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
 * depends on the car being parked, only on someone typing. Favourites travel on the USB stick
 * with every other setting, and that transfer is [SettingsActivity]'s: one file, one door.
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
        setResult(Activity.RESULT_OK, carry(Intent(), place))
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

        /**
         * The chosen place on an intent: this screen's own result, and the plan screen's input
         * when the dashboard opens it with a destination already chosen.
         */
        fun carry(intent: Intent, place: OrsGeocode.Place): Intent = intent
            .putExtra(EXTRA_LABEL, place.label)
            .putExtra(EXTRA_LONGITUDE, place.longitude)
            .putExtra(EXTRA_LATITUDE, place.latitude)

        fun intent(context: Context): Intent = Intent(context, DestinationActivity::class.java)
    }
}
