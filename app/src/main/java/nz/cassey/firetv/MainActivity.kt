package nz.cassey.firetv

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import dadb.AdbKeyPair
import dadb.Dadb
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private const val ADB_PORT = 5555
private const val REPO = "Cassey88/firetv-remote"

private const val BG = 0xFF0F1720.toInt()
private const val BTN = 0xFF1F2A37.toInt()
private const val BTN_PRESSED = 0xFF2F4157.toInt()
private const val ACCENT = 0xFF0078D4.toInt()
private const val ACCENT_PRESSED = 0xFF3399E6.toInt()
private const val GREEN = 0xFF4CAF50.toInt()
private const val RED = 0xFFE57373.toInt()
private const val GREY = 0xFF9AA5B1.toInt()

class MainActivity : Activity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("firetv", Context.MODE_PRIVATE) }

    private val tvs = arrayOf(Tv(this, 0), Tv(this, 1))
    private val statusText = arrayOf("", "")
    private val statusOk = arrayOf<Boolean?>(null, null)
    private val statusViews = arrayOfNulls<TextView>(2)
    private val selectorButtons = arrayOfNulls<Button>(2)
    private var selected = 0
    private lateinit var bothBox: CheckBox
    private lateinit var updateStatus: TextView
    private lateinit var playBtn: Button
    private var playing: Boolean? = null

    private val pollRunnable = object : Runnable {
        override fun run() {
            pollPlayState()
            mainHandler.postDelayed(this, 4000)
        }
    }

    private fun refreshPlay() {
        playBtn.text = if (playing == true) "❚❚  Pause" else "▶  Play"
    }

    /** Asks the selected Fire TV whether something is playing, so the big button shows Pause or Play. */
    private fun pollPlayState() {
        if (tvIp(selected).isBlank()) return
        val at = selected
        tvs[at].run("dumpsys media_session | grep \"PlaybackState {\"", true) { out ->
            if (at == selected) {
                val states = Regex("PlaybackState \\{state=(\\d+)").findAll(out)
                    .map { it.groupValues[1].toInt() }.toList()
                if (states.isNotEmpty()) {
                    playing = states.any { it == 3 }
                    refreshPlay()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        mainHandler.postDelayed(pollRunnable, 1500)
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(pollRunnable)
    }

    private var cachedKey: AdbKeyPair? = null

    // ---------------------------------------------------------------- settings

    fun tvName(i: Int): String = prefs.getString("name$i", "TV ${i + 1}")!!.ifBlank { "TV ${i + 1}" }
    fun tvIp(i: Int): String = prefs.getString("ip$i", "")!!.trim()

    @Synchronized
    fun keyPair(): AdbKeyPair {
        cachedKey?.let { return it }
        val priv = File(filesDir, "adbkey")
        val pub = File(filesDir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        val k = AdbKeyPair.read(priv, pub)
        cachedKey = k
        return k
    }

    fun setStatus(i: Int, text: String, ok: Boolean?) {
        mainHandler.post {
            statusText[i] = text
            statusOk[i] = ok
            refreshStatus()
        }
    }

    // ---------------------------------------------------------------- UI helpers

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor(color)
    }

    private fun bg(normal: Int, pressed: Int): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), rounded(pressed))
        addState(intArrayOf(), rounded(normal))
    }

    private fun makeButton(label: String, accent: Boolean = false, textSize: Float = 18f): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.WHITE)
            this.textSize = textSize
            stateListAnimator = null
            background = if (accent) bg(ACCENT, ACCENT_PRESSED) else bg(BTN, BTN_PRESSED)
        }

    private fun row(height: Int = 64): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(height)
        ).apply { topMargin = dp(10) }
    }

    private fun cell(v: View, weight: Float = 1f) {
        v.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight)
            .apply { marginStart = dp(5); marginEnd = dp(5) }
    }

    private fun addTo(parent: LinearLayout, v: View) {
        cell(v)
        parent.addView(v)
    }

    private fun spacer(): View = View(this)

    private fun haptic(v: View) {
        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun keyButton(label: String, keyCode: Int, accent: Boolean = false, repeat: Boolean = false, textSize: Float = 18f, shell: String? = null): Button {
        val b = makeButton(label, accent, textSize)
        val cmdStr = shell ?: "input keyevent $keyCode"
        if (repeat) {
            val r = object : Runnable {
                override fun run() {
                    sendCmd(cmdStr, true)
                    mainHandler.postDelayed(this, 130)
                }
            }
            b.setOnTouchListener { v, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.isPressed = true
                        haptic(v)
                        sendCmd(cmdStr, false)
                        mainHandler.postDelayed(r, 450)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        mainHandler.removeCallbacks(r)
                    }
                }
                true
            }
        } else {
            b.setOnClickListener { haptic(it); sendCmd(cmdStr, false) }
        }
        return b
    }

    private fun appButton(label: String, pkg: String): Button {
        val b = makeButton(label, false, 15f)
        b.setOnClickListener {
            haptic(it)
            sendShell("monkey -p $pkg -c android.intent.category.LAUNCHER 1")
        }
        return b
    }

    // ---------------------------------------------------------------- auto detect

    private fun localIpv4(): Inet4Address? {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return null
        val lp = cm.getLinkProperties(net) ?: return null
        return lp.linkAddresses.map { it.address }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress } as Inet4Address?
    }

    /** Scans the home Wi-Fi (/24) for devices with ADB (port 5555) open: Fire TVs with ADB debugging on. */
    private fun scanFireTvs(done: (List<String>) -> Unit) {
        Thread {
            val me = localIpv4()
            if (me == null) {
                runOnUiThread { done(emptyList()) }
                return@Thread
            }
            val b = me.address
            val prefix = "${b[0].toInt() and 255}.${b[1].toInt() and 255}.${b[2].toInt() and 255}."
            val myLast = b[3].toInt() and 255
            val found = java.util.Collections.synchronizedList(mutableListOf<String>())
            val pool = Executors.newFixedThreadPool(64)
            val latch = CountDownLatch(254)
            for (n in 1..254) {
                if (n == myLast) { latch.countDown(); continue }
                pool.execute {
                    val sock = Socket()
                    try {
                        sock.connect(InetSocketAddress(prefix + n, ADB_PORT), 700)
                        found.add(prefix + n)
                    } catch (_: Exception) {
                    } finally {
                        try { sock.close() } catch (_: Exception) {}
                        latch.countDown()
                    }
                }
            }
            latch.await(20, TimeUnit.SECONDS)
            pool.shutdownNow()
            val result = found.toList().sortedBy { it.substringAfterLast('.').toInt() }
            runOnUiThread { done(result) }
        }.start()
    }

    /** Fills the TV slots from the scan: keeps IPs that were found again, puts new ones in the empty/lost slots. */
    private fun assignFound(current: List<String>, found: List<String>): List<String> {
        val keep = current.map { if (it in found) it else "" }.toMutableList()
        val rest = found.filter { it !in keep }.toMutableList()
        for (i in keep.indices) if (keep[i].isEmpty() && rest.isNotEmpty()) keep[i] = rest.removeAt(0)
        return keep
    }

    private fun autoDetect(showSettingsIfNone: Boolean) {
        for (i in 0..1) setStatus(i, "Searching your Wi-Fi for Fire TVs…", null)
        scanFireTvs { found ->
            if (found.isEmpty()) {
                for (i in 0..1) setStatus(i, if (tvIp(i).isBlank()) "No Fire TV found" else "Can't reach ${tvIp(i)}", false)
                Toast.makeText(this, "No Fire TV found. Turn the TV on and ADB debugging on.", Toast.LENGTH_LONG).show()
                if (showSettingsIfNone) showSettings()
            } else {
                val newIps = assignFound(listOf(tvIp(0), tvIp(1)), found)
                val ed = prefs.edit()
                for (i in 0..1) ed.putString("ip$i", newIps[i])
                ed.apply()
                for (i in 0..1) {
                    tvs[i].reset()
                    setStatus(i, if (newIps[i].isBlank()) "No IP address set" else "Found at ${newIps[i]}", null)
                }
                Toast.makeText(this, "Found ${found.size} Fire TV: ${found.joinToString(", ")}", Toast.LENGTH_LONG).show()
                if (tvIp(selected).isNotBlank()) tvs[selected].run("echo ok", false)
            }
        }
    }

    // ---------------------------------------------------------------- sending

    private fun targets(): List<Int> {
        val list = if (bothBox.isChecked) listOf(0, 1) else listOf(selected)
        return list.filter { tvIp(it).isNotBlank() }.ifEmpty { listOf(selected) }
    }

    private fun sendCmd(cmd: String, droppable: Boolean) {
        for (i in targets()) tvs[i].run(cmd, droppable)
    }

    private fun sendShell(cmd: String) {
        for (i in targets()) tvs[i].run(cmd, false)
    }

    // ---------------------------------------------------------------- build UI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selected = prefs.getInt("selected", 0).coerceIn(0, 1)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(24))
        }

        // header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "Fire TV Remote"
            setTextColor(Color.WHITE)
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val version = TextView(this).apply {
            text = "v${BuildConfig.VERSION_NAME}"
            setTextColor(GREY)
            textSize = 14f
            setPadding(0, 0, dp(12), 0)
        }
        val gear = makeButton("Settings", false, 14f).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(42))
            setPadding(dp(14), 0, dp(14), 0)
            setOnClickListener { showSettings() }
        }
        header.addView(title)
        header.addView(version)
        header.addView(gear)
        root.addView(header)

        // TV selector
        val sel = row(56)
        for (i in 0..1) {
            val b = makeButton(tvName(i), i == selected, 16f)
            b.setOnClickListener {
                selected = i
                prefs.edit().putInt("selected", i).apply()
                playing = null
                refreshPlay()
                refreshSelector()
                tvs[i].run("echo ok", false)
            }
            selectorButtons[i] = b
            addTo(sel, b)
        }
        root.addView(sel)

        // status lines
        for (i in 0..1) {
            val t = TextView(this).apply {
                textSize = 13f
                setPadding(dp(6), dp(6), 0, dp(2))
                setOnClickListener { autoDetect(false) }
            }
            statusViews[i] = t
            root.addView(t)
        }

        bothBox = CheckBox(this).apply {
            text = "Control both TVs at once"
            setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean("both", false)
            setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("both", c).apply() }
        }
        root.addView(bothBox)

        // power
        val power = row(56)
        addTo(power, keyButton("Wake up", 224, true))
        addTo(power, keyButton("Sleep", 223))
        root.addView(power)

        // d-pad
        val r1 = row(72)
        addTo(r1, spacer()); addTo(r1, keyButton("▲", 19, repeat = true, textSize = 24f)); addTo(r1, spacer())
        val r2 = row(72)
        addTo(r2, keyButton("◀", 21, repeat = true, textSize = 24f))
        addTo(r2, keyButton("OK", 23, true, textSize = 20f))
        addTo(r2, keyButton("▶", 22, repeat = true, textSize = 24f))
        val r3 = row(72)
        addTo(r3, spacer()); addTo(r3, keyButton("▼", 20, repeat = true, textSize = 24f)); addTo(r3, spacer())
        root.addView(r1); root.addView(r2); root.addView(r3)

        // back / home / menu
        val nav = row(58)
        addTo(nav, keyButton("Back", 4))
        addTo(nav, keyButton("Home", 3))
        addTo(nav, keyButton("Menu", 82))
        root.addView(nav)

        // media
        val playRow = row(96)
        playBtn = makeButton("▶  Play", true, 30f)
        playBtn.setTypeface(playBtn.typeface, Typeface.BOLD)
        playBtn.setOnClickListener {
            haptic(it)
            playing = playing != true
            refreshPlay()
            sendCmd("input keyevent 85", false)
            mainHandler.postDelayed({ pollPlayState() }, 900)
        }
        addTo(playRow, playBtn)
        root.addView(playRow)

        val skip = row(52)
        addTo(skip, keyButton("⏪  Rewind", 89, textSize = 16f))
        addTo(skip, keyButton("Forward  ⏩", 90, textSize = 16f))
        root.addView(skip)

        // volume
        val vol = row(58)
        addTo(vol, keyButton("Vol –", 25, repeat = true,
            shell = "cmd media_session volume --show --stream 3 --adj lower || input keyevent 25"))
        addTo(vol, keyButton("Mute", 164,
            shell = "cmd media_session volume --show --stream 3 --adj toggle-mute || input keyevent 164"))
        addTo(vol, keyButton("Vol +", 24, repeat = true,
            shell = "cmd media_session volume --show --stream 3 --adj raise || input keyevent 24"))
        root.addView(vol)

        // apps
        val apps = row(54)
        addTo(apps, appButton("Netflix", "com.netflix.ninja"))
        addTo(apps, appButton("YouTube", "com.amazon.firetv.youtube"))
        addTo(apps, appButton("Prime Video", "com.amazon.avod"))
        root.addView(apps)

        // updates
        val upd = makeButton("Check for updates", false, 14f)
        upd.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
            .apply { topMargin = dp(22) }
        upd.setOnClickListener { checkUpdate(true) }
        root.addView(upd)

        updateStatus = TextView(this).apply {
            setTextColor(GREY)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        }
        root.addView(updateStatus)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            addView(root)
        })

        for (i in 0..1) setStatus(i, if (tvIp(i).isBlank()) "No IP address set" else "Not connected yet", null)
        refreshSelector()

        if (tvIp(0).isBlank() && tvIp(1).isBlank()) {
            autoDetect(true)
        } else if (tvIp(selected).isNotBlank()) {
            tvs[selected].run("echo ok", false)
        }
        checkUpdate(false)
    }

    private fun refreshSelector() {
        for (i in 0..1) {
            selectorButtons[i]?.apply {
                text = tvName(i)
                background = if (i == selected) bg(ACCENT, ACCENT_PRESSED) else bg(BTN, BTN_PRESSED)
            }
        }
        refreshStatus()
    }

    private fun refreshStatus() {
        for (i in 0..1) {
            val v = statusViews[i] ?: continue
            v.text = "●  ${tvName(i)} — ${statusText[i]}"
            v.setTextColor(
                when (statusOk[i]) {
                    true -> GREEN
                    false -> RED
                    null -> GREY
                }
            )
        }
    }

    // ---------------------------------------------------------------- settings dialog

    private fun showSettings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), 0)
        }
        val info = TextView(this).apply {
            text = "On each Fire TV: Settings → My Fire TV → Developer options → turn ADB debugging ON. " +
                "(If Developer options is missing: About → click the device name 7 times.) " +
                "Then tap Find Fire TVs below (or type the IP address). The first time, accept the prompt on the TV."
            textSize = 13f
        }
        box.addView(info)

        val nameFields = arrayOfNulls<EditText>(2)
        val ipFields = arrayOfNulls<EditText>(2)
        for (i in 0..1) {
            val label = TextView(this).apply {
                text = "TV ${i + 1}"
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(14), 0, 0)
            }
            box.addView(label)
            val n = EditText(this).apply {
                hint = "Name (e.g. Lounge)"
                setText(prefs.getString("name$i", "") ?: "")
                inputType = InputType.TYPE_CLASS_TEXT
                setSingleLine()
            }
            val ip = EditText(this).apply {
                hint = "IP address (e.g. 192.168.1.50)"
                setText(prefs.getString("ip$i", "") ?: "")
                inputType = InputType.TYPE_CLASS_TEXT
                setSingleLine()
            }
            nameFields[i] = n
            ipFields[i] = ip
            box.addView(n)
            box.addView(ip)
        }

        val findBtn = makeButton("Find Fire TVs on my Wi-Fi", true, 14f)
        findBtn.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
            .apply { topMargin = dp(16) }
        findBtn.setOnClickListener {
            findBtn.isEnabled = false
            findBtn.text = "Searching… (a few seconds)"
            scanFireTvs { found ->
                findBtn.isEnabled = true
                findBtn.text = "Find Fire TVs on my Wi-Fi"
                if (found.isEmpty()) {
                    Toast.makeText(this, "None found. Turn the TVs on and ADB debugging on.", Toast.LENGTH_LONG).show()
                } else {
                    val newIps = assignFound(ipFields.map { it!!.text.toString().trim() }, found)
                    for (i in 0..1) ipFields[i]!!.setText(newIps[i])
                    Toast.makeText(this, "Found: ${found.joinToString(", ")}. Tap Save.", Toast.LENGTH_LONG).show()
                }
            }
        }
        box.addView(findBtn)

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                val ed = prefs.edit()
                for (i in 0..1) {
                    ed.putString("name$i", nameFields[i]!!.text.toString().trim())
                    ed.putString("ip$i", ipFields[i]!!.text.toString().trim())
                }
                ed.apply()
                for (i in 0..1) {
                    tvs[i].reset()
                    setStatus(i, if (tvIp(i).isBlank()) "No IP address set" else "Not connected yet", null)
                }
                refreshSelector()
                if (tvIp(selected).isNotBlank()) tvs[selected].run("echo ok", false)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------- updates

    private fun checkUpdate(manual: Boolean) {
        if (manual) updateStatus.text = "Checking for updates…"
        Thread {
            try {
                val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.connectTimeout = 8000
                c.readTimeout = 8000
                val json = JSONObject(c.inputStream.bufferedReader().readText())
                val tag = json.getString("tag_name")
                val latest = tag.removePrefix("v").substringAfter('.', "0").toIntOrNull() ?: 0
                var apkUrl: String? = null
                val assets = json.getJSONArray("assets")
                for (k in 0 until assets.length()) {
                    val a = assets.getJSONObject(k)
                    if (a.getString("name").endsWith(".apk")) apkUrl = a.getString("browser_download_url")
                }
                if (latest > BuildConfig.VERSION_CODE && apkUrl != null) {
                    val url = apkUrl
                    runOnUiThread {
                        updateStatus.text = "Update available: $tag"
                        AlertDialog.Builder(this)
                            .setTitle("Update available")
                            .setMessage("Version ${tag.removePrefix("v")} is ready (you have ${BuildConfig.VERSION_NAME}). Download and install now?")
                            .setPositiveButton("Update") { _, _ -> downloadAndInstall(url) }
                            .setNegativeButton("Later", null)
                            .show()
                    }
                } else {
                    runOnUiThread { updateStatus.text = if (manual) "You have the latest version (${BuildConfig.VERSION_NAME})" else "" }
                }
            } catch (e: Exception) {
                runOnUiThread { if (manual) updateStatus.text = "Could not check for updates" }
            }
        }.start()
    }

    private fun downloadAndInstall(url: String) {
        if (!packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "Allow installing from this app, then tap Check for updates again", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            )
            return
        }
        updateStatus.text = "Downloading update…"
        Thread {
            try {
                val dir = File(cacheDir, "updates").apply { mkdirs() }
                val file = File(dir, "FireTV-Remote.apk")
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 30000
                c.instanceFollowRedirects = true
                c.inputStream.use { input -> file.outputStream().use { out -> input.copyTo(out) } }
                runOnUiThread {
                    updateStatus.text = "Installing…"
                    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                    val i = Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(i)
                }
            } catch (e: Exception) {
                runOnUiThread { updateStatus.text = "Download failed: ${e.message}" }
            }
        }.start()
    }
}

/** One Fire TV, controlled over ADB (port 5555). Commands run on a private single thread. */
class Tv(private val ctx: MainActivity, private val idx: Int) {
    private val ex = Executors.newSingleThreadExecutor()
    private val pending = AtomicInteger(0)

    @Volatile
    private var dadb: Dadb? = null

    private fun closeQuiet() {
        try { dadb?.close() } catch (_: Exception) {}
        dadb = null
    }

    fun reset() {
        ex.execute { closeQuiet() }
    }

    private fun open(ip: String): Dadb {
        val s = Socket()
        try {
            s.connect(InetSocketAddress(ip, ADB_PORT), 3000)
        } finally {
            try { s.close() } catch (_: Exception) {}
        }
        return Dadb.create(ip, ADB_PORT, ctx.keyPair())
    }

    private fun execOnce(cmd: String, ip: String): String {
        val d = dadb ?: open(ip).also { dadb = it }
        return d.shell(cmd).allOutput
    }

    fun run(cmd: String, droppable: Boolean, onOutput: ((String) -> Unit)? = null) {
        if (droppable && pending.get() >= 3) return
        pending.incrementAndGet()
        ex.execute {
            try {
                val ip = ctx.tvIp(idx)
                if (ip.isBlank()) {
                    ctx.setStatus(idx, "No IP address set (open Settings)", false)
                    return@execute
                }
                try {
                    if (dadb == null) ctx.setStatus(idx, "Connecting… accept the prompt on the TV if shown", null)
                    val out = execOnce(cmd, ip)
                    ctx.setStatus(idx, "Connected", true)
                    onOutput?.let { cb -> ctx.runOnUiThread { cb(out) } }
                } catch (e: Exception) {
                    closeQuiet()
                    try {
                        val out = execOnce(cmd, ip)
                        ctx.setStatus(idx, "Connected", true)
                        onOutput?.let { cb -> ctx.runOnUiThread { cb(out) } }
                    } catch (e2: Exception) {
                        closeQuiet()
                        ctx.setStatus(idx, "Can't reach $ip — TV on? ADB debugging on?", false)
                    }
                }
            } finally {
                pending.decrementAndGet()
            }
        }
    }
}
