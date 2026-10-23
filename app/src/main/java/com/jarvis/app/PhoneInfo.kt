package com.jarvis.app

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PhoneInfo {

    // (percent, charging)
    fun battery(ctx: Context): Pair<Int, Boolean> {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        return Pair(pct, charging)
    }

    private fun fmt(d: Double): String = String.format(Locale.US, "%.1f", d)

    private fun batteryText(ctx: Context): String {
        val (pct, charging) = battery(ctx)
        if (pct < 0) return "बैटरी का पता नहीं चल पाया"
        var s = "बैटरी $pct प्रतिशत है"
        if (charging) s += " और चार्ज हो रही है"
        else if (pct <= 20) s += "। दोस्त, चार्जर लगा लो"
        return s
    }

    private fun storageText(): String {
        val st = StatFs(Environment.getDataDirectory().path)
        val free = st.availableBytes / 1e9
        val total = st.totalBytes / 1e9
        return "स्टोरेज में ${fmt(free)} जीबी खाली है, कुल ${fmt(total)} जीबी में से"
    }

    private fun ramText(ctx: Context): String {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return "रैम में ${fmt(mi.availMem / 1e9)} जीबी खाली है, कुल ${fmt(mi.totalMem / 1e9)} जीबी में से"
    }

    private fun currentApp(ctx: Context): String {
        val pkg = JarvisService.instance?.rootInActiveWindow?.packageName?.toString()
            ?: return "स्क्रीन कंट्रोल चालू नहीं है, इसलिए मैं देख नहीं पा रहा"
        val label = try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
        return "अभी $label खुला है"
    }

    // Phone ke baare me sawal ho to jawab deta hai, warna null (tab Brain kaam karega)
    fun reply(ctx: Context, raw: String): String? {
        val t = raw.lowercase().trim()
        if (t.split(" ").size > 7) return null

        fun has(vararg w: String) = w.any { t.contains(it) }

        // Ye words ho to ye app kholne / click wala command hai
        if (has("youtube", "यूट्यूब", "kholo", "खोलो", "खोल", "open", "ओपन", "click", "क्लिक")) return null

        return when {
            has("फोन का हाल", "फोन की हालत", "phone ka haal", "phone status", "फोन स्टेटस", "स्टेटस") ->
                batteryText(ctx) + "। " + storageText() + "। " + ramText(ctx)
            has("battery", "बैटरी", "बैट्री") -> batteryText(ctx)
            has("storage", "स्टोरेज", "खाली जगह") -> storageText()
            has("ram", "रैम", "रेम") -> ramText(ctx)
            has("कितने बजे", "टाइम", "समय") ->
                "अभी " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date()) + " बजे हैं"
            has("तारीख", "आज की डेट", "आज की date") ->
                "आज " + SimpleDateFormat("d MMMM, EEEE", Locale("hi", "IN")).format(Date()) + " है"
            has("कौन सा ऐप", "which app", "क्या चल रहा") -> currentApp(ctx)
            else -> null
        }
    }
}
