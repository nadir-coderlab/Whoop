package com.mizani.tv

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * تشفير القراءات قبل ما تطلع من التلفزيون — نفس اللي يفكه scale.py في مستودع Whoop:
 * AES-256-GCM بمفتاح PBKDF2-SHA256 من كلمة مرور لوحة WHOOP (DASHBOARD_PASSWORD).
 *
 * كلمة المرور نفسها ما تنحفظ: نشتق المفتاح مرة وحدة وقت الإعداد ونحفظ المفتاح والملح.
 */
object Seal {
    const val ITER = 200_000
    private val rnd = SecureRandom()

    fun randomBytes(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }

    fun deriveKey(password: String, salt: ByteArray, iterations: Int = ITER): ByteArray {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return f.generateSecret(PBEKeySpec(password.toCharArray(), salt, iterations, 256)).encoded
    }

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)

    /** يرجع JSON بشكل {v, iter, salt, iv, ct} */
    fun seal(plainJson: String, key: ByteArray, salt: ByteArray, iterations: Int = ITER): String {
        val iv = randomBytes(12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val ct = c.doFinal(plainJson.toByteArray(Charsets.UTF_8))
        return "{\"v\":1,\"iter\":$iterations,\"salt\":\"${b64(salt)}\",\"iv\":\"${b64(iv)}\",\"ct\":\"${b64(ct)}\"}"
    }

    /** للاختبارات: فك نفس الشكل */
    fun open(salt: String, iv: String, ct: String, key: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, Base64.getDecoder().decode(iv)))
        return String(c.doFinal(Base64.getDecoder().decode(ct)), Charsets.UTF_8)
    }

    /** نص القراءات المرسَل (قبل التشفير) */
    fun readingsJson(readings: List<Huawei.Reading>, device: String, app: String): String {
        val items = readings.joinToString(",") { r ->
            buildString {
                append("{\"t\":").append(r.timeMs).append(",\"w\":").append(fmt(r.weightKg))
                r.fatPct?.let { append(",\"fat\":").append(fmt(it)) }
                r.impedance?.let { append(",\"imp\":").append(it) }
                append(",\"dev\":\"").append(device.filter { it.isLetterOrDigit() || it == ' ' || it == '-' }.take(24)).append("\"}")
            }
        }
        return "{\"readings\":[$items],\"app\":\"${app.filter { it.isLetterOrDigit() || it in "./- " }}\",\"sent\":${System.currentTimeMillis()}}"
    }

    private fun fmt(d: Double) = String.format(java.util.Locale.US, "%.2f", d).trimEnd('0').trimEnd('.')
}
