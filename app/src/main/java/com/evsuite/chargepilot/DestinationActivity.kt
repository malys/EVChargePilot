package com.evsuite.chargepilot

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
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
 * Typing is the expensive part on a head unit, so the screen answers before the driver has
 * finished: on every pause in typing, Pelias's own type-ahead endpoint fills the left column
 * with places, the saved list on the right narrows to what matches, and the keyboard's search
 * key finishes the job. Nothing is queued — one request per pause, never one per keystroke.
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

    /** The text a request was last sent for, so a cursor move does not spend a second one. */
    private var lastQueried: String? = null

    private val handler = Handler(Looper.getMainLooper())

    /** What the saved column is currently drawing, so a keystroke does not re-inflate it. */
    private var shownFavorites: List<OrsGeocode.Place>? = null

    /** The debounced suggestion: one instance, so posting it again replaces the pending one. */
    private val suggest = Runnable { lookUp(OrsGeocode.AUTOCOMPLETE_PATH, quiet = true) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDestinationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.backAction.setOnClickListener { finish() }
        binding.searchAction.setOnClickListener { search() }
        // The keyboard already says "search"; until now the key did nothing.
        binding.destinationInput.setOnEditorActionListener { _, _, _ -> search(); true }
        binding.destinationInput.doAfterTextChanged { editable ->
            val text = editable?.toString().orEmpty()
            showFavorites(text)
            handler.removeCallbacks(suggest)
            // An emptied box shows nothing: answers to a destination that has been deleted are
            // the one thing a driver cannot have meant to tap. lastQueried goes with it — a
            // driver who clears the box and retypes the same word is asking again, not
            // repeating themselves, and a stale lastQueried would otherwise refuse to re-fire.
            if (text.isBlank()) {
                lastQueried = null
                if (places.isNotEmpty()) {
                    places = emptyList()
                    showPlaces()
                }
            }
            if (OrsGeocode.shouldSuggest(text, lastQueried)) {
                handler.postDelayed(suggest, OrsGeocode.SUGGEST_DEBOUNCE_MS)
            }
        }
        showFavorites()
        announce(getString(R.string.destination_prompt))
    }

    override fun onDestroy() {
        handler.removeCallbacks(suggest)
        worker.shutdownNow()
        super.onDestroy()
    }

    /** The button and the keyboard's search key: the full endpoint, on a finished address. */
    private fun search() {
        handler.removeCallbacks(suggest)
        if (typed().isEmpty()) {
            announce(getString(R.string.charge_stop_destination_missing))
            return
        }
        lookUp(OrsGeocode.PATH, quiet = false)
    }

    /**
     * @param quiet a suggestion the driver did not ask for out loud: it says nothing while it
     *   waits and nothing when a half-typed prefix matches nothing. A refusal is announced
     *   either way — quota and transport failures are the driver's to see, not this screen's
     *   to swallow.
     */
    private fun lookUp(path: String, quiet: Boolean) {
        val text = typed()
        if (text.isEmpty()) return
        // Recorded before any refusal too: an unanswered attempt is still an attempt, and
        // without this a quiet suggestion with no key configured re-announces "not
        // configured" on every single pause for as long as the driver keeps typing.
        lastQueried = text
        val credentials = RoutingCredentials.read(this)
        if (credentials == null) {
            announce(getString(R.string.charge_stop_not_configured))
            return
        }
        // A fix if one is already held, never a request for one: this screen turns words into
        // coordinates, and the position only sorts the answers by how near they are.
        val near = LocationSource.lastKnown(this)
        if (!quiet) announce(getString(R.string.charge_stop_searching))
        worker.execute {
            val result = geocode.get(credentials, path, OrsGeocode.query(text, near))
            val found = (result as? RoutingTransport.Result.Ok)?.let { OrsGeocode.parse(it.body) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                // A slow answer to an older prefix must not replace the answer to a newer one.
                if (typed() != text) return@runOnUiThread
                places = found.orEmpty()
                showPlaces()
                when {
                    result is RoutingTransport.Result.Refused -> announce(routingRefusal(result))
                    places.isEmpty() && !quiet ->
                        announce(getString(R.string.charge_stop_no_results))
                    places.isNotEmpty() -> announce(getString(R.string.charge_stop_choose))
                    // Quiet and empty: a suggestion that matched nothing says nothing new, but
                    // an earlier announcement (e.g. "Choose a place below" from a wider prefix)
                    // must not keep describing a panel that is empty now.
                    else -> announce(getString(R.string.destination_prompt))
                }
            }
        }
    }

    private fun typed(): String = binding.destinationInput.text?.toString()?.trim().orEmpty()

    /** One button per answer: a head unit list the driver reads once and taps once. */
    private fun showPlaces() {
        binding.destinationResults.removeAllViews()
        places.forEach { place ->
            binding.destinationResults.addView(
                row(binding.destinationResults, place) { saveFavorite(place) }
            )
        }
    }

    /**
     * One button per saved place; a long-press removes it rather than choosing it by mistake.
     *
     * @param filter what is in the box. A saved destination that matches what is being typed is
     *   the cheapest answer on this screen — no request, no wait — so the list narrows to it
     *   instead of staying a wall the driver has to read past.
     */
    private fun showFavorites(filter: String = "") {
        val saved = DestinationFavorites.all(this)
        val favorites = OrsGeocode.matching(saved, filter)
        // Cheap, and not covered by the memo guard below: an empty-vs-no-match hint depends
        // on `saved` too, which can change (a save, a remove) while the filtered list itself
        // stays the same empty list.
        binding.destinationFavoritesHint.setText(
            when {
                saved.isEmpty() -> R.string.charge_stop_favorites_empty
                favorites.isEmpty() -> R.string.charge_stop_favorites_no_match
                else -> R.string.charge_stop_favorites_hint
            }
        )
        // The list holds up to a hundred: a letter that changes nothing must not re-inflate
        // them all on an MT2712, once per keystroke.
        if (favorites == shownFavorites) return
        shownFavorites = favorites
        binding.destinationFavorites.removeAllViews()
        favorites.forEach { place ->
            binding.destinationFavorites.addView(
                row(binding.destinationFavorites, place) {
                    DestinationFavorites.remove(this, place.label)
                    showFavorites(typed())
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
        showFavorites(typed())
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
