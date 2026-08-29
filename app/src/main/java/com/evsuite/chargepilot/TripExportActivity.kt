package com.evsuite.chargepilot

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.evsuite.chargepilot.databinding.ActivityTripExportBinding
import com.evsuite.hardware.telemetry.EnergyTripHistoryStore
import java.io.File
import java.util.concurrent.Executors

/** User-mediated CSV/JSON export for one completed trip or the whole local ledger. */
class TripExportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTripExportBinding
    private lateinit var store: EnergyTripHistoryStore
    private lateinit var exporter: TripExporter
    private val disk = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "chargepilot-trip-export")
    }
    private var singleStartedAtMs: Long? = null
    private var exported: TripExporter.ExportedFile? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTripExportBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = EnergyTripHistoryStore(File(filesDir, HISTORY_FILE))
        exporter = TripExporter(File(filesDir, EXPORT_DIRECTORY))
        singleStartedAtMs = intent.takeIf { it.hasExtra(EXTRA_STARTED_AT) }
            ?.getLongExtra(EXTRA_STARTED_AT, INVALID_ID)
            ?.takeIf { it != INVALID_ID }

        val single = singleStartedAtMs != null
        binding.exportTitle.setText(
            if (single) R.string.trip_export_one_title else R.string.trip_export_all_title
        )
        binding.exportMessage.setText(
            if (single) R.string.trip_export_one_message else R.string.trip_export_all_message
        )
        binding.backAction.setOnClickListener { finish() }
        binding.exportCsvAction.setOnClickListener { export(TripExporter.Format.CSV) }
        binding.exportJsonAction.setOnClickListener { export(TripExporter.Format.JSON) }
        binding.shareAction.setOnClickListener { share() }
        renderReady()
    }

    override fun onDestroy() {
        disk.shutdownNow()
        super.onDestroy()
    }

    private fun export(format: TripExporter.Format) {
        setBusy(true)
        binding.exportStatus.setText(R.string.trip_export_working)
        binding.exportPath.text = ""
        binding.shareAction.isEnabled = false
        disk.execute {
            val allTrips = store.read()
            val selected = singleStartedAtMs?.let { startedAt ->
                allTrips.firstOrNull { it.summary.startedAtMs == startedAt }
                    ?.let(::listOf)
                    .orEmpty()
            } ?: allTrips
            val result = exporter.export(selected, format, singleStartedAtMs != null)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                setBusy(false)
                result.fold(
                    onSuccess = { value ->
                        exported = value
                        binding.exportStatus.text = resources.getQuantityString(
                            R.plurals.trip_export_success,
                            value.tripCount,
                            value.tripCount,
                        )
                        binding.exportPath.text = value.file.absolutePath
                        binding.shareAction.isEnabled = true
                    },
                    onFailure = {
                        exported = null
                        binding.exportStatus.setText(R.string.trip_export_failed)
                        binding.exportPath.text = ""
                        binding.shareAction.isEnabled = false
                    },
                )
            }
        }
    }

    private fun share() {
        val value = exported ?: return
        val uri = FileProvider.getUriForFile(this, "$packageName.files", value.file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = value.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(contentResolver, value.file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.trip_export_share_title)))
    }

    private fun renderReady() {
        setBusy(false)
        binding.exportStatus.setText(R.string.trip_export_ready)
        binding.exportPath.text = ""
        binding.shareAction.isEnabled = false
    }

    private fun setBusy(busy: Boolean) {
        binding.exportCsvAction.isEnabled = !busy
        binding.exportJsonAction.isEnabled = !busy
        binding.backAction.isEnabled = !busy
    }

    companion object {
        private const val EXTRA_STARTED_AT = "started_at"
        private const val INVALID_ID = Long.MIN_VALUE
        private const val HISTORY_FILE = "trips.json"
        private const val EXPORT_DIRECTORY = "exports"

        fun single(context: Context, startedAtMs: Long) =
            Intent(context, TripExportActivity::class.java)
                .putExtra(EXTRA_STARTED_AT, startedAtMs)

        fun all(context: Context) = Intent(context, TripExportActivity::class.java)
    }
}
