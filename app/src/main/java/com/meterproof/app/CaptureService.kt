package com.meterproof.app

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.io.ByteArrayOutputStream
import java.util.Calendar
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * خدمة الجلسة: تبقى حية طوال الدوام، والكاميرا مغلقة.
 * عند موعد الصورة (عشوائي ضمن مجال يحدده المدير) تفتح الكاميرا بلا معاينة،
 * تلتقط إطارًا واحدًا، تشوّه الوجوه في الذاكرة، تحفظ النسخة المشوّهة فقط، ثم تغلق الكاميرا.
 */
class CaptureService : LifecycleService() {

    companion object {
        const val ACTION_START = "com.meterproof.app.START"
        const val ACTION_STOP = "com.meterproof.app.STOP"
        const val ACTION_ALARM = "com.meterproof.app.ALARM"
        const val ACTION_NOW = "com.meterproof.app.NOW"

        private const val CHANNEL_ID = "meterproof_session"
        private const val NOTIF_ID = 1001

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, CaptureService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, CaptureService::class.java).setAction(ACTION_STOP))
        }

        fun captureNow(ctx: Context) {
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, CaptureService::class.java).setAction(ACTION_NOW)
            )
        }
    }

    private lateinit var settings: Settings
    private lateinit var store: EvidenceStore

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var capturing = false
    private var cameraProvider: ProcessCameraProvider? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var lastLocation: Location? = null
    private var locationListening = false

    // ---- التتبع الذكي ومؤشرات التلاعب
    private var gpsFast = true
    private var tracking = false
    private lateinit var tracker: MotionTracker
    private var motionSensor: MotionSensor? = null
    private val clockWatch = ClockWatch()
    private var clockJumped = false
    private var lastFixMock = false
    private val tamperLoggedAt = HashMap<String, Long>()

    /** يستقبل قرارات آلة الحالات ويكتبها في المخزن (الكتابة على خيط منفصل). */
    private val trackerSink = object : MotionTracker.Sink {
        override fun onTrackPoint(fix: MotionTracker.Fix, reason: String) {
            val flags = if (lastFixMock) "MOCK_LOCATION" else ""
            executor.execute {
                try {
                    store.addTrackFix(fix.t, fix.lat, fix.lon, fix.acc, reason, flags)
                } catch (e: Exception) {
                    // تجاهل: نقطة مسار واحدة لا تستحق إيقاف الخدمة
                }
            }
        }

        override fun onStopStart(fix: MotionTracker.Fix, sinceMs: Long) {
            settings.motionState = "stopped"
            settings.stopSince = sinceMs
            executor.execute {
                try {
                    store.addEvent(
                        "STOP_START", System.currentTimeMillis(), fix.lat, fix.lon, fix.acc,
                        "since=$sinceMs"
                    )
                } catch (e: Exception) {
                    // تجاهل
                }
            }
            if (settings.photoOnStop && settings.active && !settings.paused && inWorkHours()) {
                mainHandler.post { capture("stop") }
            }
        }

        override fun onStopEnd(startMs: Long, endMs: Long, fix: MotionTracker.Fix) {
            settings.motionState = "moving"
            executor.execute {
                try {
                    store.addEvent(
                        "STOP_END", endMs, fix.lat, fix.lon, fix.acc,
                        "start=$startMs;dur=${(endMs - startMs) / 1000}"
                    )
                } catch (e: Exception) {
                    // تجاهل
                }
            }
        }

        override fun onGpsFast(fast: Boolean) {
            setGpsFast(fast)
        }
    }

    private fun newTracker(): MotionTracker = MotionTracker(
        MotionTracker.Config(stopAfterMs = settings.stopAfterSeconds.coerceIn(10, 600) * 1000L),
        trackerSink
    )

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastLocation = location
            lastFixMock = isMock(location)
            watchTamper(location)

            // التسجيل محصور في ساعات الدوام وخارج الإيقاف المؤقت
            if (settings.paused || !inWorkHours()) {
                if (tracking) {
                    tracking = false
                    tracker.finish(System.currentTimeMillis())
                    settings.motionState = "idle"
                    stopMotionSensor()
                    setGpsFast(false)
                }
                return
            }
            if (!tracking) {
                tracking = true
                settings.motionState = "moving"
                setGpsFast(true)
                startMotionSensor()
            }
            tracker.onFix(
                MotionTracker.Fix(
                    System.currentTimeMillis(), location.latitude, location.longitude,
                    location.accuracy, if (location.hasSpeed()) location.speed else null
                )
            )
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

        override fun onProviderEnabled(provider: String) {}

        override fun onProviderDisabled(provider: String) {}
    }

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        store = EvidenceStore(this)
        tracker = newTracker()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        // إعادة تشغيل النظام للخدمة تصل بلا Intent
        val action = intent?.action ?: if (settings.active) ACTION_START else ACTION_STOP

        when (action) {
            ACTION_STOP -> {
                stopSession()
                return START_NOT_STICKY
            }
            ACTION_ALARM -> {
                if (!enterForeground()) return START_NOT_STICKY
                onAlarm()
            }
            ACTION_NOW -> {
                if (!enterForeground()) return START_NOT_STICKY
                capture("test")
            }
            else -> {
                if (!enterForeground()) return START_NOT_STICKY
                settings.active = true
                settings.lastError = ""
                tracker = newTracker()
                tracking = false
                clockJumped = false
                tamperLoggedAt.clear()
                settings.motionState = "idle"
                gpsFast = !settings.paused && inWorkHours()
                logEvent("SESSION_START", "")
                if (autoTimeOff()) logTamper("AUTO_TIME_OFF")
                startLocationUpdates()
                scheduleNext()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopLocationUpdates()
        stopMotionSensor()
        releaseWakeLock()
        executor.shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- الخدمة الأمامية

    private fun enterForeground(): Boolean {
        return try {
            val type = when {
                Build.VERSION.SDK_INT >= 30 ->
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                else -> 0
            }
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
            true
        } catch (e: Exception) {
            settings.lastError = "تعذّر بدء الخدمة الأمامية: ${e.javaClass.simpleName}"
            stopSelf()
            false
        }
    }

    private fun buildNotification(): android.app.Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("MeterProof: جلسة العمل نشطة")
            .setContentText("توثيق الميدان قيد التشغيل (الكاميرا مغلقة بين اللقطات)")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, "جلسة العمل", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    // ---------------------------------------------------------------- الجدولة

    private fun alarmIntent(): PendingIntent = PendingIntent.getService(
        this, 1,
        Intent(this, CaptureService::class.java).setAction(ACTION_ALARM),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** يسحب الفاصل التالي عشوائيًا ضمن المجال ويضبط المنبّه (لا يتطلب إذنًا خاصًا) */
    private fun scheduleNext() {
        val minM = settings.minMinutes.coerceAtLeast(1)
        val maxM = settings.maxMinutes.coerceAtLeast(minM)
        val delayMs = Random.nextLong(minM * 60_000L, maxM * 60_000L + 1)
        val at = System.currentTimeMillis() + delayMs
        settings.nextCaptureAt = at
        val am = getSystemService(AlarmManager::class.java)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent())
    }

    private fun inWorkHours(): Boolean {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val s = settings.startHour
        val e = settings.endHour
        return if (s <= e) h in s until e else (h >= s || h < e)
    }

    private fun onAlarm() {
        if (!settings.active) {
            stopSession()
            return
        }
        if (settings.paused || !inWorkHours()) {
            scheduleNext()
            return
        }
        capture("auto")
    }

    // ---------------------------------------------------------------- التصوير

    private fun capture(mode: String) {
        if (capturing) return
        if (clockWatch.check(System.currentTimeMillis(), SystemClock.elapsedRealtime())) {
            clockJumped = true
            logTamper("CLOCK_JUMP")
        }
        if (autoTimeOff()) logTamper("AUTO_TIME_OFF")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            settings.lastError = "صلاحية الكاميرا غير ممنوحة"
            finishCapture(mode)
            return
        }

        capturing = true
        acquireWakeLock()

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val cp = future.get()
                cameraProvider = cp
                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                cp.unbindAll()
                cp.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, imageCapture)
                // انتظار قصير حتى يثبت التعريض والتركيز بعد فتح الكاميرا الباردة
                mainHandler.postDelayed({ takePicture(imageCapture, mode) }, 1200L)
            } catch (e: Exception) {
                settings.lastError = "فشل فتح الكاميرا: ${e.javaClass.simpleName}"
                releaseCamera()
                finishCapture(mode)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePicture(imageCapture: ImageCapture, mode: String) {
        imageCapture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                var jpeg: ByteArray? = null
                try {
                    jpeg = processImage(image)
                } catch (e: Throwable) {
                    settings.lastError = "فشل معالجة الصورة: ${e.javaClass.simpleName}"
                } finally {
                    image.close()
                }
                if (jpeg != null) {
                    try {
                        val loc = lastLocation
                        store.addRecord(jpeg, mode, loc, currentFlags(loc))
                    } catch (e: Exception) {
                        settings.lastError = "فشل الحفظ: ${e.javaClass.simpleName}"
                    }
                }
                mainHandler.post {
                    releaseCamera()
                    finishCapture(mode)
                }
            }

            override fun onError(exception: ImageCaptureException) {
                settings.lastError = "فشل الالتقاط (رمز ${exception.imageCaptureError})"
                mainHandler.post {
                    releaseCamera()
                    finishCapture(mode)
                }
            }
        })
    }

    /**
     * الصورة الأصلية تبقى في الذاكرة فقط. إن فشل التشويه تُتلف ولا يُكتب شيء.
     */
    private fun processImage(image: ImageProxy): ByteArray? {
        val raw = image.toBitmap()
        val rotation = image.imageInfo.rotationDegrees
        val oriented = if (rotation != 0) {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        } else {
            raw
        }

        val safe = PrivacyFilter.process(oriented)
        if (safe == null) {
            settings.discardedCount = settings.discardedCount + 1
            settings.lastError = "فشل التشويه: أُتلفت الصورة ولم تُحفظ"
            return null
        }

        val out = ByteArrayOutputStream()
        safe.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return out.toByteArray()
    }

    private fun releaseCamera() {
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            // الكاميرا أُغلقت أصلًا
        }
    }

    private fun finishCapture(mode: String) {
        capturing = false
        releaseWakeLock()
        if (mode == "auto") {
            if (settings.active) scheduleNext()
        } else if (!settings.active) {
            // صورة تجريبية خارج الجلسة: نغلق الخدمة بعدها
            stopForegroundAndSelf()
        }
    }

    // ---------------------------------------------------------------- الموقع

    private fun startLocationUpdates() {
        if (locationListening) return
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) {
            settings.lastError = "صلاحية الموقع غير ممنوحة"
            return
        }
        val lm = getSystemService(LocationManager::class.java)
        val interval = if (gpsFast) 5_000L else 30_000L
        try {
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (!lm.isProviderEnabled(p)) continue
                lm.requestLocationUpdates(p, interval, 0f, locationListener, Looper.getMainLooper())
                val known = lm.getLastKnownLocation(p)
                val current = lastLocation
                if (known != null && (current == null || known.time > current.time)) {
                    lastLocation = known
                }
            }
            locationListening = true
        } catch (e: SecurityException) {
            settings.lastError = "الموقع مرفوض من النظام"
        }
    }

    private fun stopLocationUpdates() {
        if (!locationListening) return
        try {
            getSystemService(LocationManager::class.java).removeUpdates(locationListener)
        } catch (e: Exception) {
            // لا شيء
        }
        locationListening = false
    }

    /**
     * وتيرة GPS: سريعة (كل 5 ثوانٍ) أثناء الحركة، وبطيئة (كل 30 ثانية) أثناء التوقف الطويل
     * أو خارج الدوام. التبديل يُؤجَّل إلى الخيط الرئيسي كي لا يحدث داخل استدعاء الموقع نفسه.
     */
    private fun setGpsFast(fast: Boolean) {
        mainHandler.post {
            if (gpsFast == fast) return@post
            gpsFast = fast
            if (locationListening) {
                stopLocationUpdates()
                startLocationUpdates()
            }
        }
    }

    // ---------------------------------------------------------------- حساس الحركة

    private fun startMotionSensor() {
        if (motionSensor == null) {
            motionSensor = MotionSensor(this, mainHandler) { t, moving ->
                if (tracking) tracker.onMotion(t, moving)
            }
        }
        motionSensor?.start()
    }

    private fun stopMotionSensor() {
        motionSensor?.stop()
    }

    // ---------------------------------------------------------------- مؤشرات التلاعب والأحداث

    private fun logEvent(type: String, detail: String) {
        val loc = lastLocation
        val now = System.currentTimeMillis()
        executor.execute {
            try {
                store.addEvent(type, now, loc?.latitude, loc?.longitude, loc?.accuracy, detail)
            } catch (e: Exception) {
                // تجاهل
            }
        }
    }

    /** يسجل حدث تلاعب مرة كل نصف ساعة لكل نوع كي لا يمتلئ السجل. */
    private fun logTamper(kind: String) {
        val now = SystemClock.elapsedRealtime()
        val last = tamperLoggedAt[kind]
        if (last != null && now - last < 30 * 60_000L) return
        tamperLoggedAt[kind] = now
        logEvent("TAMPER", kind)
    }

    @Suppress("DEPRECATION")
    private fun isMock(loc: Location): Boolean =
        if (Build.VERSION.SDK_INT >= 31) loc.isMock else loc.isFromMockProvider

    private fun autoTimeOff(): Boolean = try {
        android.provider.Settings.Global.getInt(
            contentResolver, android.provider.Settings.Global.AUTO_TIME, 1
        ) == 0
    } catch (e: Exception) {
        false
    }

    private fun watchTamper(loc: Location) {
        if (clockWatch.check(System.currentTimeMillis(), SystemClock.elapsedRealtime())) {
            clockJumped = true
            logTamper("CLOCK_JUMP")
        }
        if (isMock(loc)) logTamper("MOCK_LOCATION")
    }

    /** علامات تُحفظ مع الصورة داخل السجل المبصوم. فارغة إن لم يُرصد شيء. */
    private fun currentFlags(loc: Location?): String {
        val f = java.util.TreeSet<String>()
        if (clockJumped) f.add("CLOCK_JUMP")
        if (autoTimeOff()) f.add("AUTO_TIME_OFF")
        if (loc != null) {
            if (isMock(loc)) f.add("MOCK_LOCATION")
            val ageMs = (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000L
            if (ageMs > 5 * 60_000L) f.add("STALE_LOCATION")
        }
        return f.joinToString(",")
    }

    // ---------------------------------------------------------------- الإيقاف

    private fun stopSession() {
        val wasActive = settings.active
        settings.active = false
        settings.nextCaptureAt = 0L
        try {
            getSystemService(AlarmManager::class.java).cancel(alarmIntent())
        } catch (e: Exception) {
            // لا شيء
        }
        if (tracking) {
            tracker.finish(System.currentTimeMillis())
            tracking = false
        }
        settings.motionState = "idle"
        stopMotionSensor()
        if (wasActive) logEvent("SESSION_STOP", "")
        stopLocationUpdates()
        releaseCamera()
        stopForegroundAndSelf()
    }

    private fun stopForegroundAndSelf() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------------------------------------------------------------- القفل

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "meterproof:capture").apply {
            acquire(60_000L)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            // لا شيء
        }
        wakeLock = null
    }
}
