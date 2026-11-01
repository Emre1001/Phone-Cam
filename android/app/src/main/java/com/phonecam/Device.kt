package com.phonecam

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/** Chip family. Encoders of one family behave alike, whichever phone they sit in. */
enum class Chip(val label: String) {
    QUALCOMM("Snapdragon"), MEDIATEK("Dimensity / MediaTek"), EXYNOS("Exynos"), TENSOR("Google Tensor"), OTHER("");
}

/**
 * What the app knows about this phone: a readable name for the PC, the chip family, and the encoder tuning
 * that suits it. Tuning follows the chip and its encoder, so phones that are not listed get the right
 * settings too; the list below only gives them a readable name.
 */
object Device {
    private val maker = Build.MANUFACTURER.orEmpty().lowercase()
    private val brand = Build.BRAND.orEmpty().lowercase()

    val isSamsung get() = maker == "samsung"
    val isXiaomi get() = maker == "xiaomi" || brand in setOf("xiaomi", "redmi", "poco")
    val isOppoFamily get() = maker in setOf("oneplus", "oppo", "realme")
    val isHuawei get() = maker in setOf("huawei", "honor")

    /** Build.MODEL prefix to marketing name, for models whose MODEL is only a code. */
    private val KNOWN = listOf(
        // Samsung Galaxy S
        "SM-S948" to "Galaxy S26 Ultra", "SM-S947" to "Galaxy S26+", "SM-S942" to "Galaxy S26",
        "SM-S938" to "Galaxy S25 Ultra", "SM-S937" to "Galaxy S25 Edge", "SM-S936" to "Galaxy S25+", "SM-S931" to "Galaxy S25",
        "SM-S928" to "Galaxy S24 Ultra", "SM-S926" to "Galaxy S24+", "SM-S921" to "Galaxy S24", "SM-S721" to "Galaxy S24 FE",
        "SM-S918" to "Galaxy S23 Ultra", "SM-S916" to "Galaxy S23+", "SM-S911" to "Galaxy S23", "SM-S711" to "Galaxy S23 FE",
        "SM-S908" to "Galaxy S22 Ultra", "SM-S906" to "Galaxy S22+", "SM-S901" to "Galaxy S22",
        // Samsung Galaxy Z and A
        "SM-F966" to "Galaxy Z Fold7", "SM-F766" to "Galaxy Z Flip7", "SM-F956" to "Galaxy Z Fold6", "SM-F741" to "Galaxy Z Flip6",
        "SM-F946" to "Galaxy Z Fold5", "SM-F731" to "Galaxy Z Flip5",
        "SM-A566" to "Galaxy A56", "SM-A556" to "Galaxy A55", "SM-A546" to "Galaxy A54", "SM-A356" to "Galaxy A35",
        // Xiaomi
        "2407FPN8E" to "Xiaomi 14T Pro", "24072PX77" to "Xiaomi 14T",
        "23078PND5" to "Xiaomi 13T Pro", "2306EPN60" to "Xiaomi 13T",
        "24030PN60" to "Xiaomi 14 Ultra", "23127PN0C" to "Xiaomi 14",
        "2210132" to "Xiaomi 13 Pro", "2211133" to "Xiaomi 13",
    )

    /** Name shown in the PC app: marketing name, else the name the user gave the phone, else the model. */
    fun name(ctx: Context): String {
        val model = Build.MODEL.orEmpty()
        KNOWN.firstOrNull { model.uppercase().startsWith(it.first) }?.let { return it.second }
        val given = try { Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME) } catch (_: Exception) { null }
        return given?.trim()?.takeIf { it.isNotEmpty() } ?: model.ifEmpty { "Android" }
    }

    val chip: Chip by lazy {
        val soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}".lowercase() else ""
        val hw = "${Build.HARDWARE} ${Build.BOARD}".lowercase()
        when {
            "qti" in soc || "qualcomm" in soc || hw.startsWith("qcom") || "kalama" in hw || "pineapple" in hw || "sun" == Build.BOARD.lowercase() -> Chip.QUALCOMM
            "mediatek" in soc || Regex("\\bmt\\d{4}").containsMatchIn(hw) || Regex("\\bmt\\d{4}").containsMatchIn(soc) -> Chip.MEDIATEK
            "google" in soc || hw.startsWith("gs") || "zuma" in hw || "ripcurrent" in hw -> Chip.TENSOR
            "samsung" in soc || "exynos" in hw || hw.startsWith("s5e") || hw.startsWith("universal") -> Chip.EXYNOS
            else -> Chip.OTHER
        }
    }

    /** HEVC gives more picture per bit (good for Wi-Fi). Unknown chips get H.264, whose encoders are the most reliable. */
    val preferHevc get() = chip != Chip.OTHER

    /** Encoder settings for one encoder, picked by its name (c2.qti.*, c2.mtk.*, c2.exynos.*, ...). */
    class Tuning(
        /** Seconds between regular key frames. */
        val keyFrameSec: Int,
        /** Vendor-specific MediaFormat keys; dropped automatically if the encoder refuses them. */
        val vendorKeys: Map<String, Int>,
    )

    fun tuning(encoder: String): Tuning {
        val n = encoder.lowercase()
        return when {
            // Qualcomm (Galaxy S22-S26 Ultra, OnePlus, most Xiaomi flagships): its own low-latency switch.
            n.startsWith("c2.qti") || n.startsWith("omx.qcom") -> Tuning(2, mapOf("vendor.qti-ext-enc-low-latency.enable" to 1))
            // MediaTek (Xiaomi 13T/14T Pro, Redmi, Poco): key frame requests are not always honoured at once,
            // so a regular key frame comes every second and a lost frame heals quickly.
            n.startsWith("c2.mtk") || n.startsWith("omx.mtk") -> Tuning(1, emptyMap())
            // Exynos and Tensor (Galaxy S24/S24+ in Europe, Pixel).
            else -> Tuning(2, emptyMap())
        }
    }

    /** "Galaxy S24 Ultra · Snapdragon · c2.qti.hevc.encoder" for the settings page. */
    fun summary(ctx: Context, encoder: String?): String =
        listOf(name(ctx), chip.label, encoder.orEmpty()).filter { it.isNotEmpty() }.joinToString(" · ")

    /* ---------- Staying alive with the screen off ---------- */

    fun ignoresBatteryOptimizations(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) ?: true

    /** System dialog: exempt this app from battery optimisation (Samsung "Unrestricted", Xiaomi "No restrictions"). */
    fun batteryIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))

    /** HyperOS / MIUI kill background apps without "Autostart"; this opens that list. Null on other phones. */
    fun autostartIntent(ctx: Context): Intent? {
        if (!isXiaomi) return null
        val i = Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
        return i.takeIf { it.resolveActivity(ctx.packageManager) != null }
    }

    /** App info page, the fallback when a maker's own page is missing. */
    fun appInfoIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
}
