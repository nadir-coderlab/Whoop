package com.mizani.tv

import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * يرفع القراءات لمستودع Whoop عن طريق repository_dispatch (نوع "scale").
 * الحمولة مشفّرة، والـ workflow هناك يفكها بكلمة مرور اللوحة ويحدّث الصفحة.
 *
 * كل مرة نرسل اللي ما انرفع + آخر 30 يوم: لو ضاع إرسال قبل، اللي بعده يغطيه
 * (GitHub يخلي تشغيل واحد بالانتظار ويلغي اللي قبله).
 */
object Uploader {
    sealed class Result {
        object Nothing : Result()
        data class Ok(val count: Int) : Result()
        data class Config(val message: String) : Result()     // التوكن أو المستودع غلط — ما نعيد إلا بعد تعديل
        data class Retry(val message: String) : Result()      // نت أو GitHub — نعيد بعدين
    }

    fun send(store: Store, app: String, test: Boolean = false): Result {
        if (!store.configured()) return Result.Config("الإعدادات ناقصة: التوكن أو كلمة المرور")
        val mine = store.mine()
        val pending = mine.filter { !it.sent }
        if (pending.isEmpty() && !test) return Result.Nothing
        val since = System.currentTimeMillis() - 30L * 86_400_000
        val batch = if (test) emptyList() else (pending + mine.filter { it.t >= since }).distinctBy { it.t }.sortedBy { it.t }.takeLast(120)
        val json = Seal.readingsJson(batch.map { Huawei.Reading(it.t, it.w, it.fat, it.imp, false) }, store.scaleName.ifBlank { "AH100" }, app)
        val sealed = Seal.seal(json, Base64.getDecoder().decode(store.keyB64), Base64.getDecoder().decode(store.saltB64), store.iter)
        val body = "{\"event_type\":\"scale\",\"client_payload\":$sealed}"
        return try {
            val c = URL("https://api.github.com/repos/${store.repo}/dispatches").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true
            c.connectTimeout = 15_000; c.readTimeout = 20_000
            c.setRequestProperty("Authorization", "Bearer ${store.token}")
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            c.setRequestProperty("User-Agent", app)
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val err = if (code >= 400) (c.errorStream?.bufferedReader()?.readText() ?: "").take(200) else ""
            c.disconnect()
            when {
                code == 204 -> {
                    store.markSent(pending.map { it.t }.toSet())
                    store.lastUploadOk = System.currentTimeMillis(); store.lastError = ""
                    Result.Ok(pending.size)
                }
                code == 401 -> Result.Config("التوكن غلط أو انتهى (401)")
                code == 403 -> Result.Config("التوكن ما عنده صلاحية Contents: Read and write على ${store.repo} (403)")
                code == 404 -> Result.Config("ما لقيت المستودع ${store.repo} أو التوكن ما يشوفه (404)")
                code == 422 -> Result.Config("GitHub رفض الطلب (422) $err")
                else -> Result.Retry("GitHub رد $code")
            }
        } catch (e: Exception) {
            Result.Retry("ما فيه نت؟ (${e.javaClass.simpleName})")
        }
    }
}
