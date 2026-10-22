package com.nexa.ai

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.view.*
import android.widget.LinearLayout
import android.widget.TextView

class FloatingService : Service() {
    private lateinit var wm: WindowManager
    private var root: LinearLayout? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 20; y = 400 }

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            setPadding(20, 20, 20, 20)
            background = GradientDrawable().apply { cornerRadius = 40f; setColor(0xEE111A3A.toInt()); setStroke(2, 0xFF26305A.toInt()) }
        }
        listOf("Ask" to "ask", "Voice" to "voice", "Image" to "image", "Open App" to "home", "Close" to "close").forEach { (label, dest) ->
            menu.addView(TextView(this).apply {
                text = label; setTextColor(Color.WHITE); textSize = 16f; setPadding(30, 22, 30, 22)
                setOnClickListener {
                    menu.visibility = View.GONE
                    if (dest == "close") { stopSelf(); return@setOnClickListener }
                    startActivity(Intent(this@FloatingService, MainActivity::class.java)
                        .putExtra("dest", dest).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                }
            })
        }
        val bubble = TextView(this).apply {
            text = "N"; textSize = 26f; setTextColor(0xFF22D3EE.toInt()); gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD_ITALIC); width = 150; height = 150
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF0B1230.toInt()); setStroke(5, 0xFF8B5CF6.toInt()) }
        }
        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f; var moved = false
        bubble.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - tx).toInt(); val dy = (e.rawY - ty).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) moved = true
                    lp.x = sx + dx; lp.y = sy + dy; wm.updateViewLayout(col, lp)
                }
                MotionEvent.ACTION_UP -> if (!moved) menu.visibility = if (menu.visibility == View.GONE) View.VISIBLE else View.GONE
            }
            true
        }
        col.addView(bubble); col.addView(menu)
        root = col
        wm.addView(col, lp)
    }

    override fun onDestroy() {
        root?.let { wm.removeView(it) }
        super.onDestroy()
    }
}
