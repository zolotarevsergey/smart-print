package uk.mwms1488.print

import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import java.util.concurrent.Executors

/**
 * Принтер «Canon MF3010 (sportik)» у системному діалозі друку Android. Система сама робить із
 * документа PDF потрібного формату; ми лише пересилаємо його службі на Windows-ПК.
 */
class SportikPrintService : PrintService() {

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool()

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession = Session()

    private inner class Session : PrinterDiscoverySession() {
        private val printerId: PrinterId = generatePrinterId("sportik")
        @Volatile private var alive = true

        override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) {
            // одразу показуємо принтер, стан уточнюємо запитом до ПК
            addPrinters(listOf(info(PrinterInfo.STATUS_IDLE, "Перевіряю зв'язок…")))
            refresh()
        }

        override fun onStartPrinterStateTracking(printerId: PrinterId) = refresh()
        override fun onStopPrinterDiscovery() {}
        override fun onValidatePrinters(printerIds: MutableList<PrinterId>) {}
        override fun onStopPrinterStateTracking(printerId: PrinterId) {}
        override fun onDestroy() {
            alive = false
        }

        private fun refresh() {
            if (LocalNetwork.needsRequest(this@SportikPrintService)) {
                addPrinters(listOf(info(PrinterInfo.STATUS_UNAVAILABLE, LocalNetwork.HINT)))
                return
            }
            val client = PrintClient.fromPrefs(this@SportikPrintService)
            io.execute {
                val printer = try {
                    val st = client.status()
                    val text = buildString {
                        append(st.state.ifEmpty { "Готовий" })
                        if (st.machine.isNotEmpty()) append(" · ПК ").append(st.machine)
                        if (st.queuedJobs > 0) append(" · у черзі ").append(st.queuedJobs)
                    }
                    // «Немає паперу» тощо — не блокуємо: завдання ляже в чергу Windows і піде, коли поправлять
                    info(if (st.ready) PrinterInfo.STATUS_IDLE else PrinterInfo.STATUS_BUSY, text)
                } catch (e: Exception) {
                    info(PrinterInfo.STATUS_UNAVAILABLE, e.message ?: "Немає зв'язку з ПК")
                }
                main.post { if (alive && !isDestroyed) addPrinters(listOf(printer)) }
            }
        }

        private fun info(status: Int, description: String): PrinterInfo {
            val caps = PrinterCapabilitiesInfo.Builder(printerId)
                .addMediaSize(PrintAttributes.MediaSize.ISO_A4, true)
                .addMediaSize(PrintAttributes.MediaSize.ISO_A5, false)
                // Letter не даємо: з англійською мовою телефону Android ставить його замість A4
                .addResolution(PrintAttributes.Resolution("600", "600×600 dpi", 600, 600), true)
                .setColorModes(PrintAttributes.COLOR_MODE_MONOCHROME, PrintAttributes.COLOR_MODE_MONOCHROME)
                // жорсткі поля MF3010 ≈ 5 мм (у тисячних дюйма)
                .setMinMargins(PrintAttributes.Margins(200, 200, 200, 200))
                .build()
            return PrinterInfo.Builder(printerId, getString(R.string.printer_name), status)
                .setDescription(description)
                .setCapabilities(caps)
                .build()
        }
    }

    override fun onPrintJobQueued(job: PrintJob) {
        if (!job.isQueued) return
        job.start()
        if (LocalNetwork.needsRequest(this)) {
            job.fail(LocalNetwork.HINT)
            return
        }
        val data: ParcelFileDescriptor? = job.document.data
        if (data == null) {
            job.fail("Система не передала документ")
            return
        }
        val info = job.info
        val copies = info.copies.coerceAtLeast(1)
        val media = info.attributes.mediaSize?.id
        val title = job.document.info.name.ifBlank { "Android" }
        val client = PrintClient.fromPrefs(this)

        io.execute {
            val error: String? = try {
                ParcelFileDescriptor.AutoCloseInputStream(data).use { input ->
                    client.print(input, -1, "application/pdf", title, copies, media)
                }
                null
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            main.post {
                if (job.isCancelled || job.isCompleted || job.isFailed) return@post
                if (error == null) job.complete() else job.fail(error)
            }
        }
    }

    override fun onRequestCancelPrintJob(job: PrintJob) {
        // те, що вже пішло на ПК, скасовується лише в черзі Windows
        job.cancel()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }
}
