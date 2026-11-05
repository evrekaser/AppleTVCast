package com.example.appletvcast

import android.app.AlertDialog
import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONArray
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val io = Executors.newSingleThreadExecutor()
    private val bridge: PyObject by lazy {
        if (!Python.isStarted()) Python.start(AndroidPlatform(this))
        Python.getInstance().getModule("airplay_bridge")
    }
    private lateinit var status: TextView
    private lateinit var list: ListView
    private lateinit var urlInput: EditText
    private var devices = listOf<Pair<String, String>>() // id to name
    private var lock: WifiManager.MulticastLock? = null

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { sendFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // mDNS (Bonjour) taraması için gerekli
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        lock = wifi.createMulticastLock("appletvcast").apply { setReferenceCounted(true); acquire() }

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

        status = TextView(this).apply { text = "Hazır. Önce cihazları tarayın." }
        list = ListView(this)
        urlInput = EditText(this).apply { hint = "Video URL'si (.mp4 / .m3u8)" }

        fun btn(label: String, action: () -> Unit) = Button(this).apply { text = label; setOnClickListener { action() } }

        root.addView(btn("Apple TV'leri tara") { scan() })
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(status)
        root.addView(btn("Dosya seç ve gönder (video/ses)") { picker.launch(arrayOf("video/*", "audio/*")) })
        root.addView(urlInput)
        root.addView(btn("URL'yi gönder") { sendUrl(urlInput.text.toString()) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((label, act) in listOf("Oynat" to "play", "Duraklat" to "pause", "Durdur" to "stop")) {
            row.addView(btn(label) { bg { bridge.callAttr("control", act) } }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(row)
        setContentView(root)

        list.setOnItemClickListener { _, _, pos, _ -> devices.getOrNull(pos)?.let { select(it.first, it.second) } }
    }

    override fun onDestroy() {
        lock?.release()
        super.onDestroy()
    }

    private fun ui(msg: String) = runOnUiThread { status.text = msg }

    private fun bg(block: () -> Unit) = io.execute {
        try { block() } catch (e: Exception) { ui("Hata: ${e.message}") }
    }

    private fun scan() = bg {
        ui("Taranıyor…")
        val arr = JSONArray(bridge.callAttr("scan").toString())
        devices = (0 until arr.length()).map { arr.getJSONObject(it).let { o -> o.getString("id") to o.getString("name") } }
        runOnUiThread {
            list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, devices.map { it.second })
        }
        ui("${devices.size} cihaz bulundu. Birine dokunun.")
    }

    private fun select(id: String, name: String) = bg {
        val prefs = getSharedPreferences("creds", MODE_PRIVATE)
        var creds = prefs.getString(id, null)
        if (creds == null) {
            ui("Eşleştiriliyor… TV ekranındaki PIN'e bakın")
            bridge.callAttr("pair_begin", id)
            val pin = askPin() ?: return@bg
            creds = bridge.callAttr("pair_finish", pin).toString()
            if (creds.isEmpty()) { ui("Eşleştirme başarısız"); return@bg }
            prefs.edit().putString(id, creds).apply()
        }
        bridge.callAttr("connect", id, creds)
        ui("Bağlandı: $name")
    }

    private fun askPin(): String? {
        val latch = CountDownLatch(1)
        var result: String? = null
        runOnUiThread {
            val et = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER }
            AlertDialog.Builder(this).setTitle("TV'deki PIN").setView(et)
                .setPositiveButton("Tamam") { _, _ -> result = et.text.toString(); latch.countDown() }
                .setNegativeButton("İptal") { _, _ -> latch.countDown() }
                .setOnCancelListener { latch.countDown() }
                .show()
        }
        latch.await()
        return result
    }

    private fun sendFile(uri: Uri) = bg {
        ui("Dosya hazırlanıyor…")
        val name = contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && i >= 0) c.getString(i) else null
        } ?: "media.mp4"
        val dir = File(cacheDir, "media").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val out = File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        contentResolver.openInputStream(uri)!!.use { i -> out.outputStream().use { i.copyTo(it) } }
        bridge.callAttr("play_file", out.name, dir.absolutePath)
        ui("Gönderildi: ${out.name}")
    }

    private fun sendUrl(url: String) = bg {
        if (url.isBlank()) { ui("URL girin"); return@bg }
        bridge.callAttr("play_url", url.trim())
        ui("URL gönderildi")
    }
}
