package com.evsuite.chargepilot

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.evsuite.chargepilot.databinding.ActivityAdviceHistoryBinding
import com.evsuite.chargepilot.databinding.ItemAdviceRowBinding
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

/**
 * Every advice the app gave, newest first, word for word (CP-087).
 *
 * A notification is gone once dismissed; this is where it can be read again. Rows are the
 * [AdviceJournal] as stored — nothing is recomputed, so a line reads as it did when it was said.
 */
class AdviceHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdviceHistoryBinding
    private val disk = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "chargepilot-advice-history")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdviceHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.backAction.setOnClickListener { finish() }
        disk.execute {
            val entries = AdviceJournal.of(filesDir).read()
            runOnUiThread {
                if (!isFinishing && !isDestroyed) render(entries)
            }
        }
    }

    override fun onDestroy() {
        disk.shutdownNow()
        super.onDestroy()
    }

    private fun render(entries: List<AdviceEntry>) {
        binding.adviceRows.removeAllViews()
        binding.adviceEmpty.visibility = if (entries.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        val format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        entries.asReversed().forEach {
            val row = ItemAdviceRowBinding.inflate(layoutInflater, binding.adviceRows, true)
            row.rowDate.text = format.format(Date(it.atMs))
            row.rowText.text = it.text
        }
    }
}
