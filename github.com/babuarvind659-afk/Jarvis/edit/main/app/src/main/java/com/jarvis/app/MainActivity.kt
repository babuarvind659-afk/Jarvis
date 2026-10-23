package com.jarvis.app

import android.Manifest
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var keyBox: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("jarvis", Context.MODE_PRIVATE)

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 80, 40, 40)
        }
        scroll.addView(root)
        setContentView(scroll)

        status = TextView(this).apply { textSize = 18f }
        root.addView(status)

        keyBox = EditText(this).apply {
            hint = "Gemini API key yaha paste karo"
            setSingleLine(true)
            setText(prefs.getString("gemini", ""))
        }
        root.addView(keyBox)

        addBtn(root, "Key save karo") {
            prefs.edit().putString("gemini", keyBox.text.toString().trim()).apply()
            status.text = "Key save ho gayi"
        }
        addBtn(root, "JARVIS START (hamesha sunna)") { startJarvis() }
        addBtn(root, "JARVIS STOP") {
            stopService(Intent(this, ListenService::class.java))
            refresh()
        }
        addBtn(root, "1. Screen control ON karo (Accessibility)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        addBtn(root, "2. Display over other apps allow karo") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        addBtn(root, "3. Battery: No restrictions karo") {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        addBtn(root, "Jarvis wallpaper lagao") {
            try {
                val i = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                i.putExtra(
                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(this, JarvisWallpaper::class.java)
                )
                startActivity(i)
            } catch (e: Exception) {
                startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun addBtn(parent: LinearLayout, label: String, onClick: () -> Unit) {
        parent.addView(Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        })
    }

    private fun refresh() {
        status.text = if (ListenService.running)
            "Jarvis ON hai. \"जार्विस\" bolo, phir command."
        else
            "Jarvis band hai. START dabao."
    }

    private fun startJarvis() {
        val need = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.POST_NOTIFICATIONS)

        if (need.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, need.toTypedArray(), 1)
            status.text = "Permission Allow karo, phir START dobara dabao"
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, ListenService::class.java))
        status.text = "Jarvis chalu ho raha hai..."
    }
}
