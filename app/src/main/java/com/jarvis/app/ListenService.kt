package com.jarvis.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.util.Locale

class ListenService : Service() {

    companion object {
        var running = false
        private const val CH = "jarvis_listen"
        private val wakeWords = listOf(
            "jarvis", "जार्विस", "जारविस", "जार्वीस", "जर्विस", "जार्विश",
            "जरविस", "जारवीस", "ज़ार्विस", "जार्विज", "जारविज"
        )
        private val fillerWords = listOf("hey", "ok", "okay", "हे", "ओके", "अरे")
    }

    private val main = Handler(Looper.getMainLooper())
    private var rec: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speaking = false
    private var stopped = false
    private var awaitingUntil = 0L
    private lateinit var brain: Brain

    private val startRunnable = Runnable { startListening() }

    private var lowAlerted = false
    private var fullAlerted = false

    // Dost ki tarah apne aap batata hai (battery low / full)
    private val friendCheck = object : Runnable {
        override fun run() {
            if (stopped) return
            if (!speaking) {
                val (pct, charging) = PhoneInfo.battery(this@ListenService)
                if (pct in 0..20 && !charging && !lowAlerted) {
                    lowAlerted = true
                    speak("दोस्त, बैटरी सिर्फ $pct प्रतिशत बची है। चार्जर लगा दो।")
                } else if (pct > 30) {
                    lowAlerted = false
                }
                if (pct >= 100 && charging && !fullAlerted) {
                    fullAlerted = true
                    speak("बैटरी फुल हो गई है, चार्जर निकाल दो।")
                } else if (pct in 0..94) {
                    fullAlerted = false
                }
            }
            main.postDelayed(this, 60000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH, "Jarvis", NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(
            this, 1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )

        brain = Brain(this) { speak(it) }

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("hi", "IN")
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        main.post { speakingDone() }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        main.post { speakingDone() }
                    }
                })
                ttsReady = true
            }
            main.post {
                speak("जार्विस चालू है दोस्त। बोलो क्या करना है?")
                main.postDelayed(friendCheck, 60000)
            }
        }
    }

    override fun onDestroy() {
        stopped = true
        running = false
        main.removeCallbacksAndMessages(null)
        try {
            rec?.destroy()
        } catch (e: Exception) {
        }
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val stopPi = PendingIntent.getService(
            this, 0, Intent(this, ListenService::class.java).setAction("STOP"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openPi = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Jarvis सुन रहा है")
            .setContentText("\"जार्विस\" बोलकर जगाओ")
            .setContentIntent(openPi)
            .addAction(0, "बंद करो", stopPi)
            .setOngoing(true)
            .build()
    }

    private fun schedule(ms: Long) {
        main.removeCallbacks(startRunnable)
        main.postDelayed(startRunnable, ms)
    }

    private fun speakingDone() {
        speaking = false
        schedule(400)
    }

    private fun speak(text: String) {
        speaking = true
        try {
            rec?.cancel()
        } catch (e: Exception) {
        }
        if (ttsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u" + System.nanoTime())
        } else {
            speakingDone()
        }
    }

    private fun resetRecognizer() {
        try {
            rec?.destroy()
        } catch (e: Exception) {
        }
        rec = null
    }

    private fun startListening() {
        if (stopped || speaking) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            schedule(5000)
            return
        }
        if (rec == null) {
            rec = SpeechRecognizer.createSpeechRecognizer(this)
            rec?.setRecognitionListener(listener)
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        try {
            rec?.startListening(i)
        } catch (e: Exception) {
            resetRecognizer()
            schedule(2000)
        }
    }

    private fun hasWake(s: String): Boolean {
        val t = s.lowercase()
        return wakeWords.any { t.contains(it) }
    }

    private fun stripWake(s: String): String {
        var t = " " + s.lowercase() + " "
        for (w in wakeWords) t = t.replace(w, " ")
        repeat(2) { for (f in fillerWords) t = t.replace(" $f ", " ") }
        return t.trim().replace(Regex("\\s+"), " ")
    }

    private fun route(cmd: String) {
        val info = PhoneInfo.reply(this, cmd)
        if (info != null) speak(info) else brain.handle(cmd)
    }

    private fun process(list: List<String>) {
        val now = System.currentTimeMillis()
        val wakeText = list.firstOrNull { hasWake(it) }
        if (wakeText != null) {
            val cmd = stripWake(wakeText)
            if (cmd.isBlank()) {
                awaitingUntil = now + 15000
                speak("जी बोलिए")
            } else {
                awaitingUntil = 0
                route(cmd)
            }
        } else if (now < awaitingUntil && list.isNotEmpty()) {
            awaitingUntil = 0
            route(list[0])
        } else {
            schedule(100)
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> schedule(200)
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> stopSelf()
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    resetRecognizer()
                    schedule(1500)
                }
                else -> {
                    resetRecognizer()
                    schedule(2000)
                }
            }
        }

        override fun onResults(results: Bundle?) {
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
            process(list)
        }
    }
}
