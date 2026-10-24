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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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

    /** Posición de la burbuja: la "x" SIEMPRE se recalcula al borde más cercano (nunca queda a
     * mitad de pantalla, ni con posiciones viejas guardadas de antes de tener el snap). Solo la
     * "y" se recuerda tal cual. */
    private fun loadPos(): Pair<Int, Int> {
        val prefs = getSharedPreferences("ranking_ui", MODE_PRIVATE)
        val dm = resources.displayMetrics
        val size = dp(52)
        val defX = if (Store.bubbleSide(this) == "left") 0 else dm.widthPixels - size
        val storedX = prefs.getInt("bx", defX)
        val x = if (storedX + size / 2 < dm.widthPixels / 2) 0 else dm.widthPixels - size
        val maxY = (dm.heightPixels - size).coerceAtLeast(0)
        val y = prefs.getInt("by", dp(120)).coerceIn(0, maxY)
        return x to y
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

    /** dp aplicando el "Tamaño" elegido en Ajustes (100% = medidas de fábrica). */
    private fun dp(v: Int) = (v * resources.displayMetrics.density * (Store.scalePercent(this) / 100f)).toInt()

    private fun bg(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    /** Agrega opacidad (0..100%) a un color sólido "#RRGGBB". Semi-transparente, nunca 100% invisible. */
    private fun translucent(hex: String, pct: Int) =
        runCatching { Color.parseColor(hex) }.getOrDefault(Color.WHITE) and 0x00FFFFFF or
            ((pct.coerceIn(0, 100) * 255 / 100) shl 24)

    private fun panelBg() = bg(translucent(Store.panelColor(this), Store.panelOpacity(this)), 13)
    private fun elementBg(hex: String) = bg(translucent(hex, Store.buttonOpacity(this)), 7)

    /** Blanco o negro según qué se lea mejor sobre ese color de fondo (así el texto nunca
     * desaparece, elijas el color que elijas, claro u oscuro). */
    private fun autoText(hex: String): Int {
        val c = runCatching { Color.parseColor(hex) }.getOrDefault(Color.WHITE)
        val luminance = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255
        return if (luminance > 0.55) Color.BLACK else Color.WHITE
    }

    /** Contorno (no relleno) alrededor del texto, para que no se mezcle con el fondo. Se adapta:
     * negro si el texto es claro, blanco si el texto es oscuro (así nunca queda "manchado",
     * como pasaba en el botón Sumar con texto oscuro sobre dorado). Android no permite un
     * stroke real en un TextView; un halo parejo (offset 0,0) es lo más cercano a un contorno. */
    private fun TextView.relief(textColorArgb: Int) {
        val outline = if (isLight(textColorArgb)) Color.argb(190, 0, 0, 0) else Color.argb(190, 255, 255, 255)
        setShadowLayer(2.2f.coerceAtLeast(dp(2).toFloat()), 0f, 0f, outline)
    }

    private fun isLight(argb: Int): Boolean {
        val luminance = (0.299 * Color.red(argb) + 0.587 * Color.green(argb) + 0.114 * Color.blue(argb)) / 255
        return luminance > 0.6
    }

    private fun textOnButton() = autoText(Store.buttonColor(this))
    private fun textOnPanel() = autoText(Store.panelColor(this))
    private fun textOnPanelHex() = if (textOnPanel() == Color.WHITE) "#FFFFFF" else "#000000"

    // Colores de significado fijo (no dependen del tema elegido): rojo = "cerrar/borrar",
    // dorado = la acción principal "Sumar". El violeta de acento sí usa el color elegido en Ajustes.
    private fun DANGER() = Store.dangerColor(this)
    private fun GOLD() = Store.goldColor(this)
    private fun accent() = Store.selectedColor(this)

    private fun strokeBg(fillHex: String, fillPct: Int, strokeHex: String, strokePct: Int, radius: Int) =
        GradientDrawable().apply {
            setColor(translucent(fillHex, fillPct))
            setStroke(dp(1).coerceAtLeast(2), translucent(strokeHex, strokePct))
            cornerRadius = dp(radius).toFloat()
        }

    private fun dashedBg(strokeHex: String, strokePct: Int, radius: Int) = GradientDrawable().apply {
        setColor(Color.TRANSPARENT)
        setStroke(dp(1).coerceAtLeast(2), translucent(strokeHex, strokePct), dp(4).toFloat(), dp(3).toFloat())
        cornerRadius = dp(radius).toFloat()
    }

    /** Botón circular de ícono (✕ ↩ ✎), con tinte propio según qué hace. */
    private fun iconButton(symbol: String, tintHex: String, tintPct: Int, onClick: () -> Unit) = TextView(this).apply {
        text = symbol
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(translucent(tintHex, 230))
        relief(translucent(tintHex, 230))
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(translucent(tintHex, tintPct))
        }
        layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    /** Avatar circular con la inicial, de un color estable por nombre (mismo criterio que la app). */
    private val AVATAR_COLORS = listOf("#6A1B9A", "#1976D2", "#2E7D32", "#D32F2F", "#F9A825", "#00838F", "#AD1457", "#4E342E")
    private fun avatarColor(name: String) = AVATAR_COLORS[(name.hashCode() and 0x7fffffff) % AVATAR_COLORS.size]
    private fun avatar(name: String) = TextView(this).apply {
        text = name.trim().take(1).uppercase().ifEmpty { "?" }
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        relief(Color.WHITE)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(avatarColor(name)))
        }
        layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(8) }
    }

    private lateinit var scroll: ScrollView
    private lateinit var feedback: TextView
    private val uiHandler = Handler(Looper.getMainLooper())
    private var clearFeedback: Runnable? = null

    /** Aviso corto dentro del propio panel: a diferencia del Toast del sistema, esto nunca se
     * pierde ni se descarta (Android limita/descarta Toasts seguidos desde un servicio). */
    private fun notify(msg: String) {
        if (!::feedback.isInitialized) return
        feedback.text = msg
        feedback.visibility = View.VISIBLE
        clearFeedback?.let { uiHandler.removeCallbacks(it) }
        val r = Runnable { feedback.visibility = View.GONE }
        clearFeedback = r
        uiHandler.postDelayed(r, 1800)
    }
    private lateinit var chips: LinearLayout
    private lateinit var editToggle: TextView
    private var editMode = false
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
        textSize = 14f
        setTextColor(textOnButton())
        relief(textOnButton())
        setHintTextColor((textOnButton() and 0x00FFFFFF) or (0x99 shl 24))
        background = elementBg(Store.buttonColor(this@FloatingService))
        setPadding(dp(9), dp(4), dp(9), dp(4))
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
        textSize = 14f
        minWidth = 0; minimumWidth = 0
        minHeight = 0; minimumHeight = dp(36)
        setPadding(dp(8), 0, dp(8), 0)
        setTextColor(textOnButton())
        relief(textOnButton())
        background = elementBg(Store.buttonColor(this@FloatingService))
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
                setColor(runCatching { Color.parseColor(Store.bubbleColor(this@FloatingService)) }.getOrDefault(Color.parseColor("#6A1B9A")))
            }
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
        }
        root.addView(bubble)

        val dm = resources.displayMetrics
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Pestaña blanca semi-transparente que contiene todos los botones.
            background = panelBg()
            setPadding(dp(9), dp(9), dp(9), dp(9))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                minOf(dm.widthPixels - dp(16), dp(300)), LinearLayout.LayoutParams.WRAP_CONTENT
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
        top.addView(iconButton("✕", DANGER(), 55) { stopSelf() }.apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8) })
        top.addView(iconButton("↩", "#FFFFFF", 20) {
            if (Store.undo(this@FloatingService)) refresh()
            else notify("Nada para deshacer")
        }.apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8) })
        editToggle = iconButton("✎", accent(), 55) {
            editMode = !editMode
            refreshChips()
        }
        top.addView(editToggle)
        panel.addView(top)
        feedback = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#8BE28B"))
            relief(Color.parseColor("#8BE28B"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = bg(translucent("#2E7D32", 45), 20)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        panel.addView(feedback)
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
        val restarBtn = TextView(this).apply {
            text = "Restar"
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(textOnPanel())
            relief(textOnPanel())
            background = strokeBg(Store.panelColor(this@FloatingService), 0, textOnPanelHex(), 45, 12)
            setPadding(dp(12), 0, dp(12), 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36))
                .apply { marginStart = dp(6) }
            setOnClickListener { applyCustom(-1) }
        }
        sumBtn = Button(this).apply {
            text = "Sumar"
            textSize = 13f
            minWidth = 0; minimumWidth = 0
            minHeight = 0; minimumHeight = dp(36)
            setPadding(dp(14), 0, dp(14), 0)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(autoText(GOLD()))
            relief(autoText(GOLD()))
            background = bg(translucent(GOLD(), 235), 12)
            stateListAnimator = null
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36))
                .apply { marginStart = dp(6) }
            setOnClickListener { applyCustom(1) }
        }
        bottom.addView(amount)
        bottom.addView(restarBtn)
        bottom.addView(sumBtn)
        panel.addView(bottom)

        // Alta rápida de jugador: borde punteado, es "agregar algo nuevo", no sumar puntos.
        val addToggle = TextView(this).apply {
            text = "+ Jugador"
            textSize = 13f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(translucent(accent(), 235))
            relief(translucent(accent(), 235))
            background = dashedBg(accent(), 60, 12)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
            setOnClickListener {
                addForm.visibility = if (addForm.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
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
                MotionEvent.ACTION_UP -> if (moved) snapToEdge() else toggle()
            }
            true
        }
    }

    /** Al soltar, la burbuja va sola al borde más cercano (izquierda o derecha), como el chat head de Messenger. */
    private fun snapToEdge() {
        val dm = resources.displayMetrics
        val size = dp(52)
        val maxY = (dm.heightPixels - size).coerceAtLeast(0)
        params.y = params.y.coerceIn(0, maxY)
        val goLeft = params.x + size / 2 < dm.widthPixels / 2
        val targetX = if (goLeft) 0 else dm.widthPixels - size
        val startX = params.x
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180
            addUpdateListener {
                params.x = (startX + (targetX - startX) * it.animatedValue as Float).toInt()
                runCatching { wm.updateViewLayout(root, params) }
            }
            start()
        }
        savePos()
        getSharedPreferences("ranking_ui", MODE_PRIVATE).edit().putInt("bx", targetX).apply()
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
                textSize = 14f
                setTextColor(textOnPanel())
                relief(textOnPanel())
            })
        }
        players.forEach { p -> list.addView(row(p)) }
        updateSumLabel()
        refreshChips()
    }

    /** Fila de solo selección: tocar un jugador lo marca como destino de los botones de abajo.
     * El seleccionado se marca con un borde que resalta (no un bloque sólido), para que se
     * note el "rastro" de a quién le sumaste sin tapar todo de color. */
    private fun row(p: Player): View {
        val selected = p.id == selectedId
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), dp(6), dp(7), dp(6))
            background = if (selected) strokeBg(Store.selectedColor(this@FloatingService), 28, Store.selectedColor(this@FloatingService), 100, 10)
                else elementBg(Store.buttonColor(this@FloatingService))
            val margin = dp(2)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, margin, 0, margin) }

            addView(avatar(p.name))
            addView(TextView(this@FloatingService).apply {
                text = "${if (selected) "✓ " else ""}${p.name}"
                val nameColor = if (selected) textOnPanel() else autoText(Store.buttonColor(this@FloatingService))
                setTextColor(nameColor)
                relief(nameColor)
                textSize = 14f
                setTypeface(typeface, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@FloatingService).apply {
                text = "${p.points} pts"
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor(GOLD()))
                relief(Color.parseColor(GOLD()))
                setPadding(dp(7), dp(3), dp(7), dp(3))
                background = bg(translucent(GOLD(), 35), 10)
            })

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

    /** Un solo lugar con los atajos de puntos. En modo edición se pueden mover (◀▶), borrar (✕) o agregar uno. */
    private fun refreshChips() {
        chips.removeAllViews()
        editToggle.text = if (editMode) "✓" else "✎"
        val values = Store.quick(this)
        if (!editMode) {
            values.forEach { n ->
                val tint = when {
                    n < 0 -> DANGER()
                    kotlin.math.abs(n) > 10 -> GOLD()
                    else -> accent()
                }
                // Tamaño progresivo según el monto: así, dentro del mismo color, un +100 no
                // se ve igual que un +1 (no son "todos el mismo botón").
                val mag = kotlin.math.abs(n)
                val tier = when { mag <= 1 -> 0; mag <= 10 -> 1; mag <= 30 -> 2; else -> 3 }
                val fSize = 12f + tier * 1.3f
                val padH = 8 + tier * 3
                val padV = 5 + tier * 2
                chips.addView(TextView(this).apply {
                    text = if (n > 0) "+$n" else "$n"
                    textSize = fSize
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(translucent(tint, 235))
                    relief(translucent(tint, 235))
                    setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
                    background = strokeBg(tint, 30, tint, 70, 20)
                    val m = dp(3)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(m, 0, m, 0) }
                    setOnClickListener { applyToSelected(n) }
                })
            }
            return
        }
        values.forEachIndexed { i, n ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            cell.addView(btn("◀") { moveQuick(i, i - 1) }.apply { isEnabled = i > 0; alpha = if (i > 0) 1f else 0.3f })
            cell.addView(TextView(this).apply {
                text = if (n > 0) "+$n" else "$n"
                setTextColor(textOnPanel())
                relief(textOnPanel())
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(dp(4), 0, dp(4), 0)
            })
            cell.addView(btn("▶") { moveQuick(i, i + 1) }.apply { isEnabled = i < values.lastIndex; alpha = if (i < values.lastIndex) 1f else 0.3f })
            cell.addView(btn("✕") { deleteQuick(i) })
            chips.addView(cell)
        }
        chips.addView(btn("+ agregar") { addQuickFromAmount() })
    }

    private fun moveQuick(from: Int, to: Int) {
        val list = Store.quick(this).toMutableList()
        if (to !in list.indices) return
        list.add(to, list.removeAt(from))
        Store.setQuick(this, list.joinToString(","))
        refreshChips()
    }

    private fun deleteQuick(index: Int) {
        val list = Store.quick(this).toMutableList()
        list.removeAt(index)
        Store.setQuick(this, list.joinToString(","))
        refreshChips()
    }

    /** Toma lo que esté escrito en "Cantidad" y lo agrega como atajo nuevo (admite negativos). */
    private fun addQuickFromAmount() {
        val n = amount.text.toString().toIntOrNull()
        if (n == null || n == 0) {
            notify("Escribí un número en Cantidad primero")
            return
        }
        Store.setQuick(this, (Store.quick(this) + n).joinToString(","))
        amount.setText("")
        refreshChips()
    }

    private fun applyCustom(sign: Int) {
        val n = amount.text.toString().toIntOrNull()
        if (n == null || n <= 0) {
            notify("Escribí una cantidad")
            return
        }
        applyToSelected(n * sign)
        amount.setText("")
        amount.clearFocus()
    }

    /** Suma/resta sobre el jugador marcado, deja la marca bien visible y avisa a quién se le aplicó. */
    private var burstPlayerId: String? = null
    private var burstTotal: Int = 0

    private fun applyToSelected(delta: Int) {
        val id = selectedId
        val target = players.firstOrNull { it.id == id }
        if (id == null || target == null) {
            notify("⚠ Tocá un jugador primero")
            return
        }
        players = Store.addPoints(this, id, delta)
        refresh()
        // Si tocás varias veces seguidas (antes de que el aviso se oculte), se acumula en vez
        // de mostrar solo el último toque.
        burstTotal = if (burstPlayerId == id && feedback.visibility == View.VISIBLE) burstTotal + delta else delta
        burstPlayerId = id
        val sign = if (burstTotal > 0) "+" else ""
        notify("✓ $sign$burstTotal a ${target.name}")
    }

    private fun saveNewPlayer() {
        val name = nameInput.text.toString().trim()
        if (name.isEmpty()) {
            notify("Falta el nombre")
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
