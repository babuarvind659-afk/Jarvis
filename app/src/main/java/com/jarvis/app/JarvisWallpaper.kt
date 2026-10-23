package com.jarvis.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class JarvisWallpaper : WallpaperService() {

    override fun onCreateEngine(): Engine = HudEngine()

    inner class HudEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private var visible = false
        private var t = 0f
        private val cyan = 0xFF00E5FF.toInt()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        private var boy: Bitmap? = null

        private val tick = object : Runnable {
            override fun run() {
                draw()
                if (visible) handler.postDelayed(this, 33)
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            // Agar res/drawable-nodpi/boy.png daali ho to wo wallpaper me dikhegi
            val id = resources.getIdentifier("boy", "drawable", packageName)
            if (id != 0) {
                try {
                    boy = BitmapFactory.decodeResource(resources, id)
                } catch (e: Exception) {
                }
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            handler.removeCallbacks(tick)
            if (visible) handler.post(tick)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            handler.removeCallbacks(tick)
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            visible = false
            handler.removeCallbacks(tick)
            super.onDestroy()
        }

        private fun draw() {
            val holder = surfaceHolder
            var c: Canvas? = null
            try {
                c = holder.lockCanvas()
                if (c != null) render(c)
            } catch (e: Exception) {
            } finally {
                if (c != null) {
                    try {
                        holder.unlockCanvasAndPost(c)
                    } catch (e: Exception) {
                    }
                }
            }
        }

        private fun render(c: Canvas) {
            t += 1f
            val w = c.width.toFloat()
            val h = c.height.toFloat()
            val cx = w / 2f
            val cy = h * 0.38f

            // Background
            paint.reset()
            paint.isAntiAlias = true
            paint.shader = RadialGradient(
                cx, cy, h * 0.8f, 0xFF06283D.toInt(), 0xFF01040A.toInt(), Shader.TileMode.CLAMP
            )
            c.drawRect(0f, 0f, w, h, paint)
            paint.shader = null

            // Moving grid
            paint.color = 0x2200E5FF
            paint.strokeWidth = 1.5f
            val step = 80f
            val off = (t * 0.6f) % step
            var x = -step + off
            while (x < w + step) {
                c.drawLine(x, 0f, x, h, paint)
                x += step
            }
            var y = -step + off
            while (y < h + step) {
                c.drawLine(0f, y, w, y, paint)
                y += step
            }

            // Optional image (boy.png)
            val b = boy
            if (b != null) {
                val scale = (w * 0.95f) / b.width
                val bw = b.width * scale
                val bh = b.height * scale
                val dst = RectF(cx - bw / 2f, h - bh, cx + bw / 2f, h)
                paint.alpha = 235
                c.drawBitmap(b, null, dst, paint)
                paint.alpha = 255
            }

            // Arc reactor rings
            val base = w * 0.36f
            paint.style = Paint.Style.STROKE
            paint.color = cyan

            paint.strokeWidth = 6f
            c.drawArc(RectF(cx - base, cy - base, cx + base, cy + base), t * 1.2f, 250f, false, paint)

            val r2 = base * 0.84f
            paint.strokeWidth = 10f
            paint.color = 0xCC00B8D4.toInt()
            c.drawArc(RectF(cx - r2, cy - r2, cx + r2, cy + r2), -t * 1.8f, 120f, false, paint)
            c.drawArc(RectF(cx - r2, cy - r2, cx + r2, cy + r2), -t * 1.8f + 180f, 90f, false, paint)

            val r3 = base * 0.66f
            paint.strokeWidth = 3f
            paint.color = cyan
            c.drawCircle(cx, cy, r3, paint)

            // Tick marks on r3
            c.save()
            c.rotate(t * 0.8f, cx, cy)
            paint.strokeWidth = 3f
            for (i in 0 until 60) {
                val len = if (i % 5 == 0) 26f else 12f
                c.drawLine(cx, cy - r3, cx, cy - r3 + len, paint)
                c.rotate(6f, cx, cy)
            }
            c.restore()

            val r4 = base * 0.42f
            paint.strokeWidth = 5f
            paint.color = 0xFF00E5FF.toInt()
            c.drawArc(RectF(cx - r4, cy - r4, cx + r4, cy + r4), t * 3f, 70f, false, paint)
            c.drawArc(RectF(cx - r4, cy - r4, cx + r4, cy + r4), t * 3f + 120f, 70f, false, paint)
            c.drawArc(RectF(cx - r4, cy - r4, cx + r4, cy + r4), t * 3f + 240f, 70f, false, paint)

            // Pulsing core
            val pulse = 0.5f + 0.5f * Math.sin((t * 0.08f).toDouble()).toFloat()
            paint.style = Paint.Style.FILL
            paint.color = 0xFFFFFFFF.toInt()
            paint.alpha = (140 + 115 * pulse).toInt()
            c.drawCircle(cx, cy, base * 0.14f + base * 0.03f * pulse, paint)
            paint.alpha = 255

            // Text
            paint.typeface = Typeface.MONOSPACE
            paint.textAlign = Paint.Align.CENTER
            paint.color = cyan
            paint.textSize = w * 0.075f
            paint.letterSpacing = 0.3f
            c.drawText("J.A.R.V.I.S", cx, h * 0.10f, paint)

            paint.textSize = w * 0.16f
            paint.letterSpacing = 0.05f
            paint.color = 0xFFFFFFFF.toInt()
            c.drawText(timeFmt.format(Date()), cx, h * 0.10f + w * 0.2f, paint)

            paint.textSize = w * 0.04f
            paint.letterSpacing = 0.25f
            paint.color = cyan
            c.drawText("SYSTEM ONLINE", cx, h * 0.93f, paint)
        }
    }
}
