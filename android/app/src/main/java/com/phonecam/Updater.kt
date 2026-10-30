package com.phonecam

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val REPO = "Emre1001/Phone-Cam"

/** Checks GitHub Releases, downloads the APK into the app cache, hands it to the system installer. */
class UpdateManager(private val ctx: Context) {
    data class Info(val version: String, val notes: String, val apkUrl: String, val size: Long)

    private val dir get() = File(ctx.cacheDir, "updates")

    /** Installer files are never kept. */
    fun deleteInstallerFiles() { dir.deleteRecursively() }

    fun currentVersion(): String = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"

    /** Blocking. Returns null when up to date; throws when the check itself fails. */
    fun check(): Info? {
        val conn = (URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "phone-cam-updater")
            connectTimeout = 10_000; readTimeout = 10_000
        }
        try {
            if (conn.responseCode != 200) throw java.io.IOException("http ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val version = json.getString("tag_name").removePrefix("v")
            if (!isNewer(version, currentVersion())) return null
            val assets = json.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val url = a.getString("browser_download_url")
                if (a.getString("name").endsWith(".apk") && url.startsWith("https://github.com/$REPO/releases/download/"))
                    return Info(version, json.optString("body"), url, a.optLong("size"))
            }
            return null
        } finally { conn.disconnect() }
    }

    fun download(info: Info, cancelled: () -> Boolean, onProgress: (Int) -> Unit): File {
        deleteInstallerFiles(); dir.mkdirs()
        val file = File(dir, "update.apk")
        val conn = URL(info.apkUrl).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000; conn.readTimeout = 15_000
            val total = if (conn.contentLengthLong > 0) conn.contentLengthLong else info.size
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024); var done = 0L; var last = -1
                    while (true) {
                        if (cancelled()) throw java.io.IOException("cancelled")
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        if (total > 0) { val p = (done * 100 / total).toInt(); if (p != last) { last = p; onProgress(p) } }
                    }
                }
            }
            return file
        } catch (e: Exception) { deleteInstallerFiles(); throw e } finally { conn.disconnect() }
    }

    fun canInstall() = ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings() = ctx.startActivity(
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    companion object {
        fun isNewer(remote: String, local: String): Boolean {
            val r = remote.split('.', '-').mapNotNull { it.toIntOrNull() }
            val l = local.split('.', '-').mapNotNull { it.toIntOrNull() }
            for (i in 0 until maxOf(r.size, l.size)) {
                val d = r.getOrElse(i) { 0 } - l.getOrElse(i) { 0 }
                if (d != 0) return d > 0
            }
            return false
        }
    }
}

/** The update popup: Install / Later / Skip this update, download progress, hand-over to the system installer. */
object UpdatePopup {
    private const val DAY = 24 * 3600 * 1000L

    /** Automatic check at start (at most every 6 hours). Silent unless a newer, not skipped version exists. */
    fun autoCheck(a: Activity, pal: Pal) {
        val p = Prefs(a)
        val now = System.currentTimeMillis()
        if (!p.autoUpdate || now - p.lastCheck < 6 * 3600 * 1000L) return
        Thread {
            try {
                val info = UpdateManager(a).check()
                p.lastCheck = System.currentTimeMillis()
                if (info != null && info.version != p.skippedVersion && System.currentTimeMillis() > p.remindAfter)
                    a.runOnUiThread { if (!a.isFinishing) show(a, pal, info) }
            } catch (_: Exception) {
            }
        }.start()
    }

    fun show(a: Activity, pal: Pal, info: UpdateManager.Info) {
        val um = UpdateManager(a)
        val p = Prefs(a)
        val d = Dialog(a, android.R.style.Theme_DeviceDefault_NoActionBar)
        d.window?.setBackgroundDrawable(ColorDrawable(pal.scrim))
        d.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        val holder = FrameLayout(a).apply { setPadding(a.dp(20), a.dp(20), a.dp(20), a.dp(20)) }
        val cardView = card(a, pal)
        holder.addView(cardView, FrameLayout.LayoutParams(minOf(a.dp(440), a.resources.displayMetrics.widthPixels - a.dp(40)), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        d.setContentView(holder)
        val cancelledFlag = java.util.concurrent.atomic.AtomicBoolean(false)

        fun render(state: String, progress: Int = 0, notice: String = "") {
            cardView.removeAllViews()
            fun add(v: View, topDp: Int = 12) = cardView.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = a.dp(topDp) })
            val title = when (state) { "handoff" -> R.string.update_handoff_title; "error" -> R.string.update_error; else -> R.string.update_title }
            cardView.addView(text(a, pal, a.getString(title), 19f, true))
            when (state) {
                "available" -> {
                    add(text(a, pal, a.getString(R.string.update_body, info.version, um.currentVersion()), 14f, muted = true), 8)
                    if (info.notes.isNotBlank()) {
                        add(text(a, pal, a.getString(R.string.update_notes), 12f, true, true))
                        add(ScrollView(a).apply { addView(text(a, pal, info.notes.take(1200), 13f), ViewGroup.LayoutParams(-1, -2)) }, 4)
                    }
                    if (notice.isNotEmpty()) add(text(a, pal, notice, 13f, muted = true))
                    val row = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
                    row.addView(button(a, pal, a.getString(R.string.update_install), Kind.PRIMARY) {
                        if (!um.canInstall()) { um.openInstallPermissionSettings(); render("available", notice = a.getString(R.string.update_permission)); return@button }
                        render("downloading")
                        Thread {
                            try {
                                val apk = um.download(info, { cancelledFlag.get() }) { pct -> a.runOnUiThread { render("downloading", pct) } }
                                a.runOnUiThread { handoff = true; um.install(apk); render("handoff") }
                            } catch (e: Exception) {
                                um.deleteInstallerFiles()
                                if (!cancelledFlag.get()) a.runOnUiThread { render("error") }
                            }
                        }.start()
                    }, LinearLayout.LayoutParams(-1, -2))
                    val sub = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
                    sub.addView(button(a, pal, a.getString(R.string.update_later)) { p.remindAfter = System.currentTimeMillis() + DAY; d.dismiss() }, LinearLayout.LayoutParams(0, -2, 1f))
                    sub.addView(button(a, pal, a.getString(R.string.update_skip)) { p.skippedVersion = info.version; d.dismiss() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = a.dp(8) })
                    row.addView(sub, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(8) })
                    add(row, 16)
                }
                "downloading" -> {
                    add(text(a, pal, a.getString(R.string.update_downloading), 14f, muted = true), 8)
                    val bar = FrameLayout(a).apply { background = rounded(pal.inset, a.dp(4).toFloat()) }
                    val fill = View(a).apply { background = rounded(pal.accent, a.dp(4).toFloat()); pivotX = 0f; scaleX = progress / 100f }
                    bar.addView(fill, FrameLayout.LayoutParams(-1, a.dp(8)))
                    add(bar, 14)
                    add(button(a, pal, a.getString(android.R.string.cancel)) { cancelledFlag.set(true); um.deleteInstallerFiles(); d.dismiss() }, 16)
                }
                "handoff" -> {
                    add(text(a, pal, a.getString(R.string.update_handoff), 14f, muted = true), 8)
                    add(button(a, pal, a.getString(R.string.close), Kind.PRIMARY) { d.dismiss() }, 16)
                }
                else -> {
                    add(text(a, pal, a.getString(R.string.update_error_body), 14f, muted = true), 8)
                    add(button(a, pal, a.getString(R.string.update_retry), Kind.PRIMARY) { render("available") }, 16)
                    add(button(a, pal, a.getString(R.string.close)) { d.dismiss() }, 8)
                }
            }
        }
        render("available")
        d.setOnShowListener {
            cardView.scaleX = 0.94f; cardView.scaleY = 0.94f; cardView.alpha = 0f
            cardView.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(520).setInterpolator(OvershootInterpolator(1.1f)).start()
        }
        d.setOnDismissListener { cancelledFlag.set(true) }
        d.show()
    }

    /** Set when the APK was handed to the system installer; the file is deleted once the app is back in front. */
    @Volatile var handoff = false
}
