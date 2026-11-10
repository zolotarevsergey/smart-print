package uk.mwms1488.print

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var server: EditText
    private lateinit var token: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // targetSdk 35+: застосунок малюється під системними панелями — відсуваємо вміст
        if (Build.VERSION.SDK_INT >= 30) {
            findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                WindowInsets.CONSUMED
            }
        }

        status = findViewById(R.id.status)
        server = findViewById(R.id.server)
        token = findViewById(R.id.token)
        server.setText(Prefs.server(this))
        token.setText(Prefs.token(this))
        findViewById<TextView>(R.id.version).text = "Версія ${BuildConfig.VERSION_NAME}"

        findViewById<Button>(R.id.check).setOnClickListener {
            if (LocalNetwork.needsRequest(this)) {
                if (shouldShowRequestPermissionRationale(LocalNetwork.PERMISSION) || !askedThisSession) {
                    askedThisSession = true
                    requestPermissions(arrayOf(LocalNetwork.PERMISSION), REQ_LOCAL_NETWORK)
                } else {
                    // відмовили «назавжди» — система більше не питає, лише через налаштування
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null)))
                }
            } else {
                check()
            }
        }
        findViewById<Button>(R.id.save).setOnClickListener {
            Prefs.save(this, server.text.toString(), token.text.toString())
            Toast.makeText(this, "Збережено", Toast.LENGTH_SHORT).show()
            check()
        }
        findViewById<Button>(R.id.pick).setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/pdf", "image/*", "text/plain"))
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            startActivityForResult(i, REQ_PICK)
        }
        findViewById<Button>(R.id.printSettings).setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_PRINT_SETTINGS)) }
                .onFailure { Toast.makeText(this, "Налаштування друку не відкриваються на цьому телефоні", Toast.LENGTH_LONG).show() }
        }
    }

    override fun onResume() {
        super.onResume()
        if (LocalNetwork.needsRequest(this) && !askedThisSession) {
            askedThisSession = true
            requestPermissions(arrayOf(LocalNetwork.PERMISSION), REQ_LOCAL_NETWORK)
            return // check() — після відповіді
        }
        check()
    }

    private var askedThisSession = false

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCAL_NETWORK) check()
    }

    private fun check() {
        if (LocalNetwork.needsRequest(this)) {
            status.text = "✗ Немає дозволу на локальну мережу — без нього телефон не бачить ПК.\nНатисніть «Перевірити зв'язок», щоб дозволити, або дайте дозвіл у налаштуваннях застосунку (Дозволи → Пристрої поблизу)."
            return
        }
        status.text = "Перевіряю…"
        val client = PrintClient(server.text.toString().ifBlank { Prefs.server(this) }, token.text.toString())
        io.execute {
            val text = try {
                val st = client.status()
                buildString {
                    append(if (st.ready) "✓ " else "⚠ ").append(st.state)
                    append("\nПринтер у Windows: ").append(st.printer)
                    if (st.machine.isNotEmpty()) append("\nПК: ").append(st.machine)
                    if (st.queuedJobs > 0) append("\nУ черзі завдань: ").append(st.queuedJobs)
                }
            } catch (e: Exception) {
                "✗ " + (e.message ?: e.javaClass.simpleName)
            }
            runOnUiThread { if (!isDestroyed) status.text = text }
        }
    }

    @Deprecated("Activity без AndroidX — класичний onActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return
        val uris = buildList {
            data.clipData?.let { clip -> for (i in 0 until clip.itemCount) add(clip.getItemAt(i).uri) }
            if (isEmpty()) data.data?.let { add(it) }
        }
        if (uris.isEmpty()) return
        PrintFlow.confirmAndPrint(this, uris.map { PrintItem.of(this, it) }) { check() }
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val REQ_PICK = 1
        const val REQ_LOCAL_NETWORK = 2
    }
}
