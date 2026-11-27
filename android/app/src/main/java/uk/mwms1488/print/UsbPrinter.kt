package uk.mwms1488.print

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.media.ExifInterface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.StaticLayout
import android.text.TextPaint
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Друк напряму по USB-кабелю (OTG) на принтер, під'єднаний до телефона — без ПК і мережі.
 *
 * Працює з принтерами, які самі розуміють PDF (HP LaserJet Pro 4003/4103 та подібні): PDF іде
 * «як є» у звичайний USB-інтерфейс принтера (клас 7), загорнутий у команди PJL (копії, папір,
 * двобічний друк). Canon MF3010 так не вміє — йому потрібен драйвер, тому він друкує через ПК.
 */
object UsbPrinter {
    private const val ACTION_PERMISSION = "uk.mwms1488.print.USB_PERMISSION"
    private const val CLASS_PRINTER = 7
    private const val PROTOCOL_IPP = 4 // IPP-over-USB: чекає HTTP, а не сирий потік
    private const val CHUNK = 16 * 1024
    private const val WRITE_TIMEOUT_MS = 20_000
    // принтер перестає приймати дані, поки друкує попередні сторінки або чекає паперу
    private const val STALL_LIMIT_MS = 180_000

    /** [out] == null — у принтера є лише IPP-USB, сирий PDF йому не надіслати. */
    class Target(val device: UsbDevice, val iface: UsbInterface?, val out: UsbEndpoint?) {
        val id: String = "usb:%04x:%04x".format(device.vendorId, device.productId)
        val supported: Boolean get() = iface != null && out != null
        val name: String
            get() {
                val product = device.productName?.trim().orEmpty()
                val maker = device.manufacturerName?.trim().orEmpty()
                return when {
                    product.isEmpty() && maker.isEmpty() -> "USB-принтер"
                    product.isEmpty() -> "$maker (USB)"
                    maker.isEmpty() || product.startsWith(maker.substringBefore(' '), ignoreCase = true) -> product
                    else -> "$maker $product"
                }
            }
    }

    data class Options(
        val title: String,
        val copies: Int = 1,
        /** Розмір паперу Android (ISO_A4, ISO_A5, NA_LETTER…); null — як налаштовано в принтері. */
        val media: String? = null,
        /** 0 — однобічний, 1 — двобічний по довгому краю, 2 — по короткому. */
        val duplex: Int = 0,
    )

    private fun manager(ctx: Context): UsbManager? = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager

    /** Усі під'єднані до телефона USB-принтери. */
    fun find(ctx: Context): List<Target> {
        val devices = try {
            manager(ctx)?.deviceList?.values.orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        return devices.mapNotNull { dev ->
            var best: Pair<UsbInterface, UsbEndpoint>? = null
            var printerClass = false
            for (i in 0 until dev.interfaceCount) {
                val itf = dev.getInterface(i)
                if (itf.interfaceClass != CLASS_PRINTER) continue
                printerClass = true
                if (itf.interfaceProtocol == PROTOCOL_IPP) continue
                val out = (0 until itf.endpointCount).map { itf.getEndpoint(it) }.firstOrNull {
                    it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_OUT
                } ?: continue
                // двонапрямний (2) кращий за однонапрямний (1): на ньому сидять сучасні прошивки
                if (best == null || itf.interfaceProtocol == 2) best = itf to out
            }
            when {
                best != null -> Target(dev, best.first, best.second)
                printerClass -> Target(dev, null, null)
                else -> null
            }
        }
    }

    fun hasPermission(ctx: Context, device: UsbDevice): Boolean = manager(ctx)?.hasPermission(device) == true

    /**
     * Системне вікно «Дозволити Smart print доступ до USB-пристрою?». [onResult] — у головному потоці.
     * Якщо застосунок відкрився сам при під'єднанні кабелю, дозвіл уже є і вікна не буде.
     */
    fun requestPermission(ctx: Context, device: UsbDevice, onResult: (Boolean) -> Unit) {
        val app = ctx.applicationContext
        val usb = manager(app)
        if (usb == null) {
            onResult(false)
            return
        }
        if (usb.hasPermission(device)) {
            onResult(true)
            return
        }
        val main = Handler(Looper.getMainLooper())
        var done = false
        lateinit var receiver: BroadcastReceiver
        fun finish(granted: Boolean) {
            if (done) return
            done = true
            runCatching { app.unregisterReceiver(receiver) }
            onResult(granted)
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                main.post { finish(usb.hasPermission(device)) }
            }
        }
        val filter = IntentFilter(ACTION_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") app.registerReceiver(receiver, filter)
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(app, 0, Intent(ACTION_PERMISSION).setPackage(app.packageName), flags)
        try {
            usb.requestPermission(device, pi)
        } catch (_: Exception) {
            finish(false)
        }
    }

    /** Надсилає PDF у принтер. Блокує потік, доки принтер не забере весь документ. */
    fun send(ctx: Context, target: Target, pdf: InputStream, opts: Options) {
        val iface = target.iface
        val out = target.out
        if (iface == null || out == null) {
            throw PrintException("«${target.name}» приймає по USB лише IPP — такий друк застосунок не вміє. Під'єднайте принтер до мережі (Ethernet/Wi-Fi), тоді Android побачить його сам.")
        }
        val usb = manager(ctx) ?: throw PrintException("На цьому телефоні немає USB-хоста (OTG)")
        if (!usb.hasPermission(target.device)) throw PrintException("Немає дозволу на USB-принтер. Від'єднайте й під'єднайте кабель і дозвольте доступ.")
        val conn = usb.openDevice(target.device)
            ?: throw PrintException("Не вдалося відкрити USB-принтер. Перевірте кабель і що принтер увімкнений.")
        try {
            if (!conn.claimInterface(iface, true)) throw PrintException("USB-принтер зайнятий іншим застосунком")
            if (iface.alternateSetting != 0) conn.setInterface(iface)

            val buf = ByteArray(CHUNK)
            fun write(data: ByteArray, length: Int) {
                var off = 0
                var stalledMs = 0
                while (off < length) {
                    val n = conn.bulkTransfer(out, data, off, minOf(CHUNK, length - off), WRITE_TIMEOUT_MS)
                    if (n > 0) {
                        off += n
                        stalledMs = 0
                    } else {
                        stalledMs += WRITE_TIMEOUT_MS
                        if (usb.deviceList.values.none { it.deviceId == target.device.deviceId }) {
                            throw PrintException("USB-кабель від'єднано під час друку")
                        }
                        if (stalledMs >= STALL_LIMIT_MS) {
                            throw PrintException("Принтер не приймає дані. Перевірте папір, кришку й повідомлення на екрані принтера.")
                        }
                    }
                }
            }

            val head = pjlHeader(opts)
            write(head, head.size)
            while (true) {
                val n = pdf.read(buf)
                if (n < 0) break
                if (n > 0) write(buf, n)
            }
            val tail = "$UEL@PJL EOJ\r\n$UEL".toByteArray(Charsets.US_ASCII)
            write(tail, tail.size)
        } finally {
            runCatching { conn.releaseInterface(iface) }
            conn.close()
        }
    }

    private const val UEL = "\u001B%-12345X"

    private fun pjlHeader(o: Options): ByteArray {
        val name = o.title.map { if (it in ' '..'~' && it != '"') it else '_' }.joinToString("").take(60).ifBlank { "Android" }
        val paper = when (o.media) {
            "ISO_A4" -> "A4"
            "ISO_A5" -> "A5"
            "ISO_A6" -> "A6"
            "ISO_B5" -> "B5"
            "NA_LETTER" -> "LETTER"
            "NA_LEGAL" -> "LEGAL"
            else -> null
        }
        return buildString {
            append(UEL).append("@PJL\r\n")
            append("@PJL JOB NAME=\"").append(name).append("\"\r\n")
            // QTY — копії всього документа (1,2,3,1,2,3), на відміну від COPIES (1,1,2,2,3,3)
            if (o.copies > 1) append("@PJL SET QTY=").append(o.copies.coerceAtMost(99)).append("\r\n")
            if (paper != null) append("@PJL SET PAPER=").append(paper).append("\r\n")
            if (o.duplex == 0) {
                append("@PJL SET DUPLEX=OFF\r\n")
            } else {
                append("@PJL SET DUPLEX=ON\r\n")
                append("@PJL SET BINDING=").append(if (o.duplex == 2) "SHORTEDGE" else "LONGEDGE").append("\r\n")
            }
            append("@PJL ENTER LANGUAGE=PDF\r\n")
        }.toByteArray(Charsets.US_ASCII)
    }
}

/** Фото й текст з «Поділитися» → PDF A4 (по USB принтер приймає лише PDF; через ПК це робить Windows). */
object UsbDocs {
    private const val A4_W = 595 // пункти, 1/72"
    private const val A4_H = 842
    private const val MARGIN = 20
    private const val MAX_SIDE = 3000 // ≈ 270 dpi на A4 — досить для лазерного ч/б

    class Doc(val stream: InputStream, val pages: Int?)

    fun toPdf(ctx: Context, item: PrintItem): Doc = when (item) {
        is PrintItem.Text -> text(item.text)
        is PrintItem.File -> {
            val input = BufferedInputStream(
                ctx.contentResolver.openInputStream(item.uri) ?: throw PrintException("Не вдалося відкрити файл"), 64 * 1024,
            )
            input.mark(8)
            val magic = ByteArray(4)
            val n = input.read(magic)
            input.reset()
            val isPdf = n == 4 && String(magic, Charsets.US_ASCII) == "%PDF"
            when {
                isPdf -> Doc(input, null)
                item.mime?.startsWith("text/") == true -> input.use { text(it.readBytes().toString(Charsets.UTF_8)) }
                else -> {
                    input.close()
                    image(ctx, item)
                }
            }
        }
    }

    private fun image(ctx: Context, item: PrintItem.File): Doc {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(item.uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) throw PrintException("По USB друкуються PDF, фото й текст — цей файл не схожий на жоден з них")
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE * 2) sample *= 2
        var bmp = cr.openInputStream(item.uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw PrintException("Не вдалося прочитати фото")

        val degrees = runCatching {
            cr.openInputStream(item.uri)?.use { s ->
                when (ExifInterface(s).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            }
        }.getOrNull() ?: 0f
        if (degrees != 0f) {
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees) }, true)
        }

        // альбомне фото — на альбомний аркуш
        val landscape = bmp.width > bmp.height
        val pw = if (landscape) A4_H else A4_W
        val ph = if (landscape) A4_W else A4_H
        val pdf = PdfDocument()
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(pw, ph, 1).create())
        val box = RectF(MARGIN.toFloat(), MARGIN.toFloat(), (pw - MARGIN).toFloat(), (ph - MARGIN).toFloat())
        val m = Matrix().apply {
            setRectToRect(RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat()), box, Matrix.ScaleToFit.CENTER)
        }
        page.canvas.drawBitmap(bmp, m, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        pdf.finishPage(page)
        return finish(pdf, 1)
    }

    private fun text(text: String): Doc {
        val paint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            textSize = 10f
            color = android.graphics.Color.BLACK
        }
        val margin = 42 // ≈ 15 мм
        val width = A4_W - 2 * margin
        val height = A4_H - 2 * margin
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width).build()

        val pdf = PdfDocument()
        var line = 0
        var pages = 0
        do {
            val top = if (line < layout.lineCount) layout.getLineTop(line) else 0
            var end = line
            while (end < layout.lineCount && layout.getLineBottom(end) - top <= height) end++
            if (end == line && line < layout.lineCount) end++ // рядок, вищий за сторінку
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(A4_W, A4_H, ++pages).create())
            val c = page.canvas
            c.translate(margin.toFloat(), (margin - top).toFloat())
            val bottom = if (end > 0 && end <= layout.lineCount && end > line) layout.getLineBottom(end - 1) else top
            c.clipRect(0, top, width, bottom)
            layout.draw(c)
            pdf.finishPage(page)
            line = end
        } while (line < layout.lineCount)
        return finish(pdf, pages)
    }

    private fun finish(pdf: PdfDocument, pages: Int): Doc {
        val out = ByteArrayOutputStream()
        pdf.writeTo(out)
        pdf.close()
        return Doc(ByteArrayInputStream(out.toByteArray()), pages)
    }
}
