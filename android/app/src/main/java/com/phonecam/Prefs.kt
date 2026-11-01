package com.phonecam

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import java.util.Locale

class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.applicationContext.getSharedPreferences("phonecam", Context.MODE_PRIVATE)

    var mode: String get() = sp.getString("mode", "usb")!!; set(v) = put("mode", v)            // usb | wifi | both
    var host: String get() = sp.getString("host", "")!!; set(v) = put("host", v)
    var pin: String get() = sp.getString("pin", "")!!; set(v) = put("pin", v)
    var front: Boolean get() = sp.getBoolean("front", false); set(v) = put("front", v)
    var maxHeight: Int get() = sp.getInt("maxHeight", 0); set(v) = put("maxHeight", v)        // 0 = auto
    var maxFps: Int get() = sp.getInt("maxFps", 0); set(v) = put("maxFps", v)                  // 0 = auto
    var codec: String get() = sp.getString("codec", "auto")!!; set(v) = put("codec", v)        // auto | h264 | hevc
    var stabilize: Boolean get() = sp.getBoolean("stabilize", false); set(v) = put("stabilize", v)    // electronic stabilisation
    var bitrateMbit: Int get() = sp.getInt("bitrateMbit", 0); set(v) = put("bitrateMbit", v)   // 0 = auto
    var theme: String get() = sp.getString("theme", "system")!!; set(v) = put("theme", v)      // system | light | dark
    var lang: String get() = sp.getString("lang", "system")!!; set(v) = put("lang", v)         // system | de | en | tr
    var skippedVersion: String get() = sp.getString("skipped", "")!!; set(v) = put("skipped", v)
    var remindAfter: Long get() = sp.getLong("remindAfter", 0); set(v) = put("remindAfter", v)
    var autoUpdate: Boolean get() = sp.getBoolean("autoUpdate", true); set(v) = put("autoUpdate", v)
    var lastCheck: Long get() = sp.getLong("lastCheck", 0); set(v) = put("lastCheck", v)

    /** PC identity keys the user confirmed (or that came over USB). Stored as SHA-256 hex. */
    fun isTrusted(key: ByteArray) = Crypto.hex(Crypto.sha256(key)) in sp.getStringSet("trusted", emptySet())!!
    fun trust(key: ByteArray) = sp.edit().putStringSet("trusted", sp.getStringSet("trusted", emptySet())!! + Crypto.hex(Crypto.sha256(key))).apply()

    private fun put(k: String, v: Any) = sp.edit().apply {
        when (v) {
            is String -> putString(k, v)
            is Boolean -> putBoolean(k, v)
            is Int -> putInt(k, v)
            is Long -> putLong(k, v)
        }
    }.apply()

    companion object {
        val LANGUAGES = listOf("de" to "Deutsch", "en" to "English", "tr" to "Türkçe")

        /** Applies the in-app language choice to a context (activity and service use this). */
        fun wrap(ctx: Context): Context {
            val lang = Prefs(ctx).lang
            if (lang == "system") return ctx
            val cfg = Configuration(ctx.resources.configuration)
            cfg.setLocale(Locale.forLanguageTag(lang))
            return ctx.createConfigurationContext(cfg)
        }
    }
}
