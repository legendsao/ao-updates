package com.ranking.app

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatReaderService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var scheduled = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (scheduled) return
        scheduled = true
        handler.postDelayed({ scheduled = false; read() }, 500)
    }

    override fun onInterrupt() {}

    private fun read() {
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        if (!pkg.startsWith("com.whatsapp")) return
        val lines = mutableListOf<String>()
        collect(root, lines)
        val found = ChatParser.parse(lines)
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        ChatBridge.lastDump = buildString {
            append("Lectura $time — ${lines.size} líneas, ${found.size} sugerencias\n")
            found.forEach { append("→ ${it.name} +${it.points} (respuesta: \"${it.reply}\")\n") }
            append("---\n")
            lines.take(250).forEachIndexed { i, l -> append("$i: ${l.replace('\n', '⏎').take(200)}\n") }
        }
        ChatBridge.update(applicationContext, found)
    }

    private fun collect(n: AccessibilityNodeInfo?, out: MutableList<String>) {
        if (n == null || !n.isVisibleToUser) return
        val text = n.text?.toString()?.takeIf { it.isNotBlank() }
        if (text != null) out.add(text)
        else if (n.childCount == 0) n.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        for (i in 0 until n.childCount) collect(n.getChild(i), out)
    }
}
