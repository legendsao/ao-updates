package com.ranking.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class FloatingService : Service() {

    companion object {
        @Volatile
        var running = false
    }

    private lateinit var wm: WindowManager
    private lateinit var root: LinearLayout
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var panel: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var amount: EditText
    private lateinit var sumBtn: Button
    private var selectedId: String? = null
    private var players: List<Player> = emptyList()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        startForegroundNotification()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        buildViews()
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = dp(120)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }
        wm.addView(root, params)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        running = false
        if (::root.isInitialized) runCatching { wm.removeView(root) }
        super.onDestroy()
    }

    private fun startForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("floating", "Ventana flotante", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, "floating")
            .setContentTitle("Ranking")
            .setContentText("Ventana flotante activa")
            .setSmallIcon(android.R.drawable.ic_input_add)
            .setOngoing(true)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun bg(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun buildViews() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val bubble = TextView(this).apply {
            text = "🏆"
            textSize = 22f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#6A1B9A"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
        }
        root.addView(bubble)

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(Color.parseColor("#F2FFFFFF"), 12)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(300), LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(6) }
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            addView(list)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(260))
        }
        panel.addView(scroll)

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        amount = EditText(this).apply {
            hint = "Cantidad"
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_ACTION_DONE
            setTextColor(Color.BLACK)
            setHintTextColor(Color.GRAY)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                    wm.updateViewLayout(root, params)
                    v.post {
                        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                            .showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
                    }
                } else {
                    params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    wm.updateViewLayout(root, params)
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                        .hideSoftInputFromWindow(v.windowToken, 0)
                }
            }
            setOnEditorActionListener { v, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) { v.clearFocus(); true } else false
            }
        }
        sumBtn = Button(this).apply {
            text = "Sumar"
            setOnClickListener { addCustom() }
        }
        bottom.addView(amount)
        bottom.addView(sumBtn)
        panel.addView(bottom)
        root.addView(panel)

        setupDrag(bubble)
    }

    private fun setupDrag(bubble: View) {
        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f; var moved = false
        bubble.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = e.rawX; touchY = e.rawY; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt(); val dy = (e.rawY - touchY).toInt()
                    if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) moved = true
                    if (moved) {
                        params.x = startX + dx; params.y = startY + dy
                        wm.updateViewLayout(root, params)
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) toggle()
            }
            true
        }
    }

    private fun toggle() {
        if (panel.visibility == View.VISIBLE) {
            amount.clearFocus()
            panel.visibility = View.GONE
        } else {
            refresh()
            panel.visibility = View.VISIBLE
        }
    }

    private fun refresh() {
        players = Store.load(this)
        if (players.none { it.id == selectedId }) selectedId = null
        list.removeAllViews()
        if (players.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Sin jugadores. Agregalos en la app."
                setTextColor(Color.DKGRAY)
            })
        }
        players.forEach { p -> list.addView(row(p)) }
        updateSumLabel()
    }

    private fun row(p: Player): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(2))
            background = if (p.id == selectedId) bg(Color.parseColor("#E1BEE7"), 8) else null
        }
        val label = TextView(this).apply {
            text = "${p.name}\n${p.points} pts"
            setTextColor(Color.BLACK)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                selectedId = p.id
                refresh()
            }
        }
        row.addView(label)
        listOf(1, 5, 10).forEach { n ->
            row.addView(Button(this).apply {
                text = "+$n"
                textSize = 12f
                minWidth = 0; minimumWidth = 0
                setPadding(dp(6), 0, dp(6), 0)
                layoutParams = LinearLayout.LayoutParams(dp(46), dp(40))
                setOnClickListener {
                    players = Store.addPoints(this@FloatingService, p.id, n)
                    refresh()
                }
            })
        }
        return row
    }

    private fun updateSumLabel() {
        val sel = players.firstOrNull { it.id == selectedId }
        sumBtn.text = if (sel != null) "Sumar a ${sel.name.take(8)}" else "Sumar"
    }

    private fun addCustom() {
        val id = selectedId
        val n = amount.text.toString().toIntOrNull()
        when {
            id == null -> Toast.makeText(this, "Tocá un jugador primero", Toast.LENGTH_SHORT).show()
            n == null || n <= 0 -> Toast.makeText(this, "Escribí una cantidad", Toast.LENGTH_SHORT).show()
            else -> {
                players = Store.addPoints(this, id, n)
                amount.setText("")
                amount.clearFocus()
                refresh()
            }
        }
    }
}
