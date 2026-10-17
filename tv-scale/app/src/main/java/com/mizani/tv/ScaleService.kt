package com.mizani.tv

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.os.PowerManager
import android.net.wifi.WifiManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * خدمة تشتغل بالخلفية على بوكس التلفزيون من أول ما يشتغل:
 * تدور على ميزان هواوي ← تتصل لما يصحى (توقف عليه) ← تاخذ الوزن ← ترفعه مشفّر لموقع Whoop.
 */
@SuppressLint("MissingPermission")
class ScaleService : Service() {

    companion object {
        const val ACTION_DISCOVER = "com.mizani.tv.DISCOVER"
        const val ACTION_UPLOAD = "com.mizani.tv.UPLOAD"
        const val ACTION_RESEND = "com.mizani.tv.RESEND"
        const val ACTION_TEST = "com.mizani.tv.TEST"
        const val ACTION_PROFILE = "com.mizani.tv.PROFILE"
        private const val CHANNEL = "scale"
        private const val NOTIF_ID = 7

        fun start(ctx: Context, action: String? = null) {
            val i = Intent(ctx, ScaleService::class.java).setAction(action)
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (e: Exception) {
                Status.log("ما قدرت أشغّل الخدمة: ${e.javaClass.simpleName}")
            }
        }

        fun hasPermissions(ctx: Context): Boolean {
            val need = if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            return need.all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var store: Store
    private var adapter: BluetoothAdapter? = null

    private var scanning = false
    private var discoverUntil = 0L
    private var cooldownUntil = 0L
    private var scanStartedAt = 0L

    private var gatt: BluetoothGatt? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var session: Huawei.Session? = null
    private var busy = false
    private val ops = ArrayDeque<Pair<ByteArray, String>>()
    private var opRunning = false
    private var opSeq = 0
    private var gotReading = false
    private var wake: PowerManager.WakeLock? = null
    private var always: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var uploadBackoff = 0
    private val time = SimpleDateFormat("h:mm a", Locale("ar"))
    private val appTag by lazy { "mizani-tv/" + (try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        goForeground("يدوّر على الميزان")
        registerReceiver(btState, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        stayAwake()
        Status.lastWeight = store.mine().lastOrNull()
        main.postDelayed(periodic, 60_000)
        Status.log("الخدمة اشتغلت")
    }

    override fun onDestroy() {
        try { unregisterReceiver(btState) } catch (_: Exception) {}
        try { if (always?.isHeld == true) always?.release(); if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Exception) {}
        stopScan(); closeGatt()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCOVER -> { discoverUntil = System.currentTimeMillis() + 90_000; cooldownUntil = 0; Status.log("بحث عن الميزان (90 ثانية)"); stopScan() }
            ACTION_UPLOAD -> scheduleUpload(0)
            ACTION_RESEND -> { io.execute { store.markAllUnsent() }; scheduleUpload(500) }
            ACTION_TEST -> io.execute { reportUpload(Uploader.send(store, appTag, test = true), test = true) }
            ACTION_PROFILE -> session?.let { s -> apply(s.setProfile(store.profile())) }
        }
        ensureScan()
        scheduleUpload(3_000)
        return START_STICKY
    }

    /**
     * البوكس على الكهرباء دايم: نخلي المعالج والواي فاي صاحيين طول الوقت عشان البحث
     * عن الميزان والرفع ما يوقفون لو التلفزيون انطفى. (الاستهلاك بسيط جداً على بوكس.)
     */
    @Suppress("DEPRECATION")
    private fun stayAwake() {
        try {
            always = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mizani:always")
                .apply { setReferenceCounted(false); acquire() }
            wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "mizani:wifi").apply { setReferenceCounted(false); acquire() }
            Status.log("البوكس ما بينام (المعالج والواي فاي صاحيين)")
        } catch (e: Exception) { Status.log("ما قدرت أمنع النوم: ${e.javaClass.simpleName}") }
    }

    // ───────────── الإشعار (مطلوب للخدمات اللي تشتغل بالخلفية) ─────────────

    private fun goForeground(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "الميزان", NotificationManager.IMPORTANCE_MIN))
        val n = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this))
            .setContentTitle("ميزاني TV").setContentText(text).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setOngoing(true).build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(NOTIF_ID, n)
        } catch (e: Exception) {
            Status.set("يحتاج إذن البلوتوث", "افتح التطبيق ووافق على الأذونات")
            Status.log("ما قدرت أشتغل بالخلفية: ${e.javaClass.simpleName}")
        }
    }

    private val periodic = object : Runnable {
        override fun run() {
            // نعيد البحث كل 20 دقيقة (بعض الأجهزة توقف البحث الطويل)، ونحاول نرفع اللي ما انرفع
            if (scanning && System.currentTimeMillis() - scanStartedAt > 20 * 60_000) { stopScan(); ensureScan() }
            if (!scanning && !busy) ensureScan()
            scheduleUpload(0)
            main.postDelayed(this, 5 * 60_000)
        }
    }

    private val btState = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> { Status.log("البلوتوث اشتغل"); main.postDelayed({ ensureScan() }, 1500) }
                BluetoothAdapter.STATE_OFF -> { scanning = false; closeGatt(); Status.set("البلوتوث طافي", "شغّله من إعدادات البوكس") }
            }
        }
    }

    // ───────────── البحث عن الميزان ─────────────

    private fun ensureScan() {
        if (busy || scanning) return
        if (!hasPermissions(this)) { Status.set("يحتاج إذن البلوتوث", "افتح التطبيق ووافق على الأذونات"); return }
        val a = adapter
        if (a == null) { Status.set("البوكس ما فيه بلوتوث"); return }
        if (!a.isEnabled) { Status.set("البلوتوث طافي", "شغّله من إعدادات البوكس"); return }
        val now = System.currentTimeMillis()
        if (now < cooldownUntil) { main.postDelayed({ ensureScan() }, cooldownUntil - now + 200); return }
        val scanner = a.bluetoothLeScanner ?: return
        val discovering = now < discoverUntil
        val filters = when {
            discovering -> emptyList()
            store.scaleMac.isNotBlank() -> listOf(ScanFilter.Builder().setDeviceAddress(store.scaleMac).build())
            else -> listOf(
                ScanFilter.Builder().setDeviceName("AH100").build(),
                ScanFilter.Builder().setDeviceName("CH100").build(),
                ScanFilter.Builder().setServiceUuid(ParcelUuid(Huawei.SERVICE)).build())
        }
        // البوكس على الكهرباء دايم، فنبحث بأسرع وضع عشان نلقط الميزان أول ما يصحى
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(filters, settings, scanCb)
            scanning = true; scanStartedAt = now
            Status.set(if (discovering) "يدوّر على أي ميزان قريب…" else "يستنى الميزان", if (discovering) "اطلع على الميزان الحين" else "اطلع على الميزان وبيوصل الوزن لحاله")
            if (discovering) main.postDelayed({ if (scanning && System.currentTimeMillis() >= discoverUntil) { stopScan(); ensureScan() } }, discoverUntil - now + 100)
        } catch (e: Exception) {
            Status.log("فشل البحث: ${e.message}")
            main.postDelayed({ ensureScan() }, 30_000)
        }
    }

    private fun stopScan() {
        if (!scanning) return
        try { adapter?.bluetoothLeScanner?.stopScan(scanCb) } catch (_: Exception) {}
        scanning = false
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, r: ScanResult) {
            val dev = r.device
            val name = r.scanRecord?.deviceName ?: try { dev.name } catch (_: Exception) { null }
            val match = (store.scaleMac.isNotBlank() && dev.address.equals(store.scaleMac, true)) || Huawei.isHuaweiName(name) ||
                (r.scanRecord?.serviceUuids?.any { it.uuid == Huawei.SERVICE } == true)
            if (!match || busy || System.currentTimeMillis() < cooldownUntil) return
            main.post { connect(dev, name ?: "AH100") }
        }
        override fun onScanFailed(errorCode: Int) {
            scanning = false
            Status.log("البحث وقف (رمز $errorCode) — نعيد بعد شوي")
            main.postDelayed({ ensureScan() }, 15_000)
        }
    }

    // ───────────── الاتصال ─────────────

    private fun connect(dev: BluetoothDevice, name: String) {
        if (busy) return
        val mac = Huawei.parseMac(dev.address) ?: return
        stopScan()
        busy = true; gotReading = false; ops.clear(); opRunning = false
        if (store.scaleMac != dev.address) { store.scaleMac = dev.address; Status.log("لقيت الميزان $name (${dev.address}) وحفظته") }
        store.scaleName = name
        discoverUntil = 0
        session = Huawei.Session(mac, store.profile())
        wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mizani:scale").apply { acquire(3 * 60_000L) }
        Status.set("يتصل بالميزان…", name)
        Status.log("اتصال بـ $name")
        main.postDelayed(watchdog, 120_000)
        gatt = if (Build.VERSION.SDK_INT >= 23) dev.connectGatt(this, false, gattCb, BluetoothDevice.TRANSPORT_LE) else dev.connectGatt(this, false, gattCb)
    }

    private val watchdog = Runnable { Status.log("انتهى وقت الاتصال"); finish(false) }

    private fun closeGatt() {
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null; tx = null
    }

    private fun finish(measured: Boolean) {
        main.removeCallbacks(watchdog)
        closeGatt()
        session = null; ops.clear(); opRunning = false
        try { if (wake?.isHeld == true) wake?.release() } catch (_: Exception) {}
        busy = false
        // الميزان يظل صاحي شوي بعد القياس؛ ننتظر عشان ما نعيد الاتصال على نفس الوقفة
        cooldownUntil = System.currentTimeMillis() + if (measured) 45_000 else 8_000
        main.postDelayed({ ensureScan() }, 300)
        Status.lastWeight = store.mine().lastOrNull()
        Status.changed()
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (g != gatt) return@post
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Status.log("متصل، نقرأ الخدمات")
                    main.postDelayed({ if (g == gatt) g.discoverServices() }, 600)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Status.log(if (gotReading) "الميزان قفل الاتصال بعد القياس" else "انقطع الاتصال (رمز $status)")
                    finish(gotReading)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                if (g != gatt) return@post
                val svc = g.getService(Huawei.SERVICE)
                val rx = svc?.getCharacteristic(Huawei.CHAR_RX)
                tx = svc?.getCharacteristic(Huawei.CHAR_TX)
                if (rx == null || tx == null) { Status.log("هذا مو ميزان هواوي AH100 (ما فيه خدمة FAA0)"); finish(false); return@post }
                g.setCharacteristicNotification(rx, true)
                val d = rx.getDescriptor(Huawei.CCCD)
                if (d == null) { kickSoon(); return@post }
                if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                else { @Suppress("DEPRECATION") run { d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(d) } }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            main.post { if (g == gatt) { Status.log("نسمع للميزان"); kickSoon() } }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            val v = value.copyOf(); main.post { if (g == gatt) handle(v) }
        }

        @Deprecated("Android 12 وتحت")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return
            @Suppress("DEPRECATION") val v = c.value?.copyOf() ?: return
            main.post { if (g == gatt) handle(v) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            main.post { if (g == gatt) { opRunning = false; runNext() } }
        }
    }

    private fun kickSoon() = main.postDelayed({ session?.let { apply(it.kick()) } }, 1200)

    private fun handle(d: ByteArray) {
        Status.log("← " + Huawei.toHex(d))
        session?.let { apply(it.onData(d)) }
    }

    private fun apply(out: Huawei.Out) {
        if (out.note.isNotBlank()) Status.log("   " + out.note)
        out.status?.let { Status.set(it, store.scaleName) }
        when (out.error) {
            "auth" -> Status.set("الميزان رفض الاتصال", "جرّب «انسَ الميزان» من إعدادات البلوتوث في البوكس، أو أعد تشغيل الميزان (طلّع البطارية)")
            "decode" -> Status.set("القياس وصل بس ما انفك", "أرسل السجل للمطوّر")
        }
        for (w in out.writes) { ops.addLast(w) }
        runNext()
        for (r in out.readings) onReading(r)
    }

    private fun runNext() {
        if (opRunning) return
        val g = gatt ?: return
        val c = tx ?: return
        val (data, label) = ops.removeFirstOrNull() ?: return
        Status.log("→ ${Huawei.toHex(data)}  ($label)")
        opRunning = true
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION") run { c.value = data; c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; g.writeCharacteristic(c) }
        }
        val my = ++opSeq
        if (!ok) { opRunning = false; main.postDelayed({ runNext() }, 150) }
        else main.postDelayed({ if (opRunning && opSeq == my) { opRunning = false; runNext() } }, 2500)   // احتياط لو ما رجع رد
    }

    private fun onReading(r: Huawei.Reading) {
        gotReading = true
        val mine = store.isMine(r.weightKg)
        val rec = store.add(r, store.scaleName.ifBlank { "AH100" }, other = !mine) ?: return
        val w = String.format(Locale.US, "%.1f", rec.w)
        if (!mine) {
            Status.log("وزن $w كجم بعيد عن وزنك — اعتبرته لشخص ثاني وما رفعته")
            Status.set("وزن $w كجم لشخص ثاني؟", "ما انرفع. تقدر تغيّر النطاق من صفحة الإعدادات")
            return
        }
        Status.lastWeight = rec
        Status.log((if (r.fromHistory) "قياس من ذاكرة الميزان: " else "وزنك: ") + "$w كجم" + (rec.fat?.let { " · دهون $it%" } ?: ""))
        if (!r.fromHistory) Status.set("وزنك $w كجم ✓", "انحفظ · ${time.format(Date(rec.t))}")
        scheduleUpload(3_000)
    }

    // ───────────── الرفع ─────────────

    private val uploadTask = Runnable { io.execute { reportUpload(Uploader.send(store, appTag)) } }

    private fun scheduleUpload(delayMs: Long) {
        main.removeCallbacks(uploadTask)
        main.postDelayed(uploadTask, delayMs)
    }

    private fun reportUpload(res: Uploader.Result, test: Boolean = false) {
        when (res) {
            is Uploader.Result.Nothing -> {}
            is Uploader.Result.Ok -> {
                uploadBackoff = 0
                Status.upload = if (test) "التجربة وصلت GitHub ✓ (شيك Actions في Whoop)" else "انرفع ✓ ${time.format(Date())}"
                Status.log(if (test) "إرسال تجربة: GitHub استلم ✓" else "انرفع ${res.count} قياس للموقع ✓")
            }
            is Uploader.Result.Config -> {
                Status.upload = "ما انرفع: ${res.message}"; store.lastError = res.message
                Status.log("الرفع: ${res.message}")
            }
            is Uploader.Result.Retry -> {
                val waits = longArrayOf(60_000, 5 * 60_000, 15 * 60_000, 30 * 60_000, 60 * 60_000)
                val wait = waits[uploadBackoff.coerceAtMost(waits.size - 1)]; uploadBackoff++
                Status.upload = "ما انرفع (${res.message}) — نعيد بعد ${wait / 60_000} دقيقة"
                Status.log("الرفع: ${res.message}")
                main.post { scheduleUpload(wait) }
            }
        }
        Status.changed()
    }
}
