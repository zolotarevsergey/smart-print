package uk.mwms1488.print

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException

/** Помилка, яку можна показати людині як є. */
class PrintException(message: String) : Exception(message)

/**
 * Клієнт служби SportikPrint на Windows-ПК (див. windows/README.md):
 * `GET /api/status`, `POST /api/print?copies=&title=&media=&fit=` з файлом у тілі.
 */
class PrintClient(server: String, private val token: String) {

    val baseUrl: String = normalize(server)

    data class Status(
        val printer: String,
        val ready: Boolean,
        val state: String,
        val queuedJobs: Int,
        val machine: String,
    )

    data class Result(val pages: Int, val printer: String)

    fun status(): Status {
        val c = open("/api/status", connectMs = 4000, readMs = 15000)
        try {
            val json = readJson(c)
            return Status(
                printer = json.optString("printer"),
                ready = json.optBoolean("ready"),
                state = json.optString("state"),
                queuedJobs = json.optInt("queuedJobs"),
                machine = json.optString("machine"),
            )
        } finally {
            c.disconnect()
        }
    }

    /**
     * Надсилає файл і чекає, поки ПК віддасть його драйверу (відповідь приходить після спулінгу).
     * [length] < 0 — невідомо, тоді шлемо частинами.
     */
    fun print(
        input: InputStream,
        length: Long,
        contentType: String?,
        title: String,
        copies: Int,
        media: String? = null,
        fit: Boolean = false,
    ): Result {
        val query = buildString {
            append("/api/print?copies=").append(copies.coerceIn(1, 99))
            append("&title=").append(URLEncoder.encode(title.take(120), "UTF-8"))
            if (!media.isNullOrEmpty()) append("&media=").append(URLEncoder.encode(media, "UTF-8"))
            if (fit) append("&fit=1")
        }
        val c = open(query, connectMs = 6000, readMs = 180_000)
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", contentType ?: "application/octet-stream")
            if (length >= 0) c.setFixedLengthStreamingMode(length) else c.setChunkedStreamingMode(64 * 1024)
            try {
                c.outputStream.use { out -> input.copyTo(out, 64 * 1024) }
            } catch (e: IOException) {
                throw humanize(e)
            }
            val json = readJson(c)
            return Result(json.optInt("pages"), json.optString("printer"))
        } finally {
            c.disconnect()
        }
    }

    private fun open(path: String, connectMs: Int, readMs: Int): HttpURLConnection {
        val c = URL(baseUrl + path).openConnection() as HttpURLConnection
        c.connectTimeout = connectMs
        c.readTimeout = readMs
        c.useCaches = false
        if (token.isNotEmpty()) c.setRequestProperty("X-Print-Token", token)
        return c
    }

    private fun readJson(c: HttpURLConnection): JSONObject {
        val code = try {
            c.responseCode
        } catch (e: IOException) {
            throw humanize(e)
        }
        val body = (if (code >= 400) c.errorStream else c.inputStream)
            ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        val json = try {
            JSONObject(body)
        } catch (_: Exception) {
            throw PrintException("За адресою $baseUrl відповідає не служба друку (HTTP $code)")
        }
        if (code >= 400 || !json.optBoolean("ok", false)) {
            throw PrintException(json.optString("error").ifEmpty { "Помилка служби друку (HTTP $code)" })
        }
        return json
    }

    private fun humanize(e: IOException): PrintException = when (e) {
        is ConnectException, is NoRouteToHostException, is UnknownHostException ->
            PrintException("Немає зв'язку з ПК ($baseUrl). Перевірте, що телефон у тій самій Wi-Fi мережі, а ПК увімкнений і на ньому працює служба SportikPrint.")
        is SocketTimeoutException ->
            PrintException("ПК не відповів вчасно ($baseUrl). Можливо, брандмауер блокує порт або ПК заснув.")
        else -> PrintException("Мережева помилка: ${e.message ?: e.javaClass.simpleName}")
    }

    companion object {
        const val DEFAULT_PORT = 8631

        fun normalize(server: String): String {
            var s = server.trim().trimEnd('/')
            if (s.isEmpty()) s = BuildConfig.DEFAULT_SERVER
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
            val hostPart = s.substringAfter("://")
            if (!hostPart.contains(':')) s = "$s:$DEFAULT_PORT"
            return s
        }

        fun fromPrefs(ctx: Context) = PrintClient(Prefs.server(ctx), Prefs.token(ctx))
    }
}

/** Дозвіл «Локальна мережа» (Android 17+). Без нього підключення до ПК висить до тайм-ауту. */
object LocalNetwork {
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    const val HINT = "Немає дозволу на локальну мережу: відкрийте Smart print і дозвольте доступ до пристроїв поблизу / локальної мережі"

    fun needsRequest(ctx: Context): Boolean =
        android.os.Build.VERSION.SDK_INT >= 37 &&
            ctx.checkSelfPermission(PERMISSION) != android.content.pm.PackageManager.PERMISSION_GRANTED
}

object Prefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("print", Context.MODE_PRIVATE)

    fun server(ctx: Context): String = sp(ctx).getString("server", null) ?: BuildConfig.DEFAULT_SERVER
    fun token(ctx: Context): String = sp(ctx).getString("token", null).orEmpty()

    fun save(ctx: Context, server: String, token: String) {
        sp(ctx).edit().putString("server", server.trim()).putString("token", token.trim()).apply()
    }
}
