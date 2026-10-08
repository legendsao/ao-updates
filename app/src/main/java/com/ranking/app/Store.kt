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

    @Synchronized
    fun save(context: Context, players: List<Player>) {
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
