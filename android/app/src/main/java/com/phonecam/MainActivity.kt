package com.phonecam

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : ComponentActivity() {
    private lateinit var prefs: Prefs
    private lateinit var pal: Pal
    private lateinit var root: FrameLayout
    private lateinit var bar: LinearLayout
    private lateinit var pages: FrameLayout
    private lateinit var homeScroll: ScrollView
    private lateinit var settingsScroll: ScrollView
    private var onSettings = false
    private var insets = androidx.core.graphics.Insets.NONE
    private var unlisten: (() -> Unit)? = null
    private var pcs: List<Pc> = emptyList()
    private var searching = false

    // live views updated from Status
    private lateinit var pill: TextView
    private lateinit var pillDot: View
    private lateinit var startBtn: TextView
    private lateinit var streamLine: TextView
    private lateinit var errorLine: TextView
    private lateinit var wifiBox: LinearLayout
    private lateinit var pcList: LinearLayout
    private lateinit var searchBtn: TextView
    private lateinit var title: TextView
    private lateinit var backBtn: ImageView
    private lateinit var gearBtn: ImageView
    private var pendingStart = false

    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { showPage(false) }
    }

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.CAMERA] == true || granted(Manifest.permission.CAMERA)) StreamService.start(this)
        else Status.publish(Snap(error = Err.PERMISSION))
    }

    override fun attachBaseContext(base: Context) = super.attachBaseContext(Prefs.wrap(base))

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun isDark(): Boolean = when (prefs.theme) {
        "dark" -> true
        "light" -> false
        else -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        pal = Pal(isDark())
        UpdateManager(this).deleteInstallerFiles()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onBackPressedDispatcher.addCallback(this, back)
        build()
        handleIntent(intent)
        UpdatePopup.autoCheck(this, pal)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.getStringExtra("autostart") == "usb" && Status.current.state == Snap.State.IDLE) {
            prefs.mode = "usb"
            build()
            start()
        }
    }

    override fun onStart() {
        super.onStart()
        unlisten = Status.listen { renderStatus(it) }
    }

    override fun onStop() {
        unlisten?.invoke()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (UpdatePopup.handoff) { UpdatePopup.handoff = false; UpdateManager(this).deleteInstallerFiles() }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (prefs.theme == "system") { pal = Pal(isDark()); build() }
    }

    /* ---------- Frame: bar on top, pages below, insets applied once ---------- */
    private fun build() {
        applySystemBars()
        root = FrameLayout(this).apply { setBackgroundColor(pal.bg) }
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(pal.bg))
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bar = buildBar()
        pages = FrameLayout(this)
        homeScroll = page(buildHome())
        settingsScroll = page(buildSettings())
        pages.addView(homeScroll)
        pages.addView(settingsScroll)
        column.addView(bar, LinearLayout.LayoutParams(-1, -2))
        column.addView(pages, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(column, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, w ->
            val sys = w.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = w.getInsets(WindowInsetsCompat.Type.ime())
            insets = androidx.core.graphics.Insets.of(sys.left, sys.top, sys.right, maxOf(sys.bottom, ime.bottom))
            applyInsets()
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(root)
        showPage(onSettings, animate = false)
        renderStatus(Status.current)
    }

    private fun applyInsets() {
        bar.setPadding(insets.left + dp(12), insets.top + dp(6), insets.right + dp(12), dp(6))
        for (s in listOf(homeScroll, settingsScroll)) {
            val content = s.getChildAt(0) as ViewGroup
            val side = maxOf(dp(16), (resources.displayMetrics.widthPixels - dp(640)) / 2)
            content.setPadding(insets.left + side, dp(12), insets.right + side, insets.bottom + dp(24))
        }
    }

    private fun applySystemBars() {
        val transparent = android.graphics.Color.TRANSPARENT
        enableEdgeToEdge(
            statusBarStyle = if (pal.dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent),
            navigationBarStyle = if (pal.dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent),
        )
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !pal.dark
            isAppearanceLightNavigationBars = !pal.dark
        }
    }

    private fun page(content: View): ScrollView = ScrollView(this).apply {
        isFillViewport = false
        overScrollMode = View.OVER_SCROLL_NEVER
        val wrap = FrameLayout(this@MainActivity)
        wrap.addView(content, FrameLayout.LayoutParams(-1, -2))
        addView(wrap, ViewGroup.LayoutParams(-1, -2))
        (wrap as ViewGroup).clipChildren = false
    }

    private fun iconButton(res: Int, desc: String, onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(pal.muted)
        contentDescription = desc
        scaleType = ImageView.ScaleType.CENTER
        isClickable = true
        isFocusable = true
        val r = dp(14).toFloat()
        background = ripple(pal, rounded(android.graphics.Color.TRANSPARENT, r), r)
        setOnClickListener { onClick() }
        pressable()
    }

    private fun buildBar(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        backBtn = iconButton(R.drawable.ic_back, getString(R.string.nav_back)) { showPage(false) }
        title = text(this@MainActivity, pal, getString(R.string.app_name), 18f, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        pillDot = View(context).apply { background = rounded(pal.muted, dp(4).toFloat()) }
        pill = text(this@MainActivity, pal, "", 12.5f, true, true).apply {
            maxLines = 1
            setPadding(dp(8), dp(5), dp(12), dp(5))
            compoundDrawablePadding = dp(0)
        }
        val pillBox = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(pal.inset, dp(20).toFloat())
            setPadding(dp(12), 0, 0, 0)
            addView(pillDot, LinearLayout.LayoutParams(dp(8), dp(8)))
            addView(pill)
        }
        gearBtn = iconButton(R.drawable.ic_gear, getString(R.string.settings)) { showPage(true) }
        addView(backBtn, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(title, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
        addView(pillBox, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(4) })
        addView(gearBtn, LinearLayout.LayoutParams(dp(48), dp(48)))
    }

    /** Directional page change: new page slides in from the end, old one leaves the other way. */
    private fun showPage(settings: Boolean, animate: Boolean = true) {
        val wasSettings = onSettings
        onSettings = settings
        back.isEnabled = settings
        backBtn.visibility = if (settings) View.VISIBLE else View.GONE
        gearBtn.visibility = if (settings) View.INVISIBLE else View.VISIBLE
        title.text = getString(if (settings) R.string.settings else R.string.app_name)
        val to = if (settings) settingsScroll else homeScroll
        val from = if (settings) homeScroll else settingsScroll
        if (!animate || wasSettings == settings) {
            to.visibility = View.VISIBLE; to.alpha = 1f; to.translationX = 0f
            from.visibility = View.GONE
            return
        }
        val sign = (if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) -1 else 1) * (if (settings) 1 else -1)
        to.visibility = View.VISIBLE; to.alpha = 0f; to.translationX = sign * dp(28).toFloat()
        to.animate().alpha(1f).translationX(0f).setDuration(380).setInterpolator(DecelerateInterpolator(2.2f)).start()
        from.animate().alpha(0f).translationX(-sign * dp(28).toFloat()).setDuration(220).withEndAction { from.visibility = View.GONE }.start()
    }

    /* ---------- Home ---------- */
    private fun col() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun LinearLayout.add(v: View, top: Int = 0) = addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })

    private fun buildHome(): View = col().apply {
        // Connection
        val conn = card(context, pal)
        conn.addView(text(context, pal, getString(R.string.connection), 13f, true))
        val modes = listOf("usb", "wifi", "both")
        val seg = Segmented(context, pal, listOf(getString(R.string.mode_usb), getString(R.string.mode_wifi), getString(R.string.mode_both)), modes.indexOf(prefs.mode).coerceAtLeast(0)) {
            prefs.mode = modes[it]
            updateModeViews(true)
            updateStreamLine()
        }
        conn.addView(seg, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        val hint = text(context, pal, "", 13f, muted = true)
        conn.addView(hint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        wifiBox = col()
        pcList = col()
        searchBtn = button(context, pal, getString(R.string.wifi_search)) { searchPcs() }
        val host = field(prefs.host, getString(R.string.host_hint), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI) { prefs.host = it.trim() }
        val pin = field(prefs.pin, getString(R.string.pin_hint), InputType.TYPE_CLASS_NUMBER, 6) { prefs.pin = it.trim() }
        wifiBox.add(pcList, 8)
        wifiBox.add(searchBtn, 8)
        wifiBox.add(host, 12)
        wifiBox.add(pin, 8)
        conn.addView(wifiBox, LinearLayout.LayoutParams(-1, -2))
        hintView = hint
        add(conn)

        // Stream
        val stream = card(context, pal)
        stream.addView(text(context, pal, getString(R.string.quality), 13f, true))
        streamLine = text(context, pal, "", 17f, true)
        stream.addView(streamLine, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        maxLine = text(context, pal, "", 13f, muted = true)
        stream.addView(maxLine, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        add(stream, 12)

        startBtn = button(context, pal, getString(R.string.btn_start), Kind.PRIMARY) { toggle() }
        add(startBtn, 16)
        errorLine = text(context, pal, "", 13.5f).apply { setTextColor(pal.danger); visibility = View.GONE }
        add(errorLine, 12)
        add(text(context, pal, getString(R.string.about_tip), 12.5f, muted = true), 14)
        updateModeViews(false)
        updateStreamLine()
    }

    private lateinit var hintView: TextView
    private lateinit var maxLine: TextView

    private fun updateModeViews(animate: Boolean) {
        val usb = prefs.mode == "usb"
        hintView.text = getString(when (prefs.mode) { "usb" -> R.string.usb_hint; "both" -> R.string.both_hint; else -> R.string.wifi_hint })
        if (usb) wifiBox.visibility = View.GONE else if (animate) fade(wifiBox, true) else wifiBox.visibility = View.VISIBLE
        if (!usb && pcs.isEmpty() && !searching && !animate) searchPcs() else if (!usb && animate && pcs.isEmpty()) searchPcs()
    }

    private fun updateStreamLine() {
        val m = Caps.resolve(this, prefs)
        streamLine.text = if (m == null) getString(R.string.quality_none)
        else getString(R.string.quality_value, m.width.toString(), m.height.toString(), m.fps.toString(), m.codecName, getString(R.string.bitrate_value, "%.0f".format(m.kbps / 1000.0)))
        val best = Caps.best(this, prefs.front, prefs.mode == "usb")
        maxLine.text = if (best == null) "" else getString(R.string.quality_max, "${best.width}×${best.height} @ ${best.fps} fps")
    }

    private fun field(value: String, hint: String, type: Int, maxLen: Int = 64, onChange: (String) -> Unit) = EditText(this).apply {
        setText(value)
        this.hint = hint
        inputType = type
        imeOptions = EditorInfo.IME_ACTION_DONE
        isSingleLine = true
        filters = arrayOf(android.text.InputFilter.LengthFilter(maxLen))
        setTextColor(pal.text)
        setHintTextColor(pal.muted)
        textSize = 15f
        minHeight = dp(48)
        setPadding(dp(14), 0, dp(14), 0)
        background = rounded(pal.inset, dp(13).toFloat(), pal.border, dp(1))
        addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = onChange(s.toString())
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    private fun searchPcs() {
        if (searching) return
        searching = true
        searchBtn.text = getString(R.string.wifi_searching)
        Thread {
            val found = Discovery.find()
            runOnUiThread {
                searching = false
                pcs = found
                searchBtn.text = getString(R.string.wifi_search)
                renderPcs()
            }
        }.start()
    }

    private fun renderPcs() {
        pcList.removeAllViews()
        if (pcs.isEmpty()) { pcList.add(text(this, pal, getString(R.string.wifi_none), 13f, muted = true)); return }
        pcs.forEach { pc ->
            val sel = prefs.host == pc.host
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                val r = dp(13).toFloat()
                background = ripple(pal, rounded(if (sel) pal.inset else pal.surface, r, if (sel) pal.accent else pal.border, dp(1)), r)
                isClickable = true
                setOnClickListener { prefs.host = pc.host; renderPcs() }
                pressable()
            }
            row.addView(text(this, pal, pc.name, 15f, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            row.addView(text(this, pal, pc.host, 12.5f, muted = true))
            pcList.add(row, 6)
        }
    }

    /* ---------- Start / stop ---------- */
    private fun running() = Status.current.state != Snap.State.IDLE

    private fun toggle() { if (running()) StreamService.stop(this) else start() }

    private fun start() {
        Status.publish(Snap())
        val need = mutableListOf<String>()
        if (!granted(Manifest.permission.CAMERA)) need += Manifest.permission.CAMERA
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isEmpty()) StreamService.start(this) else permissions.launch(need.toTypedArray())
    }

    private fun renderStatus(s: Snap) {
        if (!::pill.isInitialized) return
        val (label, color) = when (s.state) {
            Snap.State.IDLE -> getString(R.string.status_idle) to pal.muted
            Snap.State.SEARCHING -> getString(R.string.status_searching) to pal.muted
            Snap.State.CONNECTING -> getString(R.string.status_connecting) to pal.muted
            Snap.State.WAITING -> getString(R.string.status_waiting) to pal.muted
            Snap.State.LIVE -> getString(R.string.status_live) + " · ${s.fps} fps" to pal.accent
        }
        pill.text = label
        pill.setTextColor(color)
        pillDot.background = rounded(color, dp(4).toFloat())
        val run = s.state != Snap.State.IDLE
        startBtn.text = getString(if (run) R.string.btn_stop else R.string.btn_start)
        val err = when (s.error) {
            Err.NONE -> ""
            Err.WRONG_PIN -> getString(R.string.err_wrong_pin)
            Err.PIN_LOCKED -> getString(R.string.err_pin_locked)
            Err.UNTRUSTED -> getString(R.string.err_untrusted)
            Err.NO_PC -> getString(R.string.err_no_pc)
            Err.NO_CAMERA -> getString(R.string.err_no_camera)
            Err.CAMERA -> getString(R.string.err_camera, s.detail)
            Err.NO_FFMPEG -> getString(R.string.err_no_ffmpeg)
            Err.UNSUPPORTED -> getString(R.string.err_unsupported)
            Err.PERMISSION -> getString(R.string.err_permission)
        }
        errorLine.text = err
        errorLine.visibility = if (err.isEmpty()) View.GONE else View.VISIBLE
        if (s.state == Snap.State.LIVE && s.mode != null) {
            streamLine.text = getString(R.string.quality_value, s.mode.width.toString(), s.mode.height.toString(), s.fps.toString(), s.mode.codecName, getString(R.string.bitrate_value, "%.1f".format(s.kbps / 1000.0)))
            val links = s.links.joinToString(" + ") { (via, kbps) ->
                getString(if (via == "usb") R.string.mode_usb else R.string.mode_wifi) + " " + getString(R.string.bitrate_value, "%.1f".format(kbps / 1000.0))
            }
            maxLine.text = getString(R.string.live_links, links, s.pcKey?.let { Crypto.fingerprint(it) } ?: "")
        } else if (::maxLine.isInitialized && s.state == Snap.State.IDLE) updateStreamLine()
        if (s.error == Err.UNTRUSTED && s.pcKey != null && trustDialogFor !== s) {
            trustDialogFor = s
            askTrust(s.pcKey)
        }
    }

    private var trustDialogFor: Snap? = null

    /** First Wi-Fi connection to an unknown PC: the user compares the fingerprint with the PC app. */
    private fun askTrust(key: ByteArray) {
        val items = listOf("trust" to getString(R.string.trust_ok), "cancel" to getString(android.R.string.cancel))
        sheet(this, pal, getString(R.string.trust_title, Crypto.fingerprint(key)), items, "") {
            if (it == "trust") { prefs.trust(key); start() }
        }.show()
    }

    /* ---------- Settings ---------- */
    private fun buildSettings(): View = col().apply {
        fun group(titleRes: Int, rows: List<View>) {
            add(text(context, pal, getString(titleRes), 12.5f, true, true).apply { setPadding(dp(4), 0, 0, dp(6)) }, if (childCount == 0) 0 else 18)
            val c = card(context, pal).apply { setPadding(dp(18), dp(4), dp(18), dp(4)) }
            rows.forEachIndexed { i, r ->
                if (i > 0) c.addView(View(context).apply { setBackgroundColor(pal.border) }, LinearLayout.LayoutParams(-1, dp(1)))
                c.addView(r, LinearLayout.LayoutParams(-1, -2))
            }
            add(c)
        }

        val modes = listOf("system", "light", "dark")
        lateinit var themeSeg: Segmented
        themeSeg = Segmented(context, pal, listOf(getString(R.string.theme_system), getString(R.string.theme_light), getString(R.string.theme_dark)), modes.indexOf(prefs.theme)) { i ->
            changeTheme(modes[i], themeSeg.originX, themeSeg.originY)
        }
        group(R.string.appearance, listOf(
            stackedRow(R.string.theme, null, themeSeg),
            pickerRow(R.string.language, if (prefs.lang == "system") getString(R.string.language_system) else Prefs.LANGUAGES.first { it.first == prefs.lang }.second) { v ->
                sheet(this@MainActivity, pal, getString(R.string.language_title),
                    listOf("system" to getString(R.string.language_system)) + Prefs.LANGUAGES, prefs.lang) { prefs.lang = it; recreate() }.show()
            },
        ))

        val avail = Caps.available(context, prefs.front)
        val heights = avail.map { it.second }.distinct()
        val resItems = listOf("0" to getString(R.string.res_auto)) + heights.map { it.toString() to "${if (it >= 2160) "4K" else "${it}p"} (${avail.first { a -> a.second == it }.first}×$it)" }
        val fpsItems = listOf("0" to getString(R.string.fps_auto)) + avail.map { it.third }.distinct().map { it.toString() to "$it fps" }
        group(R.string.camera, listOf(
            stackedRow(R.string.facing, null, Segmented(context, pal, listOf(getString(R.string.facing_back), getString(R.string.facing_front)), if (prefs.front) 1 else 0) {
                prefs.front = it == 1; Caps.failed.clear(); updateStreamLine(); recreateSettingsLater()
            }),
            pickerRow(R.string.resolution, resItems.firstOrNull { it.first == prefs.maxHeight.toString() }?.second ?: getString(R.string.res_auto)) {
                sheet(this@MainActivity, pal, getString(R.string.resolution), resItems, prefs.maxHeight.toString()) { prefs.maxHeight = it.toInt(); Caps.failed.clear(); updateStreamLine(); refreshSettings() }.show()
            },
            pickerRow(R.string.fps, if (prefs.maxFps == 0) getString(R.string.fps_auto) else "${prefs.maxFps} fps") {
                sheet(this@MainActivity, pal, getString(R.string.fps), fpsItems, prefs.maxFps.toString()) { prefs.maxFps = it.toInt(); Caps.failed.clear(); updateStreamLine(); refreshSettings() }.show()
            },
            pickerRow(R.string.codec, when (prefs.codec) { "h264" -> "H.264"; "hevc" -> "HEVC"; else -> getString(R.string.codec_auto) }) {
                sheet(this@MainActivity, pal, getString(R.string.codec), listOf("auto" to getString(R.string.codec_auto), "hevc" to "HEVC", "h264" to "H.264"), prefs.codec) { prefs.codec = it; Caps.failed.clear(); updateStreamLine(); refreshSettings() }.show()
            },
            pickerRow(R.string.bitrate, if (prefs.bitrateMbit == 0) getString(R.string.bitrate_auto) else getString(R.string.bitrate_value, prefs.bitrateMbit.toString())) {
                val items = listOf("0" to getString(R.string.bitrate_auto)) + listOf(8, 16, 30, 50, 80).map { it.toString() to getString(R.string.bitrate_value, it.toString()) }
                sheet(this@MainActivity, pal, getString(R.string.bitrate), items, prefs.bitrateMbit.toString()) { prefs.bitrateMbit = it.toInt(); Caps.failed.clear(); updateStreamLine(); refreshSettings() }.show()
            },
        ))
        add(text(context, pal, getString(R.string.quality_hint), 12.5f, muted = true).apply { setPadding(dp(4), dp(8), dp(4), 0) })

        val status = text(context, pal, "", 13f, muted = true)
        val check = button(context, pal, getString(R.string.update_check)) { }
        check.setOnClickListener {
            status.text = getString(R.string.update_checking)
            Thread {
                try {
                    val info = UpdateManager(this@MainActivity).check()
                    runOnUiThread { if (info == null) status.text = getString(R.string.update_uptodate) else { status.text = ""; UpdatePopup.show(this@MainActivity, pal, info) } }
                } catch (_: Exception) { runOnUiThread { status.text = getString(R.string.update_failed) } }
            }.start()
        }
        group(R.string.updates, listOf(
            row(getString(R.string.update_version, UpdateManager(context).currentVersion()), status, check),
            row(getString(R.string.update_auto), text(context, pal, getString(R.string.update_auto_hint), 13f, muted = true), Toggle(context, pal, prefs.autoUpdate) { prefs.autoUpdate = it }),
        ))
    }

    private fun recreateSettingsLater() = refreshSettings()

    private fun refreshSettings() {
        val vis = settingsScroll.visibility
        val idx = pages.indexOfChild(settingsScroll)
        pages.removeView(settingsScroll)
        settingsScroll = page(buildSettings())
        settingsScroll.visibility = vis
        pages.addView(settingsScroll, idx)
        applyInsets()
    }

    private fun changeTheme(mode: String, x: Float, y: Float) {
        if (prefs.theme == mode) return
        val old = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
        prefs.theme = mode
        pal = Pal(isDark())
        build()
        showPage(true, animate = false)
        val loc = IntArray(2)
        root.getLocationOnScreen(loc)
        var overlay: RevealOverlay? = null
        overlay = RevealOverlay(this, old, x - loc[0], y - loc[1]) { overlay?.let { root.removeView(it) } }
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        overlay.post { overlay.start() }
    }

    private fun row(label: String, sub: View?, trailing: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(10), 0, dp(10))
        val left = col()
        left.addView(text(context, pal, label, 15f, true))
        if (sub != null) left.addView(sub, LinearLayout.LayoutParams(-1, -2))
        addView(left, LinearLayout.LayoutParams(0, -2, 1f))
        addView(trailing, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
    }

    private fun stackedRow(labelRes: Int, hint: String?, control: View): LinearLayout = col().apply {
        setPadding(0, dp(12), 0, dp(12))
        addView(text(context, pal, getString(labelRes), 15f, true))
        if (hint != null) addView(text(context, pal, hint, 13f, muted = true))
        addView(control, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }

    private fun pickerRow(labelRes: Int, value: String, onClick: (View) -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(56)
        isClickable = true
        setOnClickListener(onClick)
        addView(text(context, pal, getString(labelRes), 15f, true), LinearLayout.LayoutParams(0, -2, 1f))
        addView(text(context, pal, value, 14f, muted = true).apply { maxLines = 2; gravity = Gravity.END }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
    }

}
