package uk.mwms1488.print

import android.app.Activity
import android.app.AlertDialog
import android.net.Uri
import android.provider.OpenableColumns
import android.util.TypedValue
import android.view.Gravity
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ProgressBar
import android.widget.TextView
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors

/** Що друкуємо: файл з іншого застосунку або просто текст («Поділитися» текстом). */
sealed class PrintItem {
    abstract val name: String
    abstract val mime: String?

    data class File(val uri: Uri, override val name: String, override val mime: String?, val size: Long) : PrintItem()
    data class Text(val text: String) : PrintItem() {
        override val name = "Текст"
        override val mime = "text/plain; charset=utf-8"
    }

    companion object {
        fun of(activity: Activity, uri: Uri, fallbackMime: String? = null): File {
            var name = uri.lastPathSegment ?: "файл"
            var size = -1L
            runCatching {
                activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                    ?.use { c ->
                        if (c.moveToFirst()) {
                            c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !c.isNull(it) }
                                ?.let { name = c.getString(it) }
                            c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }
                                ?.let { size = c.getLong(it) }
                        }
                    }
            }
            val mime = activity.contentResolver.getType(uri) ?: fallbackMime
            if (name.all { it.isDigit() }) {
                // content://…/file/32 без DISPLAY_NAME — показуємо щось людське
                name = when {
                    mime == "application/pdf" -> "Документ.pdf"
                    mime?.startsWith("image/") == true -> "Фото"
                    else -> "Файл"
                }
            }
            return File(uri, name, mime, size)
        }
    }
}

/** Діалог «скільки копій» → відправка по черзі → підсумок. Спільний для головного екрана й «Поділитися». */
object PrintFlow {
    private val io = Executors.newSingleThreadExecutor()

    fun confirmAndPrint(activity: Activity, items: List<PrintItem>, onFinished: () -> Unit) {
        if (LocalNetwork.needsRequest(activity)) {
            AlertDialog.Builder(activity)
                .setTitle("Немає доступу до мережі")
                .setMessage(LocalNetwork.HINT)
                .setPositiveButton("Відкрити Smart print") { _, _ ->
                    activity.startActivity(android.content.Intent(activity, MainActivity::class.java))
                    onFinished()
                }
                .setNegativeButton("Скасувати") { _, _ -> onFinished() }
                .setOnCancelListener { onFinished() }
                .show()
            return
        }
        val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), activity.resources.displayMetrics).toInt() }

        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        box.addView(TextView(activity).apply {
            text = items.joinToString("\n") { "• " + it.name }
            maxLines = 8
        })
        val copiesRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        copiesRow.addView(TextView(activity).apply {
            text = "Копій:"
            setPadding(0, 0, dp(16), 0)
        })
        val picker = NumberPicker(activity).apply {
            minValue = 1
            maxValue = 50
            value = 1
            wrapSelectorWheel = false
        }
        copiesRow.addView(picker)
        box.addView(copiesRow)

        val hasPdf = items.any { it.mime == "application/pdf" || it.name.endsWith(".pdf", ignoreCase = true) }
        val fit = CheckBox(activity).apply {
            text = "PDF: вписати в аркуш (інакше — у справжньому розмірі)"
            visibility = if (hasPdf) android.view.View.VISIBLE else android.view.View.GONE
        }
        box.addView(fit)

        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.printer_name))
            .setView(box)
            .setPositiveButton("Друкувати") { _, _ -> send(activity, items, picker.value, fit.isChecked, onFinished) }
            .setNegativeButton("Скасувати") { _, _ -> onFinished() }
            .setOnCancelListener { onFinished() }
            .show()
    }

    private fun send(activity: Activity, items: List<PrintItem>, copies: Int, fit: Boolean, onFinished: () -> Unit) {
        val label = TextView(activity).apply { text = "З'єднуюсь з ПК…" }
        val progress = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            addView(ProgressBar(activity).apply { isIndeterminate = true })
            addView(label.apply { setPadding(pad / 2, 0, 0, 0) })
        }
        val wait = AlertDialog.Builder(activity).setView(progress).setCancelable(false).show()

        val client = PrintClient.fromPrefs(activity)
        io.execute {
            val lines = mutableListOf<String>()
            var failed = 0
            items.forEachIndexed { i, item ->
                activity.runOnUiThread {
                    label.text = if (items.size == 1) "Надсилаю «${item.name}»…" else "Надсилаю ${i + 1} з ${items.size}: «${item.name}»…"
                }
                try {
                    val r = when (item) {
                        is PrintItem.File -> activity.contentResolver.openInputStream(item.uri)?.use {
                            client.print(it, item.size, item.mime, item.name, copies, fit = fit)
                        } ?: throw PrintException("Не вдалося відкрити файл")
                        is PrintItem.Text -> {
                            val bytes = item.text.toByteArray(Charsets.UTF_8)
                            client.print(ByteArrayInputStream(bytes), bytes.size.toLong(), item.mime, item.name, copies)
                        }
                    }
                    lines += "✓ ${item.name} — ${pagesText(r.pages)}" + if (copies > 1) " × $copies" else ""
                } catch (e: PrintException) {
                    failed++
                    lines += "✗ ${item.name}: ${e.message}"
                } catch (e: Exception) {
                    failed++
                    lines += "✗ ${item.name}: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                wait.dismiss()
                AlertDialog.Builder(activity)
                    .setTitle(if (failed == 0) "Надіслано на друк" else if (failed == items.size) "Не надруковано" else "Надруковано частково")
                    .setMessage(lines.joinToString("\n"))
                    .setPositiveButton("OK") { _, _ -> onFinished() }
                    .setOnCancelListener { onFinished() }
                    .show()
            }
        }
    }

    fun pagesText(n: Int): String {
        val m10 = n % 10
        val m100 = n % 100
        val word = when {
            m10 == 1 && m100 != 11 -> "сторінка"
            m10 in 2..4 && m100 !in 12..14 -> "сторінки"
            else -> "сторінок"
        }
        return "$n $word"
    }
}
