package uk.mwms1488.print

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
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
 * Принтери в системному діалозі друку Android: «Canon MF3010 (sportik)» через службу на Windows-ПК
 * і принтери, під'єднані до телефона USB-кабелем (див. [UsbPrinter]). Система сама робить із
 * документа PDF потрібного формату; ми лише пересилаємо його на ПК або просто в USB.
 */
class SportikPrintService : PrintService() {

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool()

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession = Session()

    private inner class Session : PrinterDiscoverySession() {
        private val printerId: PrinterId = generatePrinterId("sportik")
        @Volatile private var alive = true
        private var usbShown: Set<PrinterId> = emptySet()
        private var usbReceiver: BroadcastReceiver? = null

        override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) {
            // одразу показуємо принтер, стан уточнюємо запитом до ПК
            addPrinters(listOf(info(PrinterInfo.STATUS_IDLE, "Перевіряю зв'язок…")))
            refresh()
            refreshUsb()
            watchUsb()
        }

        override fun onStartPrinterStateTracking(printerId: PrinterId) {
            if (!printerId.localId.startsWith("usb:")) {
                refresh()
                return
            }
            // вибрали USB-принтер: якщо дозволу ще нема — Android питає саме зараз
            val target = UsbPrinter.find(this@SportikPrintService).firstOrNull { it.id == printerId.localId }
            if (target != null && target.supported && !UsbPrinter.hasPermission(this@SportikPrintService, target.device)) {
                UsbPrinter.requestPermission(this@SportikPrintService, target.device) { if (alive && !isDestroyed) refreshUsb() }
            }
            refreshUsb()
        }

        override fun onStopPrinterDiscovery() = unwatchUsb()
        override fun onValidatePrinters(printerIds: MutableList<PrinterId>) {}
        override fun onStopPrinterStateTracking(printerId: PrinterId) {}
        override fun onDestroy() {
            alive = false
            unwatchUsb()
        }

        /** Кабель під'єднали або висмикнули, поки відкритий діалог друку. */
        private fun watchUsb() {
            if (usbReceiver != null) return
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    // пристрій з'являється у списку трохи пізніше за саму подію
                    main.postDelayed({ if (alive && !isDestroyed) refreshUsb() }, 500)
                }
            }
            val f = IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED).apply { addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_EXPORTED)
            else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(r, f)
            usbReceiver = r
        }

        private fun unwatchUsb() {
            usbReceiver?.let { runCatching { unregisterReceiver(it) } }
            usbReceiver = null
        }

        private fun refreshUsb() {
            val infos = UsbPrinter.find(this@SportikPrintService).map { usbInfo(it) }
            val ids = infos.map { it.id }.toSet()
            val gone = usbShown - ids
            if (gone.isNotEmpty()) removePrinters(gone.toList())
            if (infos.isNotEmpty()) addPrinters(infos)
            usbShown = ids
        }

        private fun usbInfo(t: UsbPrinter.Target): PrinterInfo {
            val id = generatePrinterId(t.id)
            val granted = UsbPrinter.hasPermission(this@SportikPrintService, t.device)
            val caps = PrinterCapabilitiesInfo.Builder(id)
                .addMediaSize(PrintAttributes.MediaSize.ISO_A4, true)
                .addMediaSize(PrintAttributes.MediaSize.ISO_A5, false)
                .addResolution(PrintAttributes.Resolution("600", "600×600 dpi", 600, 600), true)
                .setColorModes(PrintAttributes.COLOR_MODE_MONOCHROME, PrintAttributes.COLOR_MODE_MONOCHROME)
                .setDuplexModes(
                    PrintAttributes.DUPLEX_MODE_NONE or PrintAttributes.DUPLEX_MODE_LONG_EDGE or PrintAttributes.DUPLEX_MODE_SHORT_EDGE,
                    PrintAttributes.DUPLEX_MODE_NONE,
                )
                .setMinMargins(PrintAttributes.Margins(170, 170, 170, 170))
                .build()
            val description = when {
                !t.supported -> "USB: принтер приймає лише IPP — не підтримується"
                granted -> "USB-кабель · готовий"
                else -> "USB-кабель · виберіть і дозвольте доступ"
            }
            return PrinterInfo.Builder(id, t.name + " · USB", if (t.supported) PrinterInfo.STATUS_IDLE else PrinterInfo.STATUS_UNAVAILABLE)
                .setDescription(description)
                .setCapabilities(caps)
                .build()
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
        if (job.info.printerId?.localId?.startsWith("usb:") == true) {
            printUsb(job)
            return
        }
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

    private fun printUsb(job: PrintJob) {
        val info = job.info
        val target = UsbPrinter.find(this).firstOrNull { it.id == info.printerId?.localId }
        if (target == null) {
            job.fail("USB-принтер від'єднано. Під'єднайте кабель і надрукуйте ще раз.")
            return
        }
        val data: ParcelFileDescriptor? = job.document.data
        if (data == null) {
            job.fail("Система не передала документ")
            return
        }
        val opts = UsbPrinter.Options(
            title = job.document.info.name.ifBlank { "Android" },
            copies = info.copies.coerceAtLeast(1),
            media = info.attributes.mediaSize?.id,
            duplex = when (info.attributes.duplexMode) {
                PrintAttributes.DUPLEX_MODE_LONG_EDGE -> 1
                PrintAttributes.DUPLEX_MODE_SHORT_EDGE -> 2
                else -> 0
            },
        )
        UsbPrinter.requestPermission(this, target.device) { granted ->
            if (!granted) {
                runCatching { data.close() }
                if (!job.isCancelled) job.fail("Немає дозволу на USB-принтер")
                return@requestPermission
            }
            io.execute {
                val error: String? = try {
                    ParcelFileDescriptor.AutoCloseInputStream(data).use { UsbPrinter.send(this, target, it, opts) }
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
