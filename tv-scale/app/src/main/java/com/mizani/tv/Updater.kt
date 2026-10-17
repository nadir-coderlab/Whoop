package com.mizani.tv

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.net.HttpURLConnection
import java.net.URL

/**
 * تحديث التطبيق من داخله: يشيك على version.json في release «tv-latest»، ولو فيه نسخة أجدد
 * ينزّلها ويركّبها. أندرويد يطلب ضغطة «تثبيت» وحدة بالريموت (حماية من النظام نفسه).
 */
object Updater {
    private const val BASE = "https://github.com/nadir-coderlab/Whoop/releases/download/tv-latest/"
    @Volatile var latest: Int = 0
    @Volatile var latestName: String = ""
    @Volatile var busy = false

    fun installedCode(ctx: Context): Long = try {
        val i = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) i.longVersionCode else @Suppress("DEPRECATION") i.versionCode.toLong()
    } catch (e: Exception) { 0 }

    fun installedName(ctx: Context): String = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" } catch (e: Exception) { "" }

    private fun open(url: String): HttpURLConnection {
        var u = URL(url)
        repeat(5) {
            val c = u.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000; c.readTimeout = 60_000
            c.setRequestProperty("User-Agent", "mizani-tv")
            val code = c.responseCode
            if (code in 300..399) { u = URL(u, c.getHeaderField("Location")); c.disconnect() } else return c
        }
        throw IllegalStateException("تحويلات كثيرة")
    }

    /** يرجع true لو فيه نسخة أجدد من المثبّتة */
    fun check(ctx: Context): Boolean {
        val c = open(BASE + "version.json")
        if (c.responseCode != 200) { c.disconnect(); throw IllegalStateException("version.json رد ${c.responseCode}") }
        val txt = c.inputStream.bufferedReader().readText(); c.disconnect()
        latest = Regex("\"versionCode\"\\s*:\\s*(\\d+)").find(txt)?.groupValues?.get(1)?.toInt() ?: 0
        latestName = Regex("\"versionName\"\\s*:\\s*\"([^\"]+)\"").find(txt)?.groupValues?.get(1) ?: ""
        return latest > installedCode(ctx)
    }

    fun canInstall(ctx: Context): Boolean = Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    /** يفتح صفحة «السماح بتثبيت التطبيقات» لهالتطبيق (مرة وحدة بس) */
    fun askInstallPermission(ctx: Context) {
        try {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Status.log("افتح الإعدادات ← الأمان ← المصادر غير المعروفة واسمح لـ «ميزاني TV»")
        }
    }

    /** ينزّل النسخة الجديدة ويبدأ التثبيت (يشتغل في خيط خلفي) */
    fun downloadAndInstall(ctx: Context) {
        if (busy) return
        busy = true
        try {
            Status.set("ينزّل التحديث…", latestName)
            val c = open(BASE + "MizaniTV.apk")
            if (c.responseCode != 200) throw IllegalStateException("التنزيل رد ${c.responseCode}")
            val pi = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(ctx.packageName)
            val id = pi.createSession(params)
            pi.openSession(id).use { s ->
                s.openWrite("MizaniTV.apk", 0, c.contentLengthLong).use { out -> c.inputStream.use { it.copyTo(out) } }
                c.disconnect()
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val cb = PendingIntent.getBroadcast(ctx, id, Intent(ctx, InstallReceiver::class.java), flags)
                s.commit(cb.intentSender)
            }
            Status.set("اضغط «تثبيت» بالريموت", "عشان يتحدّث التطبيق")
            Status.log("نزل التحديث $latestName — بانتظار موافقتك على الشاشة")
        } catch (e: Exception) {
            Status.set("ما قدرت أحدّث", e.message ?: e.javaClass.simpleName)
            Status.log("التحديث: ${e.message}")
        } finally { busy = false }
    }
}

/** زر «حدّث التطبيق» (من الشاشة أو من صفحة الجوال). يشتغل في خيط خلفي ويرجع رسالة للمستخدم. */
fun Updater.run(ctx: Context): String {
    if (!canInstall(ctx)) {
        askInstallPermission(ctx)
        return "أول مرة بس: اسمح لـ «ميزاني TV» بتثبيت التطبيقات من الشاشة اللي انفتحت، وبعدها اضغط «حدّث» مرة ثانية"
    }
    return try {
        if (!check(ctx)) "عندك أحدث نسخة (${installedName(ctx)}) ✓".also { Status.update = it; Status.changed() }
        else { downloadAndInstall(ctx); "نزل التحديث $latestName — اضغط «تثبيت» بالريموت على التلفزيون" }
    } catch (e: Exception) { "ما قدرت أشيك على التحديث: ${e.message}" }
}

/** يستقبل نتيجة التثبيت؛ لو النظام يبي موافقة يفتح شاشة «تثبيت» */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                              else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { try { ctx.startActivity(it) } catch (_: Exception) {} }
            }
            PackageInstaller.STATUS_SUCCESS -> Status.log("تحدّث التطبيق ✓")
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "انلغى"
                Status.set("ما تحدّث التطبيق", msg); Status.log("التحديث: $msg")
            }
        }
    }
}
