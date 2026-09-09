package com.evsuite.chargepilot

import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.accessibility.AccessibilityManager
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButtonToggleGroup
import kotlin.math.abs

/** Shared top-level navigation: visible tabs remain the fallback for every swipe. */
abstract class PrimaryNavigationActivity : AppCompatActivity() {
    protected abstract val primaryPage: PrimaryPage

    private var navigation: MaterialButtonToggleGroup? = null
    private var downX = 0f
    private var downY = 0f
    private var trackingSwipe = false
    private var navigationStarted = false

    private val touchExplorationEnabled: Boolean
        get() = (getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager)
            .isTouchExplorationEnabled

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        navigation = findViewById<MaterialButtonToggleGroup>(R.id.primaryNavigation)?.also { tabs ->
            tabs.check(buttonId(primaryPage))
            tabs.addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (isChecked) pageForButton(checkedId)?.let(::navigateTo)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        navigationStarted = false
        navigation?.check(buttonId(primaryPage))
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val handled = super.dispatchTouchEvent(event)
        if (!touchExplorationEnabled) trackSwipe(event)
        return handled
    }

    private fun trackSwipe(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                trackingSwipe = true
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> trackingSwipe = false
            MotionEvent.ACTION_UP -> {
                if (!trackingSwipe) return
                trackingSwipe = false
                val deltaX = event.x - downX
                val deltaY = event.y - downY
                val threshold = resources.getDimension(R.dimen.chargepilot_swipe_threshold)
                if (abs(deltaX) < threshold || abs(deltaX) <= abs(deltaY) * 1.5f) return
                primaryPage.neighbour(if (deltaX < 0f) 1 else -1)?.let(::navigateTo)
            }
        }
    }

    private fun navigateTo(target: PrimaryPage) {
        if (target == primaryPage || navigationStarted) return
        navigationStarted = true
        val forward = target.ordinal > primaryPage.ordinal
        startActivity(
            Intent(this, activityClass(target)).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        )
        overridePendingTransition(
            if (forward) R.anim.primary_page_in_from_right else R.anim.primary_page_in_from_left,
            if (forward) R.anim.primary_page_out_to_left else R.anim.primary_page_out_to_right,
        )
    }

    private fun pageForButton(buttonId: Int): PrimaryPage? = when (buttonId) {
        R.id.navEnergy -> PrimaryPage.ENERGY
        R.id.navArrival -> PrimaryPage.ARRIVAL
        R.id.navTrips -> PrimaryPage.TRIPS
        R.id.navDiagnostics -> PrimaryPage.DIAGNOSTICS
        R.id.navSettings -> PrimaryPage.SETTINGS
        else -> null
    }

    private fun buttonId(page: PrimaryPage): Int = when (page) {
        PrimaryPage.ENERGY -> R.id.navEnergy
        PrimaryPage.ARRIVAL -> R.id.navArrival
        PrimaryPage.TRIPS -> R.id.navTrips
        PrimaryPage.DIAGNOSTICS -> R.id.navDiagnostics
        PrimaryPage.SETTINGS -> R.id.navSettings
    }

    private fun activityClass(page: PrimaryPage): Class<out AppCompatActivity> = when (page) {
        PrimaryPage.ENERGY -> MainActivity::class.java
        PrimaryPage.ARRIVAL -> ArrivalForecastActivity::class.java
        PrimaryPage.TRIPS -> TripHistoryActivity::class.java
        PrimaryPage.DIAGNOSTICS -> DiagnosticsActivity::class.java
        PrimaryPage.SETTINGS -> SettingsActivity::class.java
    }
}
