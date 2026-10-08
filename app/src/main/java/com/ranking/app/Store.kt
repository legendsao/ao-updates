package com.ranking.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Player(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val number: String,
    val points: Int = 0,
)

fun cleanNumber(raw: String): String = raw.filter { it.isDigit() }

fun sortPlayers(list: List<Player>): List<Player> =
    list.sortedWith(compareByDescending<Player> { it.points }.thenBy { it.name.lowercase() })

object Store {
    private const val PREFS = "ranking"
    private const val KEY = "players"

    @Synchronized
    fun load(context: Context): List<Player> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Player(o.getString("id"), o.getString("name"), o.getString("number"), o.getInt("points"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private val history = ArrayDeque<List<Player>>()

    @Synchronized
    fun save(context: Context, players: List<Player>) {
        val cur = load(context)
        if (cur != players) {
            history.addLast(cur)
            if (history.size > 30) history.removeFirst()
        }
        write(context, players)
    }

    /** Revierte el último cambio guardado (en esta sesión del proceso). */
    @Synchronized
    fun undo(context: Context): Boolean {
        if (history.isEmpty()) return false
        write(context, history.removeLast())
        return true
    }

    private const val DEFAULT_QUICK = "-1,1,5,10,15,30,100"

    /** Todos los atajos de puntos de la flotante, en el orden que el usuario los escribió. Admite negativos. */
    fun quick(context: Context): List<Int> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("quick", DEFAULT_QUICK)!!
            .split(",", " ").mapNotNull { it.trim().toIntOrNull() }.filter { it != 0 }.take(12)

    fun quickRaw(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("quick", DEFAULT_QUICK)!!

    fun setQuick(context: Context, raw: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("quick", raw).apply()
    }

    // --- Apariencia de la ventana flotante, configurable solo desde la app ---

    private fun ui(context: Context) = context.getSharedPreferences("ranking_ui", Context.MODE_PRIVATE)

    fun panelOpacity(context: Context) = ui(context).getInt("panelOpacity", 84)
    fun setPanelOpacity(context: Context, pct: Int) = ui(context).edit().putInt("panelOpacity", pct).apply()

    fun buttonOpacity(context: Context) = ui(context).getInt("buttonOpacity", 92)
    fun setButtonOpacity(context: Context, pct: Int) = ui(context).edit().putInt("buttonOpacity", pct).apply()

    /** Multiplicador de tamaño sobre las medidas base (100 = tamaño normal de fábrica). */
    fun scalePercent(context: Context) = ui(context).getInt("scale", 100)
    fun setScalePercent(context: Context, pct: Int) = ui(context).edit().putInt("scale", pct).apply()

    fun bubbleColor(context: Context) = ui(context).getString("colorBubble", "#6A1B9A")!!
    fun panelColor(context: Context) = ui(context).getString("colorPanel", "#FFFFFF")!!
    fun buttonColor(context: Context) = ui(context).getString("colorButton", "#FFFFFF")!!
    fun selectedColor(context: Context) = ui(context).getString("colorSelected", "#CE93D8")!!

    fun setColor(context: Context, key: String, hex: String) = ui(context).edit().putString(key, hex).apply()

    /** Lado por defecto de la burbuja la primera vez, o tras "reiniciar posición". */
    fun bubbleSide(context: Context) = ui(context).getString("side", "right")!!
    fun setBubbleSide(context: Context, side: String) = ui(context).edit().putString("side", side).apply()

    fun resetBubblePosition(context: Context) = ui(context).edit().remove("bx").remove("by").apply()

    /** Texto JSON con los jugadores actuales, para guardar fuera de la app (respaldo que sobrevive a una desinstalación). */
    fun exportJson(context: Context): String {
        val arr = JSONArray()
        load(context).forEach {
            arr.put(
                JSONObject().put("name", it.name).put("number", it.number).put("points", it.points)
            )
        }
        return arr.toString(2)
    }

    /** Reemplaza los jugadores guardados por los de un respaldo. Devuelve null si el texto no es válido. */
    @Synchronized
    fun importJson(context: Context, raw: String): List<Player>? {
        val players = try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Player(
                    name = o.getString("name"),
                    number = o.optString("number", ""),
                    points = o.optInt("points", 0),
                )
            }
        } catch (e: Exception) {
            return null
        }
        save(context, players)
        return players
    }

    private fun write(context: Context, players: List<Player>) {
        val arr = JSONArray()
        players.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("name", it.name)
                    .put("number", cleanNumber(it.number)).put("points", it.points)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, arr.toString()).apply()
    }

    /** Suma puntos leyendo el estado guardado más reciente y devuelve la lista actualizada. */
    @Synchronized
    fun addPoints(context: Context, id: String, delta: Int): List<Player> {
        val updated = load(context).map { if (it.id == id) it.copy(points = it.points + delta) else it }
        save(context, updated)
        return updated
    }
}
