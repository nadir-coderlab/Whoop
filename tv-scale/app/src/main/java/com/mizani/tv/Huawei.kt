package com.mizani.tv

import java.util.Calendar
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * بروتوكول ميزان هواوي Body Fat Scale AH100 / CH100 (خدمة 0xFAA0).
 *
 * كل البيانات بين الجوال/التلفزيون والميزان «مخلوطة» (XOR) بعنوان MAC الميزان،
 * وقياس الوزن نفسه فوقها AES-128-CTR بمفتاح يتكوّن من رمز التحقق.
 * ترتيب البايتات مأخوذ من ملاحظات مشروع openScale المفتوح، والكود مكتوب من الصفر.
 *
 * الكلاس هذا ما يعرف شي عن البلوتوث: ياخذ البايتات ويرجع وش نكتب للميزان ووش القراءة.
 */
object Huawei {
    val SERVICE: UUID = uuid16(0xFAA0)
    val CHAR_TX: UUID = uuid16(0xFAA1)   // من التلفزيون للميزان (كتابة)
    val CHAR_RX: UUID = uuid16(0xFAA2)   // من الميزان للتلفزيون (إشعارات)
    val CCCD: UUID = uuid16(0x2902)

    private val INITIAL_KEY = hex("3DA2784AFB87B12A980FDE3456732156")
    val IV = hex("4EF764322FDA7632123DEB8790FEA219")

    fun uuid16(n: Int): UUID = UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", n))

    fun hex(s: String): ByteArray {
        val h = s.filter { it.isLetterOrDigit() }
        return ByteArray(h.length / 2) { i -> h.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    fun toHex(b: ByteArray): String = b.joinToString(" ") { String.format("%02x", it.toInt() and 0xFF) }

    /** "5C:CA:D3:14:2B:B3" ← → 6 بايت */
    fun parseMac(s: String?): ByteArray? {
        val h = (s ?: "").filter { it.isLetterOrDigit() }
        if (h.length != 12 || !h.all { it in "0123456789abcdefABCDEF" }) return null
        val b = hex(h)
        return if (b.all { it.toInt() == 0 } || b.all { it.toInt() == -1 }) null else b
    }

    /** اسم الإعلان: AH100 أو CH100 (مع احتمال بادئة HUAWEI/HONOR أو لاحقة) — مو CH100S */
    fun isHuaweiName(name: String?): Boolean {
        val n = (name ?: "").replace("\u0000", "").trim().uppercase()
        val c = setOf(n, n.removePrefix("HUAWEI ").trim(), n.removePrefix("HONOR ").trim(),
            n.substringBefore('-'), n.substringBefore('_'), n.substringBefore(' '))
        return "AH100" in c || "CH100" in c
    }

    fun xorMac(data: ByteArray, mac: ByteArray): ByteArray =
        ByteArray(data.size) { i -> (data[i].toInt() xor mac[i % mac.size].toInt()).toByte() }

    fun aesCtr(data: ByteArray, key: ByteArray, iv: ByteArray = IV): ByteArray {
        val c = Cipher.getInstance("AES/CTR/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return c.doFinal(data)
    }

    fun authToken(userId: Int): ByteArray {
        val a = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x00, (userId and 0xFF).toByte())
        a[5] = a.fold(0) { x, y -> x xor (y.toInt() and 0xFF) }.toByte()
        return a
    }

    fun magicKey(auth: ByteArray, mac: ByteArray): ByteArray = xorMac(auth, mac) + INITIAL_KEY.copyOfRange(7, 16)

    fun plain(cmd: Int, payload: ByteArray, mac: ByteArray, len: Int = payload.size + 1): ByteArray =
        byteArrayOf(0xDB.toByte(), len.toByte(), cmd.toByte()) + xorMac(payload, mac)

    fun encrypted(cmd: Int, payload: ByteArray, key: ByteArray, mac: ByteArray, len: Int = payload.size): ByteArray =
        byteArrayOf(0xDC.toByte(), len.toByte(), cmd.toByte()) + xorMac(aesCtr(payload, key), mac)

    data class Measurement(val userId: Int, val weightKg: Double, val fatPct: Double, val impedance: Int?,
                           val timeMs: Long?, val valid: Boolean)

    private fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

    /** نص القياس بعد فك التشفير: [مستخدم][وزن/10][دهون/10][سنة][شهر][يوم][س][د][ث][يوم الأسبوع][مقاومة] */
    fun decode(frame: ByteArray, key: ByteArray, mac: ByteArray): Measurement? {
        if (frame.size < 3 + 15) return null
        val p = aesCtr(xorMac(frame.copyOfRange(3, frame.size), mac), key)
        val y = u16(p, 5); val mo = p[7].toInt() and 0xFF; val d = p[8].toInt() and 0xFF
        val h = p[9].toInt() and 0xFF; val mi = p[10].toInt() and 0xFF; val s = p[11].toInt() and 0xFF
        val ok = y in 2000..2100 && mo in 1..12 && d in 1..31 && h < 24 && mi < 60 && s < 60
        val time = if (ok) Calendar.getInstance().apply { clear(); set(y, mo - 1, d, h, mi, s) }.timeInMillis else null
        val imp = u16(p, 13)
        return Measurement(p[0].toInt() and 0xFF, u16(p, 1) / 10.0, u16(p, 3) / 10.0,
            if (imp in 1..3999) imp else null, time, ok)
    }

    data class Profile(val heightCm: Int = 170, val age: Int = 30, val female: Boolean = false, val lastWeightKg: Double? = null)

    data class Reading(val timeMs: Long, val weightKg: Double, val fatPct: Double?, val impedance: Int?, val fromHistory: Boolean)

    class Out {
        val writes = mutableListOf<Pair<ByteArray, String>>()
        val readings = mutableListOf<Reading>()
        var note: String = ""
        var status: String? = null       // نص يظهر على الشاشة
        var error: String? = null        // "auth" | "decode"
    }

    /**
     * جلسة اتصال واحدة. تمشي مثل التطبيق الرسمي:
     * تحقق ← (ربط لو رفض) ← الوحدة والساعة وبيانات الشخص ← قياس ← تأكيد ← (سجل القياسات اللي فاتت)
     */
    class Session(private val mac: ByteArray, private var profile: Profile, private val now: () -> Long = System::currentTimeMillis,
                  userId: Int = 1, private val readHistory: Boolean = true) {
        private val auth = authToken(userId)
        private var key: ByteArray? = null
        var authorised = false; private set
        private var authSent = false
        private var tries = 0
        private var binds = 0
        private var pending: Pair<ByteArray, Int>? = null
        private var historyAsked = false

        private fun send(out: Out, frame: ByteArray, label: String) { out.writes += frame to label }
        private fun sendAuth(out: Out) { authSent = true; send(out, plain(36, auth, mac), "تحقق") }

        private fun userInfo(out: Out) {
            val k = key ?: return
            val h = profile.heightCm.coerceIn(90, 220)
            val age = profile.age.coerceIn(10, 99)
            val w = profile.lastWeightKg ?: (22.0 * (h / 100.0) * (h / 100.0))
            val wt = Math.round(w * 10).toInt().coerceAtLeast(1)
            val p = auth + byteArrayOf(((age and 0x7F) or (if (profile.female) 0x80 else 0)).toByte(), h.toByte(), 0,
                (wt and 0xFF).toByte(), (wt shr 8).toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x1C, 0xE2.toByte())
            send(out, encrypted(9, p, k, mac, p.size - 2), "بيانات الشخص")
        }

        private fun clock(out: Out) {
            val c = Calendar.getInstance().apply { timeInMillis = now() }
            val y = c.get(Calendar.YEAR)
            val dow = ((c.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1   // الإثنين = 1
            send(out, plain(8, byteArrayOf((y and 0xFF).toByte(), (y shr 8).toByte(), (c.get(Calendar.MONTH) + 1).toByte(),
                c.get(Calendar.DAY_OF_MONTH).toByte(), c.get(Calendar.HOUR_OF_DAY).toByte(), c.get(Calendar.MINUTE).toByte(),
                c.get(Calendar.SECOND).toByte(), dow.toByte()), mac), "الساعة")
        }

        fun setProfile(p: Profile): Out { profile = p; return Out().also { if (authorised) userInfo(it) } }

        /** لو ما وصلت «صحى» بعد الاتصال بشوي نبدأ التحقق بنفسنا */
        fun kick(): Out = Out().also { if (!authorised && !authSent) { it.note = "نبدأ التحقق"; sendAuth(it) } }

        private fun measurement(frame: ByteArray, op: Int, out: Out) {
            val m = decode(frame, key ?: return, mac)
            if (m == null || !m.valid || m.weightKg !in 2.0..300.0) {
                out.error = "decode"; out.note = "فك التشفير طلع أرقام غلط"; return
            }
            profile = profile.copy(lastWeightKg = m.weightKg)
            out.readings += Reading(m.timeMs ?: now(), m.weightKg, m.fatPct.takeIf { it > 0 && it < 75 }, m.impedance, op == 0x10)
            if (op == 0x10) {
                send(out, plain(11, byteArrayOf(1), mac), "السجل التالي")
            } else {
                send(out, plain(19, byteArrayOf(0), mac), "تأكيد القياس")
                if (readHistory && !historyAsked) {   // قياسات صارت والتلفزيون طافي
                    historyAsked = true
                    send(out, plain(11, auth + byteArrayOf(auth.fold(0) { x, y -> x xor (y.toInt() and 0xFF) }.toByte()), mac, 7), "طلب السجل")
                }
            }
        }

        fun onData(d: ByteArray): Out {
            val out = Out()
            if (d.size < 3) return out
            val op = d[2].toInt() and 0xFF
            val tail = xorMac(d.copyOfRange(3, d.size), mac)
            val encryptedFrame = (d[0].toInt() and 0xFF) == 0xBC
            when (op) {
                0x00 -> { out.note = "الميزان صحى"; if (!authorised) sendAuth(out) }
                0x26 -> when {
                    tail.isNotEmpty() && tail[0].toInt() == 1 && authorised -> out.note = "تحقق مكرر"
                    tail.isNotEmpty() && tail[0].toInt() == 1 -> {
                        authorised = true
                        key = magicKey(auth, mac)
                        out.note = "تم التحقق"; out.status = "متصل ✓ — اطلع على الميزان"
                        send(out, plain(2, byteArrayOf(1), mac), "الوحدة كجم")
                        clock(out); userInfo(out)
                        send(out, plain(12, ByteArray(0), mac), "الإصدار")
                    }
                    tries++ < 2 -> { out.note = "رفض التحقق، نعيد"; sendAuth(out) }
                    binds++ < 2 -> { out.note = "نربط الميزان أول مرة"; out.status = "نربط الميزان أول مرة…"; send(out, plain(37, auth, mac), "ربط") }
                    else -> { out.error = "auth"; out.note = "الميزان رفض التحقق" }
                }
                0x27 -> { out.note = "تم الربط"; tries = 0; sendAuth(out) }
                0x20 -> if (authorised) userInfo(out)
                0x0F -> { out.note = "ثبت الوزن، يحلل الدهون"; out.status = "يحلل الدهون… لا تنزل" }
                0x0E, 0x10 -> if (encryptedFrame) {
                    if (key == null) out.note = "قياس قبل التحقق"
                    else if (tail.size >= 15) { pending = null; measurement(d, op, out) }
                    else pending = d to op
                }
                0x8E, 0x90 -> if (encryptedFrame) pending?.let { p -> pending = null; if (key != null) measurement(p.first, p.second, out) }
                0x01 -> out.note = "الميزان بينام"
                0x19 -> out.note = "خلص السجل"
                0x13 -> { out.note = "الوزن فوق حد الميزان"; out.status = out.note }
                0x14 -> { out.note = "بطارية الميزان ضعيفة"; out.status = "بطارية الميزان ضعيفة — غيّرها" }
                0x15 -> { out.note = "خطأ قياس"; out.status = "الميزان ما قدر يقيس — اطلع حافي وثابت" }
                else -> out.note = "رد 0x" + String.format("%02x", op)
            }
            return out
        }
    }
}
