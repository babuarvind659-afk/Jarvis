package com.jarvis.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class Brain(private val ctx: Context, private val sayRaw: (String) -> Unit) {

    private val main = Handler(Looper.getMainLooper())

    private fun say(msg: String) {
        main.post { sayRaw(msg) }
    }

    private val geminiUrl =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent"

    private val aliases = linkedMapOf(
        "व्हाट्सएप" to "whatsapp", "व्हाट्सऐप" to "whatsapp", "वाट्सएप" to "whatsapp",
        "व्हाट्स अप" to "whatsapp", "यूट्यूब" to "youtube", "यू ट्यूब" to "youtube",
        "क्रोम" to "chrome", "कैमरा" to "camera", "इंस्टाग्राम" to "instagram",
        "फेसबुक" to "facebook", "गैलरी" to "gallery", "सेटिंग्स" to "settings",
        "सेटिंग" to "settings", "टेलीग्राम" to "telegram", "स्नैपचैट" to "snapchat",
        "कैलकुलेटर" to "calculator", "जीमेल" to "gmail", "मैप्स" to "maps",
        "फोन" to "phone", "घड़ी" to "clock", "प्ले स्टोर" to "play store",
        "मैसेज" to "messages", "कैलेंडर" to "calendar",
        "स्क्रॉल डाउन" to "scroll down", "स्क्रॉल अप" to "scroll up",
        "बैक" to "back", "होम" to "home", "क्लिक" to "click", "ओपन" to "open"
    )

    private val fillers = listOf(
        "kholo", "खोलो", "खोल", "खोलिए", "open", "chalao", "चलाओ", "चला", "play",
        "search", "सर्च", "karo", "करो", "pe", "par", "पर", "me", "में", "ko", "को",
        "youtube", "यूट्यूब", "ka", "का", "ki", "की", "mujhe", "मुझे", "please", "प्लीज"
    )

    private fun has(t: String, vararg words: String): Boolean = words.any { t.contains(it) }

    private fun clean(t: String): String {
        var s = " $t "
        repeat(2) { for (w in fillers) s = s.replace(" $w ", " ") }
        return s.trim().replace(Regex("\\s+"), " ")
    }

    private fun launch(i: Intent): Boolean {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val c: Context = JarvisService.instance ?: ctx
        return try {
            c.startActivity(i)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun handle(raw: String) {
        var t = raw.lowercase().trim()
        for ((k, v) in aliases) t = t.replace(k, v)
        val svc = JarvisService.instance

        fun needSvc(block: (JarvisService) -> Unit) {
            if (svc == null) say("पहले स्क्रीन कंट्रोल चालू करो") else block(svc)
        }

        when {
            has(t, "youtube") -> {
                val q = clean(t)
                if (q.isEmpty()) {
                    openApp("youtube")
                } else {
                    val uri = Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))
                    val ok = launch(Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.youtube")) ||
                        launch(Intent(Intent.ACTION_VIEW, uri))
                    say(if (ok) "यूट्यूब पर $q" else "यूट्यूब नहीं खुल पाया")
                }
            }
            has(t, "scroll down", "नीचे", "neeche") ->
                needSvc { it.scroll(true); say("नीचे") }
            has(t, "scroll up", "ऊपर", "upar") ->
                needSvc { it.scroll(false); say("ऊपर") }
            has(t, "back", "पीछे", "piche", "peeche", "वापस") ->
                needSvc { it.back(); say("पीछे गया") }
            has(t, "home", "होम") ->
                needSvc { it.home(); say("होम") }
            t.startsWith("click ") || t.startsWith("tap ") || has(t, "दबाओ") -> {
                val target = t.removePrefix("click ").removePrefix("tap ").replace("दबाओ", "").trim()
                needSvc { say(if (it.clickText(target)) "$target दबा दिया" else "$target नहीं मिला") }
            }
            has(t, "kholo", "open", "खोल", "चालू") -> openApp(clean(t))
            else -> askGemini(raw)
        }
    }

    private fun openApp(name: String) {
        if (name.length < 2) {
            say("कौन सा ऐप खोलूँ?")
            return
        }
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
        val match = apps.firstOrNull {
            val label = it.loadLabel(pm).toString().lowercase()
            label.contains(name) || name.contains(label)
        }
        if (match != null) {
            val i = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            if (i != null && launch(i)) {
                say("${match.loadLabel(pm)} खोल दिया")
                return
            }
        }
        say("$name ऐप नहीं मिला")
    }

    private fun askGemini(q: String) {
        val key = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
            .getString("gemini", "")?.trim().orEmpty()
        if (key.isEmpty()) {
            say("मुझे समझ नहीं आया। बात करने के लिए ऐप में जेमिनी की डालो।")
            return
        }
        thread {
            val answer = try {
                val sys = "तुम जार्विस हो, एक हिंदी वॉइस असिस्टेंट। जवाब बहुत छोटा रखो, दो वाक्य तक, " +
                    "सादी हिंदी में। कोई markdown, तारे या emoji मत लगाओ।"
                val body = JSONObject()
                    .put(
                        "system_instruction",
                        JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sys)))
                    )
                    .put(
                        "contents",
                        JSONArray().put(
                            JSONObject().put("role", "user")
                                .put("parts", JSONArray().put(JSONObject().put("text", q)))
                        )
                    )
                val c = URL(geminiUrl).openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 15000
                c.readTimeout = 30000
                c.setRequestProperty("x-goog-api-key", key)
                c.setRequestProperty("Content-Type", "application/json")
                c.doOutput = true
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val resp = stream?.bufferedReader()?.readText() ?: ""
                if (code !in 200..299) {
                    "जेमिनी में एरर $code"
                } else {
                    JSONObject(resp).getJSONArray("candidates").getJSONObject(0)
                        .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
                }
            } catch (e: Exception) {
                "इंटरनेट या की में दिक्कत है"
            }
            say(answer.replace("*", "").replace("#", "").take(400))
        }
    }
}
