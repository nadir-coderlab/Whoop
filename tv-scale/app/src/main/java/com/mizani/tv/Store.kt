package com.mizani.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/** الإعدادات + كل القراءات (تنحفظ داخل التطبيق، وما تطلع إلا مشفّرة) */
class Store(ctx: Context) {
    private val p = ctx.getSharedPreferences("mizani", Context.MODE_PRIVATE)
    private val file = File(ctx.filesDir, "readings.json")

    var token: String get() = p.getString("token", "")!!; set(v) = p.edit().putString("token", v.trim()).apply()
    var repo: String get() = p.getString("repo", "nadir-coderlab/Whoop")!!; set(v) = p.edit().putString("repo", v.trim()).apply()
    var keyB64: String get() = p.getString("key", "")!!; set(v) = p.edit().putString("key", v).apply()
    var saltB64: String get() = p.getString("salt", "")!!; set(v) = p.edit().putString("salt", v).apply()
    var iter: Int get() = p.getInt("iter", Seal.ITER); set(v) = p.edit().putInt("iter", v).apply()
    var heightCm: Int get() = p.getInt("height", 0); set(v) = p.edit().putInt("height", v).apply()
    var age: Int get() = p.getInt("age", 0); set(v) = p.edit().putInt("age", v).apply()
    var female: Boolean get() = p.getBoolean("female", false); set(v) = p.edit().putBoolean("female", v).apply()
    var minKg: Float get() = p.getFloat("min", 0f); set(v) = p.edit().putFloat("min", v).apply()
    var maxKg: Float get() = p.getFloat("max", 0f); set(v) = p.edit().putFloat("max", v).apply()
    var scaleMac: String get() = p.getString("mac", "")!!; set(v) = p.edit().putString("mac", v).apply()
    var scaleName: String get() = p.getString("scaleName", "")!!; set(v) = p.edit().putString("scaleName", v).apply()
    var lastUploadOk: Long get() = p.getLong("lastUpload", 0); set(v) = p.edit().putLong("lastUpload", v).apply()
    var lastError: String get() = p.getString("lastError", "")!!; set(v) = p.edit().putString("lastError", v).apply()

    fun hasKey() = keyB64.isNotBlank() && saltB64.isNotBlank()
    fun configured() = token.isNotBlank() && hasKey()

    fun profile(): Huawei.Profile = Huawei.Profile(
        heightCm = if (heightCm in 90..220) heightCm else 170,
        age = if (age in 10..99) age else 30,
        female = female,
        lastWeightKg = mine().lastOrNull()?.w)

    data class Rec(val t: Long, val w: Double, val fat: Double?, val imp: Int?, val dev: String,
                   var sent: Boolean = false, val other: Boolean = false)

    @Synchronized fun all(): MutableList<Rec> {
        if (!file.exists()) return mutableListOf()
        return try {
            val a = JSONArray(file.readText())
            MutableList(a.length()) { i ->
                val o = a.getJSONObject(i)
                Rec(o.getLong("t"), o.getDouble("w"), o.optDouble("fat").takeIf { !it.isNaN() },
                    o.optInt("imp", -1).takeIf { it > 0 }, o.optString("dev", ""), o.optBoolean("sent"), o.optBoolean("other"))
            }
        } catch (e: Exception) { mutableListOf() }
    }

    @Synchronized fun save(list: List<Rec>) {
        val a = JSONArray()
        list.sortedBy { it.t }.takeLast(5000).forEach { r ->
            a.put(JSONObject().put("t", r.t).put("w", r.w).put("dev", r.dev).put("sent", r.sent).put("other", r.other).apply {
                r.fat?.let { put("fat", it) }; r.imp?.let { put("imp", it) }
            })
        }
        val tmp = File(file.path + ".tmp"); tmp.writeText(a.toString()); tmp.renameTo(file)
    }

    fun mine(): List<Rec> = all().filter { !it.other }.sortedBy { it.t }

    /**
     * هل الوزن هذا لصاحب التطبيق؟ (الميزان يوزن أي أحد في البيت)
     * لو حددت نطاق في الإعدادات نستخدمه، وإلا: قريب من وسيط آخر 10 قياسات (±5 كجم).
     */
    fun isMine(w: Double): Boolean {
        if (minKg > 0 && maxKg > minKg) return w in minKg.toDouble()..maxKg.toDouble()
        val last = mine().takeLast(10).map { it.w }.sorted()
        if (last.size < 3) return true
        return abs(w - last[last.size / 2]) <= 5.0
    }

    /** يضيف القراءة لو جديدة (نفس الوقت أو خلال دقيقة بنفس الوزن = تكرار) */
    @Synchronized fun add(r: Huawei.Reading, dev: String, other: Boolean): Rec? {
        val list = all()
        if (list.any { it.t == r.timeMs || (abs(it.t - r.timeMs) < 60_000 && abs(it.w - r.weightKg) < 0.05) }) return null
        val rec = Rec(r.timeMs, r.weightKg, r.fatPct, r.impedance, dev, sent = false, other = other)
        list += rec; save(list)
        return rec
    }

    @Synchronized fun markSent(ts: Set<Long>) { save(all().onEach { if (it.t in ts) it.sent = true }) }
    @Synchronized fun markAllUnsent() { save(all().onEach { if (!it.other) it.sent = false }) }
}
