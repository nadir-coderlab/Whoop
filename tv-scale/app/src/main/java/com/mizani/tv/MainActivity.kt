package com.mizani.tv

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * شاشة التلفزيون: الحالة، آخر وزن، ورابط + رمز QR تفتحه من جوالك عشان تضبط الإعدادات.
 * تتحكم فيها بالريموت (أسهم + OK). ما تحتاج تفتحها بعد الإعداد — الخدمة تشتغل لحالها.
 */
class MainActivity : Activity() {
    private lateinit var store: Store
    private var server: ConfigServer? = null
    private val pin = String.format(Locale.US, "%04d", Random.nextInt(10000))

    private lateinit var weight: TextView
    private lateinit var weightSub: TextView
    private lateinit var state: TextView
    private lateinit var detail: TextView
    private lateinit var upload: TextView
    private lateinit var setup: TextView
    private lateinit var url: TextView
    private lateinit var qr: ImageView
    private lateinit var log: TextView
    private val listener: () -> Unit = { refresh() }
    private val dateFmt = SimpleDateFormat("EEEE d MMMM · h:mm a", Locale("ar"))

    private val bg = Color.parseColor("#0B1412")
    private val card = Color.parseColor("#12201D")
    private val line = Color.parseColor("#23352F")
    private val ink = Color.parseColor("#E6EFEC")
    private val muted = Color.parseColor("#8A9C96")
    private val accent = Color.parseColor("#2DD4BF")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        setContentView(build())
        askPermissions()
    }

    override fun onStart() {
        super.onStart()
        Status.listen(listener)
        server = ConfigServer(applicationContext, store, pin) { runOnUiThread { refresh(); ScaleService.start(this, ScaleService.ACTION_PROFILE) } }.also { it.start() }
        if (ScaleService.hasPermissions(this)) ScaleService.start(this)
        refresh()
    }

    override fun onStop() {
        Status.unlisten(listener)
        server?.stop(); server = null
        super.onStop()
    }

    // ───────────── الأذونات والبلوتوث ─────────────

    private fun askPermissions() {
        val need = if (Build.VERSION.SDK_INT >= 31) mutableListOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        val missing = need.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1) else afterPermissions()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        afterPermissions()
    }

    @SuppressLint("MissingPermission")
    private fun afterPermissions() {
        if (!ScaleService.hasPermissions(this)) { Status.set("يحتاج إذن البلوتوث", "اضغط «دوّر على الميزان» ووافق"); return }
        val a: BluetoothAdapter? = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (a != null && !a.isEnabled) try { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) } catch (_: Exception) {}
        ScaleService.start(this)
    }

    // ───────────── الواجهة ─────────────

    private fun text(size: Float, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
        textDirection = View.TEXT_DIRECTION_RTL
    }

    private fun panel() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(20), dp(24), dp(20))
        background = GradientDrawable().apply { setColor(card); cornerRadius = dp(18).toFloat(); setStroke(dp(1), line) }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 16f; setTextColor(ink); isFocusable = true
        val normal = GradientDrawable().apply { setColor(Color.parseColor("#182A26")); cornerRadius = dp(12).toFloat(); setStroke(dp(1), line) }
        val focus = GradientDrawable().apply { setColor(Color.parseColor("#0F766E")); cornerRadius = dp(12).toFloat(); setStroke(dp(3), accent) }
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focus)
            addState(intArrayOf(android.R.attr.state_pressed), focus)
            addState(intArrayOf(), normal)
        }
        setPadding(dp(18), dp(10), dp(18), dp(10))
        setOnClickListener { onClick() }
    }

    private fun build(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(bg); setPadding(dp(40), dp(28), dp(40), dp(20))
        }
        root.addView(text(26f, ink, true).apply { text = "ميزاني TV" })
        root.addView(text(14f, muted).apply { text = "يسمع لميزان هواوي ويرفع وزنك لموقعك كل صباح — بدون جوال" })

        val cols = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(18), 0, dp(12)) }
        val left = panel()
        weight = text(64f, ink, true).apply { text = "--.-" }
        weightSub = text(15f, muted)
        state = text(20f, accent, true).apply { setPadding(0, dp(14), 0, 0) }
        detail = text(15f, muted)
        upload = text(15f, ink).apply { setPadding(0, dp(8), 0, 0) }
        left.addView(text(15f, muted).apply { text = "آخر وزن" })
        left.addView(weight); left.addView(weightSub); left.addView(state); left.addView(detail); left.addView(upload)
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(16), 0, 0) }
        val gap = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(10) }
        val discover = button("دوّر على الميزان الحين") { askPermissions(); ScaleService.start(this, ScaleService.ACTION_DISCOVER) }
        btns.addView(discover, gap)
        btns.addView(button("ارفع الحين") { ScaleService.start(this, ScaleService.ACTION_UPLOAD) }, gap)
        btns.addView(button("ارفع كل القياسات مرة ثانية") { ScaleService.start(this, ScaleService.ACTION_RESEND) }, gap)
        btns.addView(button("حدّث التطبيق") {
            Status.update = "يشيك على التحديث…"; refresh()
            Thread { val m = Updater.run(this); Status.update = m; Status.changed() }.start()
        }, gap)
        left.addView(btns)
        cols.addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.6f).apply { marginEnd = dp(20) })

        val right = panel().apply { gravity = Gravity.CENTER_HORIZONTAL }
        right.addView(text(18f, ink, true).apply { text = "اضبطه من جوالك" })
        right.addView(text(14f, muted).apply { text = "صوّر الرمز أو افتح الرابط (نفس الواي فاي)" })
        qr = ImageView(this)
        right.addView(qr, LinearLayout.LayoutParams(dp(190), dp(190)).apply { topMargin = dp(10); bottomMargin = dp(8) })
        url = text(16f, accent, true).apply { textDirection = View.TEXT_DIRECTION_LTR }
        right.addView(url)
        right.addView(text(16f, ink).apply { text = "الرقم السري: $pin" })
        setup = text(14f, muted).apply { setPadding(0, dp(10), 0, 0) }
        right.addView(setup)
        cols.addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(cols)

        log = text(12f, muted).apply { typeface = Typeface.MONOSPACE; textDirection = View.TEXT_DIRECTION_LTR; maxLines = 9 }
        root.addView(log)
        discover.post { discover.requestFocus() }
        return root
    }

    private fun refresh() {
        val last = Status.lastWeight ?: store.mine().lastOrNull()
        if (last != null) {
            weight.text = String.format(Locale.US, "%.1f", last.w) + " كجم"
            weightSub.text = dateFmt.format(Date(last.t)) + (last.fat?.let { "  ·  دهون $it%" } ?: "") + (if (last.sent) "  ·  انرفع ✓" else "  ·  بانتظار الرفع")
        } else { weight.text = "--.-"; weightSub.text = "ما فيه قياس للحين" }
        state.text = Status.state
        detail.text = Status.detail
        upload.text = listOf(Status.upload.ifBlank { if (store.lastError.isNotBlank()) "آخر خطأ رفع: ${store.lastError}" else "" },
            "النسخة ${Updater.installedName(this)}" + (if (Status.update.isNotBlank()) " · ${Status.update}" else "")).filter { it.isNotBlank() }.joinToString("\n")
        val ip = localIp()
        val link = if (ip != null) "http://$ip:${ConfigServer.PORT}" else "البوكس مو متصل بالواي فاي"
        if (url.text != link) { url.text = link; qr.setImageBitmap(if (ip != null) qrBitmap(link) else null) }
        val ok = { b: Boolean -> if (b) "✓" else "✗" }
        setup.text = "${ok(store.token.isNotBlank())} توكن GitHub   ${ok(store.hasKey())} كلمة مرور اللوحة   ${ok(store.heightCm > 0)} الطول\n" +
            "الميزان: " + (if (store.scaleMac.isNotBlank()) "${store.scaleName} ${store.scaleMac}" else "ما انلقى للحين — اطلع عليه")
        log.text = Status.logText(9)
    }

    private fun localIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    } catch (_: Exception) { null }

    private fun qrBitmap(s: String): Bitmap {
        val m = QRCodeWriter().encode(s, BarcodeFormat.QR_CODE, 400, 400, mapOf(EncodeHintType.MARGIN to 1))
        val px = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888)
    }
}
