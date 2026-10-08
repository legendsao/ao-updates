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
import android.widget.HorizontalScrollView
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
        val (savedX, savedY) = loadPos()
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX
            y = savedY
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }
        wm.addView(root, params)
    }

    /** Posición guardada de la burbuja, o el borde derecho por defecto. */
    private fun loadPos(): Pair<Int, Int> {
        val prefs = getSharedPreferences("ranking_ui", MODE_PRIVATE)
        val defX = resources.displayMetrics.widthPixels - dp(34)
        return prefs.getInt("bx", defX) to prefs.getInt("by", dp(120))
    }

    private fun savePos() {
        getSharedPreferences("ranking_ui", MODE_PRIVATE).edit()
            .putInt("bx", params.x).putInt("by", params.y).apply()
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

    /** Agrega opacidad a un color sólido "#RRGGBB" (0..255). Semi-transparente, nunca 100% invisible. */
    private fun translucent(hex: String, alpha: Int = 150) = Color.parseColor(hex) and 0x00FFFFFF or (alpha shl 24)

    private lateinit var scroll: ScrollView
    private lateinit var chips: LinearLayout
    private lateinit var addForm: LinearLayout
    private lateinit var nameInput: EditText
    private lateinit var numberInput: EditText
    private var editFields: List<EditText> = emptyList()

    private fun imm() = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager

    private fun field(hintText: String, type: Int, weight: Float = 1f) = EditText(this).apply {
        hint = hintText
        inputType = type
        imeOptions = EditorInfo.IME_ACTION_DONE
        setSingleLine()
        textSize = 12f
        setTextColor(Color.BLACK)
        setHintTextColor(Color.DKGRAY)
        background = bg(translucent("#FFFFFF", 235), 7)
        setPadding(dp(7), dp(2), dp(7), dp(2))
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
        setOnFocusChangeListener { v, hasFocus ->
            v.post { syncFocusable(v, hasFocus) }
        }
        setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { v.clearFocus(); true } else false
        }
    }

    /** Quita FLAG_NOT_FOCUSABLE solo mientras algún campo tenga foco. */
    private fun syncFocusable(v: View, hasFocus: Boolean) {
        val any = editFields.any { it.hasFocus() }
        val notFocusable = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0
        if (any && notFocusable) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            wm.updateViewLayout(root, params)
        } else if (!any && !notFocusable) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            wm.updateViewLayout(root, params)
            imm().hideSoftInputFromWindow(v.windowToken, 0)
        }
        if (hasFocus) v.post { imm().showSoftInput(v, InputMethodManager.SHOW_IMPLICIT) }
    }

    private fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 12f
        minWidth = 0; minimumWidth = 0
        minHeight = 0; minimumHeight = dp(29)
        setPadding(dp(6), 0, dp(6), 0)
        setTextColor(Color.BLACK)
        background = bg(translucent("#FFFFFF", 235), 7)
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    private fun buildViews() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val bubble = TextView(this).apply {
            text = "🏆"
            textSize = 28f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#6A1B9A"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        }
        root.addView(bubble)

        val dm = resources.displayMetrics
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Pestaña blanca semi-transparente que contiene todos los botones.
            background = bg(translucent("#FFFFFF", 215), 13)
            setPadding(dp(7), dp(7), dp(7), dp(7))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                minOf(dm.widthPixels - dp(16), dp(260)), LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply {
            addView(list)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (dm.heightPixels * 0.29f).toInt()
            )
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(btn("✕ Cerrar") { stopSelf() })
        top.addView(btn("↩ Deshacer") {
            if (Store.undo(this@FloatingService)) refresh()
            else Toast.makeText(this@FloatingService, "Nada para deshacer", Toast.LENGTH_SHORT).show()
        })
        panel.addView(top)
        panel.addView(scroll)

        // Único lugar para sumar o restar: actúa siempre sobre el jugador seleccionado arriba.
        panel.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            chips = LinearLayout(this@FloatingService).apply { orientation = LinearLayout.HORIZONTAL }
            addView(chips)
        })
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        amount = field("Cantidad", InputType.TYPE_CLASS_NUMBER)
        sumBtn = btn("Sumar") { applyCustom(1) }
        bottom.addView(amount)
        bottom.addView(btn("Restar") { applyCustom(-1) })
        bottom.addView(sumBtn)
        panel.addView(bottom)

        // Alta rápida de jugador
        val addToggle = btn("+ Jugador") {
            addForm.visibility = if (addForm.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        panel.addView(addToggle)
        addForm = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        nameInput = field("Nombre", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, 1f)
        numberInput = field("Número", InputType.TYPE_CLASS_PHONE, 1f)
        addForm.addView(nameInput)
        addForm.addView(numberInput)
        addForm.addView(btn("Guardar") { saveNewPlayer() })
        panel.addView(addForm)
        editFields = listOf(amount, nameInput, numberInput)

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
                MotionEvent.ACTION_UP -> if (moved) savePos() else toggle()
            }
            true
        }
    }

    private fun toggle() {
        if (panel.visibility == View.VISIBLE) collapse() else {
            refresh()
            panel.visibility = View.VISIBLE
        }
    }

    /** Cierra el panel y le devuelve el uso del resto de la pantalla (teclado, WhatsApp, etc). */
    private fun collapse() {
        editFields.forEach { it.clearFocus() }
        panel.visibility = View.GONE
    }

    private fun refresh() {
        players = Store.load(this)
        if (players.none { it.id == selectedId }) selectedId = null
        list.removeAllViews()
        if (players.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Sin jugadores. Agregalos en la app."
                textSize = 13f
                setTextColor(Color.DKGRAY)
            })
        }
        players.forEach { p -> list.addView(row(p)) }
        updateSumLabel()
        refreshChips()
    }

    /** Fila de solo selección: tocar un jugador lo marca como destino de los botones de abajo. */
    private fun row(p: Player): View {
        val selected = p.id == selectedId
        return TextView(this).apply {
            text = "${if (selected) "▸ " else ""}${p.name} — ${p.points} pts"
            setTextColor(Color.BLACK)
            textSize = 13f
            setPadding(dp(6), dp(5), dp(6), dp(5))
            background = bg(translucent(if (selected) "#CE93D8" else "#FFFFFF", 235), 7)
            val margin = dp(2)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, margin, 0, margin) }
            setOnClickListener {
                selectedId = p.id
                refresh()
            }
        }
    }

    private fun updateSumLabel() {
        val sel = players.firstOrNull { it.id == selectedId }
        sumBtn.text = if (sel != null) "Sumar a ${sel.name.take(8)}" else "Sumar"
        amount.hint = if (sel != null) "Cantidad p/ ${sel.name.take(10)}" else "Cantidad"
    }

    /** Un solo lugar con todos los atajos de puntos: -1/+1/+5/+10 fijos y los configurables en Ajustes. */
    private fun refreshChips() {
        chips.removeAllViews()
        (listOf(-1, 1, 5, 10) + Store.quick(this)).forEach { n ->
            chips.addView(btn(if (n > 0) "+$n" else "$n") {
                val id = selectedId
                if (id == null) Toast.makeText(this@FloatingService, "Tocá un jugador primero", Toast.LENGTH_SHORT).show()
                else { players = Store.addPoints(this@FloatingService, id, n); refresh() }
            })
        }
    }

    private fun applyCustom(sign: Int) {
        val id = selectedId
        val n = amount.text.toString().toIntOrNull()
        when {
            id == null -> Toast.makeText(this, "Tocá un jugador primero", Toast.LENGTH_SHORT).show()
            n == null || n <= 0 -> Toast.makeText(this, "Escribí una cantidad", Toast.LENGTH_SHORT).show()
            else -> {
                players = Store.addPoints(this, id, n * sign)
                amount.setText("")
                amount.clearFocus()
                refresh()
            }
        }
    }

    private fun saveNewPlayer() {
        val name = nameInput.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, "Falta el nombre", Toast.LENGTH_SHORT).show()
            return
        }
        val p = Player(name = name, number = cleanNumber(numberInput.text.toString()))
        Store.save(this, Store.load(this) + p)
        nameInput.setText("")
        numberInput.setText("")
        editFields.forEach { it.clearFocus() }
        selectedId = p.id
        refresh()
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }
}
