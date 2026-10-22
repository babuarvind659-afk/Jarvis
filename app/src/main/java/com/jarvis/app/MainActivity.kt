package com.jarvis.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private val CLAUDE_API_KEY = ""

    private lateinit var tts: TextToSpeech
    private lateinit var status: TextView

    private val speechLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == Activity.RESULT_OK) {
                val text = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                if (!text.isNullOrBlank()) {
                    status.text = "Aapne kaha: $text"
                    handle(text)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        status = TextView(this).apply { text = "Jarvis ready. Mic dabao aur bolo."; textSize = 18f }
        val micBtn = Button(this).apply { text = "Bolo" }
        val accBtn = Button(this).apply { text = "Screen control ON karo (Accessibility)" }
        root.addView(status); root.addView(micBtn); root.addView(accBtn)
        setContentView(root)

        tts = TextToSpeech(this) { if (it == TextToSpeech.SUCCESS) tts.language = Locale("hi", "IN") }

        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)

        micBtn.setOnClickListener { listen() }
        accBtn.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }

    private fun listen() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Boliye...")
        }
        try { speechLauncher.launch(i) } catch (e: Exception) { say("Voice recognition is phone me nahi mila") }
    }

    private fun say(msg: String) {
        status.text = msg
        tts.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
    }

    private val aliases = mapOf(
        "व्हाट्सएप" to "whatsapp", "व्हाट्सऐप" to "whatsapp", "यूट्यूब" to "youtube",
        "क्रोम" to "chrome", "कैमरा" to "camera", "इंस्टाग्राम" to "instagram",
        "गैलरी" to "gallery", "सेटिंग" to "settings", "सेटिंग्स" to "settings"
    )
    private val fillerWords = listOf(
        "kholo", "खोलो", "खोल", "open", "chalao", "चलाओ", "play", "search", "सर्च",
        "karo", "करो", "pe", "par", "पर", "me", "में", "ko", "को", "youtube", "यूट्यूब"
    )

    private fun clean(t: String): String {
        var s = t
        for (w in fillerWords) s = s.replace(Regex("(^|\\s)" + Regex.escape(w) + "(\\s|$)"), " ")
        return s.trim()
    }

    private fun handle(raw: String) {
        var t = raw.lowercase().trim()
        for ((k, v) in aliases) t = t.replace(k, v)
        val svc = JarvisService.instance

        fun needService(block: (JarvisService) -> Unit) {
            if (svc == null) say("Pehle Screen control ON karo") else block(svc)
        }

        when {
            t.contains("youtube") || raw.contains("यूट्यूब") -> {
                val q = clean(t)
                if (q.isEmpty()) openApp("youtube")
                else {
                    val uri = Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))
                    val i = Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.youtube")
                    try { startActivity(i); say("YouTube pe $q") }
                    catch (e: Exception) { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                }
            }
            t.contains("back") || t.contains("piche") || t.contains("पीछे") ->
                needService { it.back(); say("Peeche gaya") }
            t.contains("home") || t.contains("होम") ->
                needService { it.home(); say("Home") }
            t.contains("scroll down") || t.contains("neeche") || t.contains("नीचे") ->
                needService { it.scroll(true); say("Neeche") }
            t.contains("scroll up") || t.contains("upar") || t.contains("ऊपर") ->
                needService { it.scroll(false); say("Upar") }
            t.startsWith("click ") || t.startsWith("tap ") || t.contains("दबाओ") -> {
                val target = t.removePrefix("click ").removePrefix("tap ").replace("दबाओ", "").trim()
                needService { say(if (it.clickText(target)) "$target dabaya" else "$target nahi mila") }
            }
            t.contains("kholo") || t.contains("open") || t.contains("खोल") -> openApp(clean(t))
            else -> askClaude(raw)
        }
    }

    private fun openApp(name: String) {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
        val match = apps.firstOrNull {
            val label = it.loadLabel(pm).toString().lowercase()
            label.contains(name) || (name.isNotEmpty() && name.contains(label))
        }
        if (match != null) {
            val i = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            if (i != null) { startActivity(i); say("${match.loadLabel(pm)} khol diya"); return }
        }
        say("'$name' app nahi mila")
    }

    private fun askClaude(question: String) {
        if (CLAUDE_API_KEY.isBlank()) { say("Samajh nahi aaya. Claude se baat ke liye API key daalo."); return }
        status.text = "Soch raha hu..."
        thread {
            val answer = try {
                val body = JSONObject().apply {
                    put("model", "claude-sonnet-5-5")
                    put("max_tokens", 300)
                    put("system", "Tum Jarvis ho, ek Hinglish voice assistant. Chote jawab do.")
                    put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", question)))
                }
                val c = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.setRequestProperty("x-api-key", CLAUDE_API_KEY)
                c.setRequestProperty("anthropic-version", "2023-06-01")
                c.setRequestProperty("content-type", "application/json")
                c.doOutput = true
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                val resp = c.inputStream.bufferedReader().readText()
                JSONObject(resp).getJSONArray("content").getJSONObject(0).getString("text")
            } catch (e: Exception) { "Claude se connect nahi hua: ${e.message}" }
            runOnUiThread { say(answer) }
        }
    }

    override fun onDestroy() { tts.shutdown(); super.onDestroy() }
}
