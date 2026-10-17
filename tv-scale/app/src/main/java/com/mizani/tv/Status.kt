package com.mizani.tv

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

/** حالة الخدمة للشاشة (نفس العملية، بدون تعقيد) */
object Status {
    @Volatile var state: String = "يبدأ…"
    @Volatile var detail: String = ""
    @Volatile var upload: String = ""
    @Volatile var lastWeight: Store.Rec? = null
    private val lines = ArrayDeque<String>()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun log(msg: String) {
        synchronized(lines) {
            lines.addLast(fmt.format(Date()) + "  " + msg)
            while (lines.size > 200) lines.removeFirst()
        }
        changed()
    }

    fun logText(n: Int = 14): String = synchronized(lines) { lines.toList().takeLast(n).joinToString("\n") }
    fun fullLog(): String = synchronized(lines) { lines.joinToString("\n") }

    fun set(state: String, detail: String = "") { this.state = state; this.detail = detail; changed() }

    fun listen(l: () -> Unit) { listeners += l }
    fun unlisten(l: () -> Unit) { listeners -= l }
    fun changed() { main.post { listeners.forEach { it() } } }
}
