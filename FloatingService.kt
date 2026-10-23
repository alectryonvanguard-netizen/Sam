package com.nexa.ai

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.view.*
import android.widget.*

private const val C1 = 0xFF22D3EE.toInt()
private const val C2 = 0xFF3B82F6.toInt()
private const val C3 = 0xFF8B5CF6.toInt()
private const val C4 = 0xFFEC4899.toInt()

private class LogoView(c: Context) : View(c) {
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 7f }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC); textAlign = Paint.Align.CENTER
    }
    private val logo: Bitmap? = run {
        val id = c.resources.getIdentifier("nexa_logo", "drawable", c.packageName)
        if (id != 0) BitmapFactory.decodeResource(c.resources, id) else null
    }
    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        logo?.let { c.drawBitmap(it, null, RectF(4f, 4f, w - 4f, w - 4f), null); return }
        fill.shader = RadialGradient(w / 2, w / 2, w / 2, intArrayOf(0xFF1E1259.toInt(), 0xFF060A24.toInt()), null, Shader.TileMode.CLAMP)
        c.drawCircle(w / 2, w / 2, w / 2 - 6, fill)
        ring.shader = LinearGradient(0f, 0f, w, w, intArrayOf(C1, C3, C4), null, Shader.TileMode.CLAMP)
        c.drawCircle(w / 2, w / 2, w / 2 - 6, ring)
        txt.textSize = w * .55f
        txt.shader = LinearGradient(0f, 0f, w, w, intArrayOf(C1, C2, C3), null, Shader.TileMode.CLAMP)
        c.drawText("N", w / 2, w * .68f, txt)
    }
}

class FloatingService : Service() {
    private lateinit var wm: WindowManager
    private var root: LinearLayout? = null
    override fun onBind(i: Intent?): IBinder? = null

    private fun grad(r: Float, a: Int, b: Int, stroke: Boolean = true) = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(a, b)).apply {
        cornerRadius = r; if (stroke) setStroke(2, 0x8822D3EE.toInt())
    }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(16); y = dp(220) }

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // menu panel
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = grad(dp(18).toFloat(), 0xF2101A48.toInt(), 0xF2220F5C.toInt())
        }
        listOf(
            Triple("Ask", android.R.drawable.ic_menu_help, "ask"),
            Triple("Voice", android.R.drawable.ic_btn_speak_now, "voice"),
            Triple("Image", android.R.drawable.ic_menu_gallery, "image"),
            Triple("Open App", android.R.drawable.ic_menu_view, "home")
        ).forEach { (label, icon, dest) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(10), dp(24), dp(10))
                setOnClickListener {
                    menu.visibility = View.GONE
                    startActivity(Intent(this@FloatingService, MainActivity::class.java)
                        .putExtra("dest", dest).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                }
            }
            row.addView(ImageView(this).apply {
                setImageResource(icon); setColorFilter(C1); setPadding(dp(6), dp(6), dp(6), dp(6))
                background = grad(dp(10).toFloat(), 0x5522D3EE, 0x558B5CF6)
                layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
            })
            row.addView(TextView(this).apply { text = label; setTextColor(Color.WHITE); textSize = 15f; setPadding(dp(14), 0, 0, 0) })
            menu.addView(row)
        }

        // top row: bubble + tooltip + close
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val bubble = LogoView(this).apply { layoutParams = LinearLayout.LayoutParams(dp(60), dp(60)) }
        val tip = TextView(this).apply {
            text = "How can I help you?"; setTextColor(Color.WHITE); textSize = 13f
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = grad(dp(16).toFloat(), 0xF2101A48.toInt(), 0xF2220F5C.toInt())
        }
        val close = TextView(this).apply {
            text = "✕"; setTextColor(Color.WHITE); textSize = 12f; gravity = Gravity.CENTER
            background = grad(dp(20).toFloat(), 0xFF1E2A66.toInt(), 0xFF3B1E7A.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { leftMargin = dp(6) }
            setOnClickListener { stopSelf() }
        }
        top.addView(bubble); top.addView(tip); top.addView(close)

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
        col.addView(top); col.addView(menu, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(40); topMargin = dp(6) })
        root = col
        wm.addView(col, lp)
    }

    override fun onDestroy() {
        root?.let { wm.removeView(it) }
        Store.floating = false
        super.onDestroy()
    }
}
