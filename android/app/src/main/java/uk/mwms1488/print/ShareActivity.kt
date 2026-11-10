package uk.mwms1488.print

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/** «Поділитися» → «Друк на sportik» (і «Відкрити за допомогою» для PDF). */
class ShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // поворот екрана активність не перестворює (configChanges у маніфесті) — діалог живе

        val items = collect(intent)
        if (items.isEmpty()) {
            Toast.makeText(this, "Нема чого друкувати", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        PrintFlow.confirmAndPrint(this, items) { finish() }
    }

    private fun collect(intent: Intent): List<PrintItem> {
        val type = intent.type
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = streamExtra(intent)
                when {
                    uri != null -> listOf(PrintItem.of(this, uri, type))
                    !intent.getStringExtra(Intent.EXTRA_TEXT).isNullOrBlank() ->
                        listOf(PrintItem.Text(intent.getStringExtra(Intent.EXTRA_TEXT)!!))
                    else -> emptyList()
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> streamListExtra(intent).map { PrintItem.of(this, it, type) }
            Intent.ACTION_VIEW -> intent.data?.let { listOf(PrintItem.of(this, it, type)) }.orEmpty()
            else -> emptyList()
        }
    }

    private fun streamExtra(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)

    private fun streamListExtra(intent: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()
}
