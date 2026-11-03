package com.lanshare.app

import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

data class Peer(val id: String, val name: String, val ip: String, val port: Int, val seen: Long)

data class FileInfo(val name: String, val size: Long)

class MainActivity : AppCompatActivity() {

    companion object {
        const val DISC_PORT = 50505
        const val TCP_PORT = 50506
        const val MAGIC = "LANSHARE1"
        const val CHUNK = 256 * 1024
        const val MAX_HEADER = 4 * 1024 * 1024 // لیست تعداد زیادی فایل هم جا شود
    }

    private val myId = UUID.randomUUID().toString().take(8)
    private val myName = (android.os.Build.MODEL ?: "Android").replace("|", "_")
    private val peers = ConcurrentHashMap<String, Peer>()
    private val uiHandler = Handler(Looper.getMainLooper())

    @Volatile private var running = true
    private var multicastLock: WifiManager.MulticastLock? = null
    private var serverSock: ServerSocket? = null
    private var discSock: DatagramSocket? = null

    private lateinit var statusView: TextView
    private lateinit var listView: ListView
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var ipInput: EditText
    private lateinit var progress: ProgressBar
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    private var shown = listOf<Peer>()
    private var selectedId: String? = null

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) sendFiles(uris)
    }

    // انتخاب از گالری (عکس و ویدیو) - بدون نیاز به مجوز
    private val galleryPicker = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) sendFiles(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        startDiscovery()
        startServer()
        refreshLoop()
    }

    // ---------------- UI ----------------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        statusView = TextView(this).apply { textSize = 15f; text = "نام این دستگاه: $myName\nIP: ${myIp()}" }
        root.addView(statusView)

        root.addView(TextView(this).apply {
            text = "دستگاه مقصد را انتخاب کنید (اپ باید روی آن باز باشد):"
            setPadding(0, dp(12), 0, dp(4))
        })

        listView = ListView(this).apply { choiceMode = ListView.CHOICE_MODE_SINGLE }
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_single_choice, mutableListOf<String>())
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, pos, _ ->
            if (pos < shown.size) selectedId = shown[pos].id
        }
        root.addView(listView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        ipInput = EditText(this).apply { hint = "یا IP دستی (مثلاً 192.168.1.20)" }
        root.addView(ipInput)

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val sendBtn = Button(this).apply {
            text = "ارسال فایل..."
            setOnClickListener { pickFiles() }
        }
        val galleryBtn = Button(this).apply {
            text = "گالری"
            setOnClickListener { pickGallery() }
        }
        btnRow.addView(sendBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        btnRow.addView(galleryBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(btnRow)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.addView(progress)

        logView = TextView(this).apply { textSize = 12f }
        logScroll = ScrollView(this).apply { addView(logView) }
        root.addView(logScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    private fun log(msg: String) = runOnUiThread {
        logView.append(msg + "\n")
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun setProgress(done: Long, total: Long) = runOnUiThread {
        progress.progress = if (total > 0) (done * 100 / total).toInt() else 100
    }

    private fun fmt(n: Long): String {
        var v = n.toDouble()
        val units = arrayOf("B", "KB", "MB", "GB")
        var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return if (i == 0) "${n} B" else String.format("%.1f %s", v, units[i])
    }

    private fun refreshLoop() {
        uiHandler.post(object : Runnable {
            override fun run() {
                if (!running) return
                val now = System.currentTimeMillis()
                peers.values.removeAll { now - it.seen > 8000 }
                val list = peers.values.sortedBy { it.name }
                if (list.map { it.id } != shown.map { it.id }) {
                    shown = list
                    adapter.clear()
                    adapter.addAll(list.map { "${it.name}  (${it.ip})" })
                    adapter.notifyDataSetChanged()
                    val idx = list.indexOfFirst { it.id == selectedId }
                    if (idx >= 0) listView.setItemChecked(idx, true)
                }
                uiHandler.postDelayed(this, 1000)
            }
        })
    }

    private fun myIp(): String {
        return try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ip = wifi.dhcpInfo.ipAddress
            "${ip and 0xff}.${(ip shr 8) and 0xff}.${(ip shr 16) and 0xff}.${(ip shr 24) and 0xff}"
        } catch (e: Exception) { "?" }
    }

    // ---------------- کشف دستگاه‌ها ----------------
    private fun startDiscovery() {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("lanshare").apply {
            setReferenceCounted(false)
            acquire()
        }

        thread(isDaemon = true) {
            try {
                val sock = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(DISC_PORT))
                }
                discSock = sock
                val buf = ByteArray(512)
                while (running) {
                    val p = DatagramPacket(buf, buf.size)
                    sock.receive(p)
                    val parts = String(p.data, 0, p.length).split("|")
                    if (parts.size == 4 && parts[0] == MAGIC && parts[1] != myId) {
                        val port = parts[3].toIntOrNull() ?: continue
                        val ip = p.address.hostAddress ?: continue
                        peers[parts[1]] = Peer(parts[1], parts[2], ip, port, System.currentTimeMillis())
                    }
                }
            } catch (e: Exception) {
                if (running) log("خطا در کشف دستگاه: ${e.message}")
            }
        }

        thread(isDaemon = true) {
            val sock = DatagramSocket().apply { broadcast = true }
            val msg = "$MAGIC|$myId|$myName|$TCP_PORT".toByteArray()
            while (running) {
                try {
                    val targets = mutableListOf(InetAddress.getByName("255.255.255.255"))
                    try {
                        val d = wifi.dhcpInfo
                        val b = (d.ipAddress and d.netmask) or d.netmask.inv()
                        targets.add(
                            InetAddress.getByAddress(
                                byteArrayOf(
                                    (b and 0xff).toByte(), ((b shr 8) and 0xff).toByte(),
                                    ((b shr 16) and 0xff).toByte(), ((b shr 24) and 0xff).toByte()
                                )
                            )
                        )
                    } catch (_: Exception) {}
                    for (t in targets) sock.send(DatagramPacket(msg, msg.size, t, DISC_PORT))
                } catch (_: Exception) {}
                try { Thread.sleep(2000) } catch (_: InterruptedException) {}
            }
            sock.close()
        }
    }

    // ---------------- دریافت ----------------
    private fun startServer() {
        thread(isDaemon = true) {
            try {
                val srv = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(TCP_PORT))
                }
                serverSock = srv
                while (running) {
                    val s = srv.accept()
                    thread(isDaemon = true) {
                        try { handleIncoming(s) } catch (e: Exception) { log("✗ خطا در دریافت: ${e.message}") }
                    }
                }
            } catch (e: Exception) {
                if (running) log("خطا در سرور دریافت: ${e.message}")
            }
        }
    }

    /** لیست فایل‌ها را با چک‌باکس نشان می‌دهد و ایندکس فایل‌های تیک‌خورده را برمی‌گرداند. */
    private fun askAccept(from: String, files: List<FileInfo>): List<Int> {
        val latch = CountDownLatch(1)
        var result: List<Int> = emptyList()
        var dialog: AlertDialog? = null
        val checked = BooleanArray(files.size) { true }
        val labels = files.map { "${it.name}  (${fmt(it.size)})" }.toTypedArray()
        val total = files.sumOf { it.size }

        runOnUiThread {
            val dlg = AlertDialog.Builder(this)
                .setTitle("$from — ${files.size} فایل (${fmt(total)})")
                .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
                .setPositiveButton("دریافت انتخاب‌شده‌ها") { _, _ ->
                    result = checked.indices.filter { checked[it] }
                    latch.countDown()
                }
                .setNegativeButton("رد همه") { _, _ -> latch.countDown() }
                .setNeutralButton("همه / هیچ", null)
                .setOnCancelListener { latch.countDown() }
                .create()
            dialog = dlg
            dlg.show()
            // دکمه «همه / هیچ» نباید دیالوگ را ببندد
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val newState = checked.any { !it }
                for (i in checked.indices) {
                    checked[i] = newState
                    dlg.listView.setItemChecked(i, newState)
                }
            }
        }
        if (!latch.await(100, TimeUnit.SECONDS)) {
            runOnUiThread { try { dialog?.dismiss() } catch (_: Exception) {} }
        }
        return result
    }

    private fun handleIncoming(s: Socket) {
        s.use {
            val inp = DataInputStream(BufferedInputStream(s.getInputStream(), CHUNK))
            val out = s.getOutputStream()
            s.soTimeout = 30000

            val len = inp.readInt()
            if (len !in 1..MAX_HEADER) return
            val hb = ByteArray(len)
            inp.readFully(hb)
            val j = JSONObject(String(hb, Charsets.UTF_8))
            val from = j.optString("from", "?")
            val arr = j.getJSONArray("files")
            if (arr.length() == 0) return
            val files = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val size = o.getLong("size")
                if (size < 0) throw IOException("اندازه فایل نامعتبر است")
                val name = File(o.getString("name")).name
                    .replace(Regex("[\\/:*?\"<>|]"), "_").ifBlank { "file" }
                FileInfo(name, size)
            }

            // کاربر در یک لیست تیک‌دار انتخاب می‌کند
            val accepted = askAccept(from, files)
            val reply = JSONObject().put("accept", JSONArray(accepted)).toString().toByteArray(Charsets.UTF_8)
            out.write(ByteBuffer.allocate(4 + reply.size).putInt(reply.size).put(reply).array())
            out.flush()

            if (accepted.isEmpty()) {
                log("✗ ${files.size} فایل از $from رد شد")
                return
            }
            if (accepted.size < files.size) log("${files.size - accepted.size} فایل توسط شما حذف شد")

            val totalBytes = accepted.sumOf { files[it].size }
            var doneBytes = 0L
            var count = 0

            for (idx in accepted) {
                val f = files[idx]
                count++
                log("($count/${accepted.size}) در حال دریافت «${f.name}» ...")

                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, f.name)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LanShare")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("امکان ساخت فایل نبود")
                try {
                    contentResolver.openOutputStream(uri)!!.use { os ->
                        val buf = ByteArray(CHUNK)
                        var got = 0L
                        while (got < f.size) {
                            val n = inp.read(buf, 0, minOf(buf.size.toLong(), f.size - got).toInt())
                            if (n < 0) throw IOException("اتصال قطع شد")
                            os.write(buf, 0, n)
                            got += n
                            doneBytes += n
                            setProgress(doneBytes, totalBytes)
                        }
                    }
                    val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                    contentResolver.update(uri, done, null, null)
                } catch (e: Exception) {
                    contentResolver.delete(uri, null, null)
                    throw e
                }
                log("✓ «${f.name}» دریافت شد ← Downloads/LanShare")
            }
            out.write(1); out.flush()
            log("✓ همه فایل‌های انتخابی از $from دریافت شد (${accepted.size} فایل)")
        }
    }

    // ---------------- ارسال ----------------
    private fun resolveTarget(): Pair<String, Int>? {
        val manual = ipInput.text.toString().trim()
        val target: Pair<String, Int>? = if (manual.isNotEmpty()) {
            Pair(manual, TCP_PORT)
        } else {
            selectedId?.let { id -> peers[id]?.let { Pair(it.ip, it.port) } }
        }
        if (target == null) {
            Toast.makeText(this, "ابتدا یک دستگاه انتخاب کنید یا IP را وارد کنید", Toast.LENGTH_LONG).show()
        }
        return target
    }

    private fun pickFiles() {
        if (resolveTarget() == null) return
        picker.launch(arrayOf("*/*"))
    }

    private fun pickGallery() {
        if (resolveTarget() == null) return
        galleryPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
    }

    private fun sendFiles(uris: List<Uri>) {
        val target = resolveTarget() ?: return
        thread(isDaemon = true) {
            try {
                sendBatch(target.first, target.second, uris)
            } catch (e: Exception) {
                log("✗ ارسال ناموفق: ${e.message}")
                setProgress(0, 1)
            }
        }
    }

    private fun queryInfo(uri: Uri): FileInfo {
        var name = "file"
        var size = -1L
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0) name = c.getString(ni) ?: name
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        if (size < 0) {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { size = it.length }
        }
        if (size < 0) throw IOException("اندازه فایل «$name» نامشخص است")
        return FileInfo(name, size)
    }

    /** همه فایل‌ها در یک اتصال: اول لیست، بعد فقط فایل‌هایی که گیرنده تیک زده. */
    private fun sendBatch(ip: String, port: Int, uris: List<Uri>) {
        val sendUris = ArrayList<Uri>()
        val infos = ArrayList<FileInfo>()
        for (u in uris) {
            try {
                infos.add(queryInfo(u)); sendUris.add(u)
            } catch (e: Exception) {
                log("✗ نادیده گرفته شد: ${e.message}")
            }
        }
        if (infos.isEmpty()) throw IOException("فایل قابل ارسالی نبود")

        log("در حال اتصال برای ارسال ${infos.size} فایل ...")
        Socket().use { s ->
            s.connect(InetSocketAddress(ip, port), 10000)
            val out = DataOutputStream(BufferedOutputStream(s.getOutputStream(), CHUNK))
            val inp = DataInputStream(s.getInputStream())

            val arr = JSONArray()
            infos.forEach { arr.put(JSONObject().put("name", it.name).put("size", it.size)) }
            val hdr = JSONObject().put("from", myName).put("files", arr)
                .toString().toByteArray(Charsets.UTF_8)
            out.writeInt(hdr.size)
            out.write(hdr)
            out.flush()

            log("منتظر انتخاب کاربر مقصد ...")
            s.soTimeout = 120000 // منتظر انتخاب کاربر مقصد
            val rl = inp.readInt()
            if (rl !in 1..MAX_HEADER) throw IOException("پاسخ نامعتبر از مقصد")
            val rb = ByteArray(rl)
            inp.readFully(rb)
            val ja = JSONObject(String(rb, Charsets.UTF_8)).getJSONArray("accept")
            val accepted = (0 until ja.length()).map { ja.getInt(it) }
                .filter { it in infos.indices }.distinct().sorted()
            if (accepted.isEmpty()) throw IOException("دستگاه مقصد همه فایل‌ها را رد کرد")
            s.soTimeout = 30000
            if (accepted.size < infos.size) log("مقصد ${accepted.size} فایل از ${infos.size} فایل را انتخاب کرد")

            val totalBytes = accepted.sumOf { infos[it].size }
            var sentTotal = 0L
            var count = 0
            for (idx in accepted) {
                val f = infos[idx]
                count++
                log("($count/${accepted.size}) در حال ارسال «${f.name}» ...")
                contentResolver.openInputStream(sendUris[idx])!!.use { ins ->
                    val buf = ByteArray(CHUNK)
                    var sent = 0L
                    while (sent < f.size) {
                        val n = ins.read(buf, 0, minOf(buf.size.toLong(), f.size - sent).toInt())
                        if (n < 0) throw IOException("فایل «${f.name}» زودتر از حد انتظار تمام شد")
                        out.write(buf, 0, n)
                        sent += n
                        sentTotal += n
                        setProgress(sentTotal, totalBytes)
                    }
                }
                log("✓ «${f.name}» ارسال شد (${fmt(f.size)})")
            }
            out.flush()
            inp.readByte() // تایید نهایی
        }
        log("✓ ارسال تمام شد")
    }

    override fun onDestroy() {
        running = false
        try { serverSock?.close() } catch (_: Exception) {}
        try { discSock?.close() } catch (_: Exception) {}
        try { multicastLock?.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}
