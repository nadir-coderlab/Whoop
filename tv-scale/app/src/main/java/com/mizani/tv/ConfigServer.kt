package com.mizani.tv

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Base64
import java.util.concurrent.Executors

/**
 * صفحة إعداد صغيرة تفتحها من جوالك (نفس الواي فاي): http://<عنوان البوكس>:8765
 * لأن كتابة توكن طويل بريموت التلفزيون عذاب. تشتغل بس والتطبيق مفتوح على الشاشة،
 * والحفظ يحتاج الرقم السري المكتوب على الشاشة.
 */
class ConfigServer(private val store: Store, private val pin: String, private val onChange: () -> Unit) {
    companion object { const val PORT = 8765 }
    private var server: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(2)

    fun start() {
        if (server != null) return
        Thread {
            try {
                val s = ServerSocket(PORT); server = s
                while (!s.isClosed) { val c = s.accept(); pool.execute { handle(c) } }
            } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }
    }

    fun stop() { try { server?.close() } catch (_: Exception) {}; server = null }

    private fun handle(sock: Socket): Unit = sock.use { c ->
        try {
            c.soTimeout = 15_000
            val inp = BufferedInputStream(c.getInputStream())
            val head = readHead(inp) ?: return
            val lines = head.split("\r\n")
            val (method, path) = lines[0].split(" ").let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "/") }
            val len = lines.firstOrNull { it.startsWith("content-length:", true) }?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
            val body = if (len in 1..65536) ByteArray(len).also { var o = 0; while (o < len) { val n = inp.read(it, o, len - o); if (n < 0) break; o += n } }.toString(Charsets.UTF_8) else ""
            val form = parse(body)
            val html = when {
                method == "POST" && path.startsWith("/save") -> save(form)
                method == "POST" && path.startsWith("/test") -> test(form)
                else -> page(null)
            }
            val bytes = html.toByteArray(Charsets.UTF_8)
            c.getOutputStream().apply {
                write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                write(bytes); flush()
            }
            Unit
        } catch (_: Exception) {}
    }

    private fun readHead(inp: BufferedInputStream): String? {
        val b = ByteArrayOutputStream()
        var last4 = 0
        while (b.size() < 16384) {
            val x = inp.read(); if (x < 0) return null
            b.write(x); last4 = (last4 shl 8) or x
            if (last4 == 0x0D0A0D0A) return b.toString("UTF-8")
        }
        return null
    }

    private fun parse(body: String): Map<String, String> = body.split('&').filter { '=' in it }.associate {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun save(f: Map<String, String>): String {
        if (f["pin"]?.trim() != pin) return page("الرقم السري غلط — شوفه على شاشة التلفزيون", false)
        f["token"]?.trim()?.takeIf { it.isNotEmpty() }?.let { store.token = it }
        f["repo"]?.trim()?.takeIf { Regex("^[\\w.-]+/[\\w.-]+$").matches(it) }?.let { store.repo = it }
        f["password"]?.takeIf { it.isNotEmpty() }?.let { pw ->
            val salt = Seal.randomBytes(16)
            val key = Seal.deriveKey(pw, salt)          // ثواني على البوكس — مرة وحدة بس
            store.saltB64 = Base64.getEncoder().encodeToString(salt)
            store.keyB64 = Base64.getEncoder().encodeToString(key)
            store.iter = Seal.ITER
        }
        f["height"]?.toIntOrNull()?.let { if (it in 90..220) store.heightCm = it }
        f["age"]?.toIntOrNull()?.let { if (it in 10..99) store.age = it }
        f["sex"]?.let { store.female = it == "f" }
        val mn = f["min"]?.replace(',', '.')?.toFloatOrNull() ?: 0f
        val mx = f["max"]?.replace(',', '.')?.toFloatOrNull() ?: 0f
        if (mn == 0f && mx == 0f) { store.minKg = 0f; store.maxKg = 0f } else if (mx > mn && mn > 0) { store.minKg = mn; store.maxKg = mx }
        if (f["forget"] == "1") { store.scaleMac = ""; store.scaleName = "" }
        onChange()
        Status.log("انحفظت الإعدادات من الجوال")
        return page(if (store.configured()) "انحفظ ✓ — كل شي جاهز. اطلع على الميزان الصبح والوزن يوصل لحاله." else "انحفظ، بس باقي التوكن أو كلمة المرور.", store.configured())
    }

    private fun test(f: Map<String, String>): String {
        if (f["pin"]?.trim() != pin) return page("الرقم السري غلط", false)
        return when (val r = Uploader.send(store, "mizani-tv/test", test = true)) {
            is Uploader.Result.Ok -> page("GitHub استلم الإرسال ✓ — افتح Actions في مستودع Whoop وتأكد إن التشغيل نجح (لو فشل، كلمة المرور مو نفس DASHBOARD_PASSWORD).", true)
            is Uploader.Result.Config -> page(r.message, false)
            is Uploader.Result.Retry -> page(r.message, false)
            else -> page("ما صار شي", false)
        }
    }

    private fun page(msg: String?, ok: Boolean = true): String {
        val tick = { b: Boolean -> if (b) "<b class=ok>✓</b>" else "<b class=no>✗</b>" }
        val notice = msg?.let { "<p class='msg ${if (ok) "good" else "bad"}'>${esc(it)}</p>" } ?: ""
        return """<!doctype html><html lang=ar dir=rtl><head><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1">
<title>إعداد ميزاني TV</title><style>
body{font-family:system-ui,Segoe UI,Tahoma,sans-serif;background:#0b1412;color:#e6efec;margin:0;padding:16px;line-height:1.6}
main{max-width:520px;margin:auto}h1{font-size:1.3rem;margin:.2em 0}h2{font-size:1rem;margin:1.2em 0 .4em;color:#9fd9cf}
label{display:block;margin:.6em 0 .2em;font-size:.92rem;color:#b3c3be}input,select{width:100%;box-sizing:border-box;padding:12px;border-radius:12px;border:1px solid #23352f;background:#12201d;color:#e6efec;font-size:1rem}
.row{display:grid;grid-template-columns:1fr 1fr;gap:10px}button{margin-top:16px;width:100%;padding:14px;border:0;border-radius:14px;background:#2dd4bf;color:#052520;font-size:1.05rem;font-weight:700}
button.sec{background:#182a26;color:#e6efec;border:1px solid #23352f}.msg{padding:12px;border-radius:12px}.good{background:#12301f}.bad{background:#3a1515}
.ok{color:#4ade80}.no{color:#f87171}.muted{color:#8a9c96;font-size:.85rem}ol{padding-inline-start:20px;color:#b3c3be;font-size:.9rem}code{direction:ltr;unicode-bidi:isolate}
.st{background:#12201d;border-radius:12px;padding:10px 14px;border:1px solid #23352f}</style></head><body><main>
<h1>إعداد ميزاني TV</h1>$notice
<div class=st>${tick(store.token.isNotBlank())} توكن GitHub &nbsp; ${tick(store.hasKey())} كلمة مرور اللوحة &nbsp; ${tick(store.heightCm > 0)} الطول<br>
<span class=muted>الميزان: ${esc(store.scaleName.ifBlank { "ما انلقى للحين" })} ${esc(store.scaleMac)} · القياسات: ${store.mine().size}</span></div>
<form method=post action=/save>
<label>الرقم السري (مكتوب على شاشة التلفزيون)</label><input name=pin inputmode=numeric required autocomplete=off>
<h2>الربط مع موقعك</h2>
<label>توكن GitHub ${if (store.token.isNotBlank()) "(محفوظ — اتركه فاضي لو ما تبي تغيّره)" else ""}</label><input name=token type=password autocomplete=off placeholder="github_pat_…">
<ol><li>افتح <a style=color:#2dd4bf href="https://github.com/settings/personal-access-tokens/new">github.com/settings/personal-access-tokens/new</a></li>
<li>Repository access ← Only select repositories ← <b>Whoop</b></li><li>Permissions ← Repository ← <b>Contents: Read and write</b></li>
<li>Generate token وانسخه هنا</li></ol>
<label>المستودع</label><input name=repo value="${esc(store.repo)}" dir=ltr>
<label>كلمة مرور لوحة WHOOP (نفس DASHBOARD_PASSWORD) ${if (store.hasKey()) "(محفوظة)" else ""}</label><input name=password type=password autocomplete=off>
<p class=muted>كلمة المرور ما تنحفظ؛ التطبيق يشتق منها مفتاح التشفير بس. الحفظ ياخذ كم ثانية.</p>
<h2>بياناتك (عشان الميزان يحسب الدهون صح)</h2>
<div class=row><div><label>الطول (سم)</label><input name=height inputmode=numeric value="${if (store.heightCm > 0) store.heightCm else ""}"></div>
<div><label>العمر</label><input name=age inputmode=numeric value="${if (store.age > 0) store.age else ""}"></div></div>
<label>الجنس</label><select name=sex><option value=m ${if (!store.female) "selected" else ""}>ذكر</option><option value=f ${if (store.female) "selected" else ""}>أنثى</option></select>
<h2>نطاق وزنك (اختياري)</h2>
<p class=muted>الميزان يوزن أي أحد في البيت. لو حطيت نطاق، أي وزن برّاه ما ينرفع. فاضي = يقارن مع آخر أوزانك (±5 كجم).</p>
<div class=row><div><label>من</label><input name=min inputmode=decimal value="${if (store.minKg > 0) store.minKg else ""}"></div>
<div><label>إلى</label><input name=max inputmode=decimal value="${if (store.maxKg > 0) store.maxKg else ""}"></div></div>
<label><input type=checkbox name=forget value=1 style="width:auto"> انسَ الميزان المحفوظ ودوّر من جديد</label>
<button>احفظ</button></form>
<form method=post action=/test><label>الرقم السري</label><input name=pin inputmode=numeric required autocomplete=off><button class=sec>إرسال تجربة لموقعك</button></form>
</main></body></html>"""
    }
}
