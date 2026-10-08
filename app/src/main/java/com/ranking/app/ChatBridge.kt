package com.ranking.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.text.Normalizer

data class Suggestion(val id: String, val name: String, val points: Int, val reply: String)

/** Detecta respuestas a mesas armadas ("... GANADOR SUMA N PUNTOS") a partir de las líneas visibles del chat. */
object ChatParser {
    private val timeRe = Regex("""^\d{1,2}:\d{2}(\s?[ap]\.?\s?m\.?)?$""", RegexOption.IGNORE_CASE)
    private val sumaRe = Regex("""SUMA\s+(\d+)\s+PUNTOS""", RegexOption.IGNORE_CASE)

    fun parse(lines: List<String>): List<Suggestion> {
        val out = mutableListOf<Suggestion>()
        var group = mutableListOf<String>()
        for (l in lines) {
            if (timeRe.matches(l.trim())) {
                analyze(group)?.let { out.add(it) }
                group = mutableListOf()
            } else group.add(l)
        }
        return out
    }

    private fun analyze(g: List<String>): Suggestion? {
        val idx = g.indexOfFirst { sumaRe.containsMatchIn(it) }
        if (idx < 0) return null
        val before = g.subList(0, idx).map { it.trim() }.filter { it.isNotEmpty() }
        if (before.size < 2) return null // mesa original: a lo sumo el nombre de quien la posteó
        val replier = before.first()
        if (replier.length > 40) return null
        val points = sumaRe.find(g[idx])!!.groupValues[1].toIntOrNull() ?: return null
        val reply = g.drop(idx + 1).joinToString(" ").trim()
        return Suggestion("${replier}|${g[idx].hashCode()}|$reply".hashCode().toString(), replier, points, reply)
    }

    fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).lowercase().filter { it.isLetterOrDigit() }
}

object ChatBridge {
    private const val PREFS = "ranking_chat"
    private val main = Handler(Looper.getMainLooper())

    @Volatile var lastDump: String = "Todavía no se leyó nada. Activá el lector y abrí un chat de WhatsApp."
    private var pending: List<Suggestion> = emptyList()
    var listener: (() -> Unit)? = null

    fun pending(): List<Suggestion> = pending

    private fun handled(ctx: Context): Set<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("handled", "")!!
            .split(",").filter { it.isNotEmpty() }.toSet()

    fun markHandled(ctx: Context, id: String) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val all = (prefs.getString("handled", "")!!.split(",").filter { it.isNotEmpty() } + id).takeLast(500)
        prefs.edit().putString("handled", all.joinToString(",")).apply()
        pending = pending.filter { it.id != id }
        listener?.invoke()
    }

    fun update(ctx: Context, found: List<Suggestion>) {
        main.post {
            val h = handled(ctx)
            val next = found.filter { it.id !in h }.distinctBy { it.id }
            if (next != pending) {
                pending = next
                listener?.invoke()
            }
        }
    }
}
