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
        ChatBridge.listener = { refreshSuggestions() }
        refreshSuggestions()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        running = false
        ChatBridge.listener = null
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

    private lateinit var scroll: ScrollView
    private lateinit var bubbleView: TextView
    private lateinit var sugBox: LinearLayout
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
        setTextColor(Color.BLACK)
        setHintTextColor(Color.GRAY)
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
        setPadding(dp(6), 0, dp(6), 0)
        setOnClickListener { onClick() }
    }

    private fun buildViews() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val bubble = TextView(this).apply {
            bubbleView = this
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

        val dm = resources.displayMetrics
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(Color.parseColor("#F5FFFFFF"), 12)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                minOf(dm.widthPixels - dp(16), dp(380)), LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply {
            addView(list)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (dm.heightPixels * 0.38f).toInt()
            )
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(btn("↩ Deshacer") {
            if (Store.undo(this@FloatingService)) refresh()
            else Toast.makeText(this@FloatingService, "Nada para deshacer", Toast.LENGTH_SHORT).show()
        })
        top.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            chips = LinearLayout(this@FloatingService).apply { orientation = LinearLayout.HORIZONTAL }
            addView(chips)
        })
        panel.addView(top)
        sugBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(Color.parseColor("#FFF3E0"), 8)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            visibility = View.GONE
        }
        panel.addView(sugBox)
        panel.addView(scroll)

        // Cantidad libre sobre el jugador seleccionado
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
                MotionEvent.ACTION_UP -> if (!moved) toggle()
            }
            true
        }
    }

    private fun toggle() {
        if (panel.visibility == View.VISIBLE) {
            editFields.forEach { it.clearFocus() }
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
        refreshChips()
        refreshSuggestions()
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
        listOf(-1, 1, 5, 10).forEach { n ->
            row.addView(btn(if (n > 0) "+$n" else "$n") {
                players = Store.addPoints(this@FloatingService, p.id, n)
                selectedId = p.id
                refresh()
            }.apply { layoutParams = LinearLayout.LayoutParams(dp(44), dp(40)) })
        }
        return row
    }

    private fun updateSumLabel() {
        val sel = players.firstOrNull { it.id == selectedId }
        sumBtn.text = if (sel != null) "Sumar a ${sel.name.take(8)}" else "Sumar"
        amount.hint = if (sel != null) "Cantidad p/ ${sel.name.take(10)}" else "Cantidad"
    }

    private fun refreshChips() {
        chips.removeAllViews()
        Store.quick(this).forEach { n ->
            chips.addView(btn("+$n") {
                val id = selectedId
                if (id == null) Toast.makeText(this@FloatingService, "Tocá un jugador primero", Toast.LENGTH_SHORT).show()
                else { Store.addPoints(this@FloatingService, id, n); refresh() }
            })
        }
    }

    private fun refreshSuggestions() {
        if (!::sugBox.isInitialized) return
        val sugs = ChatBridge.pending()
        bubbleView.text = if (sugs.isEmpty()) "🏆" else "🏆${sugs.size}"
        sugBox.removeAllViews()
        sugBox.visibility = if (sugs.isEmpty()) View.GONE else View.VISIBLE
        val all = Store.load(this)
        sugs.take(4).forEach { s ->
            val exists = all.any { ChatParser.norm(it.name) == ChatParser.norm(s.name) }
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            r.addView(TextView(this).apply {
                text = "${s.name}${if (exists) "" else " (nuevo)"} +${s.points}" +
                    if (s.reply.isNotBlank()) "\n“${s.reply.take(30)}”" else ""
                setTextColor(Color.BLACK); textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            r.addView(btn("Sumar") { applySuggestion(s) })
            r.addView(btn("✕") { ChatBridge.markHandled(this@FloatingService, s.id) })
            sugBox.addView(r)
        }
    }

    private fun applySuggestion(s: Suggestion) {
        var all = Store.load(this)
        var p = all.firstOrNull { ChatParser.norm(it.name) == ChatParser.norm(s.name) }
        if (p == null) {
            p = Player(name = s.name, number = "")
            all = all + p
            Store.save(this, all)
        }
        Store.addPoints(this, p.id, s.points)
        selectedId = p.id
        ChatBridge.markHandled(this, s.id)
        refresh()
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
