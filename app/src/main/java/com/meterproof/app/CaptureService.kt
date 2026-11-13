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
import com.meterproof.app.delivery.CalibrationProbe
import com.meterproof.app.delivery.DeliveryDetector
import com.meterproof.app.delivery.DeliveryEventLog
import com.meterproof.app.delivery.DeliveryRevealProbe
import com.meterproof.app.delivery.InstructionStores
import com.meterproof.app.delivery.PlacementPrior
import com.meterproof.app.delivery.DeliveryStore
import com.meterproof.app.delivery.Sample
import com.meterproof.app.privacy.GateEventLog
import com.meterproof.app.privacy.InvoiceDetail
import com.meterproof.app.privacy.PrivacyGate
import com.meterproof.app.privacy.PrivacyTier
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
        const val ACTION_SHIFT_END = "com.meterproof.app.SHIFT_END"
        const val ACTION_NOW = "com.meterproof.app.NOW"
        const val ACTION_METER = "com.meterproof.app.METER"
        const val ACTION_CALIB = "com.meterproof.app.CALIB"

        private const val EXTRA_METER_ID = "meter_id"
        private const val EXTRA_CALIB_POSITIVE = "calib_positive"
        private const val EXTRA_LAT = "lat"
        private const val EXTRA_LON = "lon"
        private const val EXTRA_ACC = "acc"
        private const val EXTRA_ELAPSED = "elapsed"
        private const val EXTRA_MOCK = "mock"

        private const val RETRY_WHILE_WALKING_MS = 15_000L
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

        /**
         * التقاط عينة معايرة لكاشف الفواتير. positive = true: ورقة فاتورة ظاهرة، false: لا فاتورة.
         * لا تُحفظ أي صورة: تمر الصورة على بوابة الخصوصية، وإن سلمت تُحفظ سمات رقمية فقط ثم تُرمى الصورة.
         */
        fun calibrate(ctx: Context, positive: Boolean) {
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, CaptureService::class.java)
                    .setAction(ACTION_CALIB)
                    .putExtra(EXTRA_CALIB_POSITIVE, positive)
            )
        }

        /**
         * تصوير عداد من الجولة: الموقع يأتي من شاشة الجولة (فيه آخر نقطة GPS حية)،
         * فيعمل حتى خارج الجلسة. الصورة تُربط بالعداد داخل السجل المبصوم.
         */
        fun captureForMeter(
            ctx: Context, meterId: Long, lat: Double, lon: Double, acc: Float,
            elapsedRealtimeNanos: Long, mock: Boolean
        ) {
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, CaptureService::class.java)
                    .setAction(ACTION_METER)
                    .putExtra(EXTRA_METER_ID, meterId)
                    .putExtra(EXTRA_LAT, lat)
                    .putExtra(EXTRA_LON, lon)
                    .putExtra(EXTRA_ACC, acc)
                    .putExtra(EXTRA_ELAPSED, elapsedRealtimeNanos)
                    .putExtra(EXTRA_MOCK, mock)
            )
        }
    }

    private lateinit var settings: Settings
    private lateinit var store: EvidenceStore

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var capturing = false

    // ---- تصوير عداد من الجولة (تُضبط عند بدء الالتقاط وتُمسح عند نهايته)
    @Volatile private var captureMeterId = -1L
    @Volatile private var captureLocation: Location? = null
    @Volatile private var captureExtraFlags: Set<String> = emptySet()

    /** سبب إتلاف الصورة الحالية (يُسجَّل حدثًا بلا صورة). null إن لم تُتلف. */
    @Volatile private var discardDetail: String? = null

    /** نتيجة كشف التسليم للصورة الحالية، تُسجَّل حدثًا بعد حفظ السجل (null إن لا شيء). */
    private class PendingDelivery(
        val eventType: String, val score: Float, val modelId: String, val detail: InvoiceDetail,
        val status: DeliveryEventLog.EvidenceStatus
    )
    @Volatile private var pendingDelivery: PendingDelivery? = null
    /** للمحاولة الثانية: هل عمل الكاشف في هذه اللقطة، وهل وجد فاتورة مقبولة؟ */
    @Volatile private var captureHadProbe = false
    @Volatile private var lastCaptureDetected = false
    /** بداية التوقف الذي استُنفدت فيه المحاولة الثانية (محاولة واحدة لكل توقف). */
    private var retryUsedForStopSince = 0L

    /** تصنيف عينة المعايرة الجارية (true = فاتورة ظاهرة). */
    @Volatile private var calibPositive = false
    private val roundDb by lazy { RoundDb(this) }

    private var cameraProvider: ProcessCameraProvider? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var lastLocation: Location? = null
    private var locationListening = false

    /** وقت آخر صورة توقف (على الخيط الرئيسي فقط)، لفرض الحد الأدنى بين الصور. */
    private var lastStopPhotoAt = 0L

    // ---- التتبع الذكي ومؤشرات التلاعب
    private var gpsFast = true
    private var tracking = false
    private lateinit var tracker: MotionTracker
    private var motionSensor: MotionSensor? = null
    /** هل يمشي العامل الآن (من مقياس التسارع)؟ التصوير العشوائي لا يعمل أثناء المشي. */
    @Volatile private var walking = false
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
            if (settings.active && !settings.paused && inWorkHours()) {
                // توقف قرب إحداثية عداد في الجولة: صورة وصول مربوطة بذلك العداد (لا تعني تسليم الفاتورة)
                executor.execute {
                    val arrival = try {
                        findArrival(fix)
                    } catch (e: Exception) {
                        null
                    }
                    mainHandler.post {
                        val now = System.currentTimeMillis()
                        val action = CapturePolicy.decideStop(
                            arrival != null, settings.photoOnStop, now, lastStopPhotoAt
                        )
                        when (action) {
                            CapturePolicy.StopAction.SKIP_DISABLED -> Unit // نقطة الموقع سُجّلت أصلًا (STOP_START)
                            CapturePolicy.StopAction.SKIP_TOO_SOON ->
                                logFailure("stop", CapturePolicy.Reason.TOO_SOON, fix)
                            CapturePolicy.StopAction.ARRIVAL, CapturePolicy.StopAction.STOP -> {
                                if (capturing) {
                                    logFailure("stop", CapturePolicy.Reason.BUSY, fix)
                                } else {
                                    lastStopPhotoAt = now
                                    if (action == CapturePolicy.StopAction.ARRIVAL && arrival != null) {
                                        captureMeterId = arrival.meter.id
                                        captureLocation = lastLocation
                                        captureExtraFlags =
                                            if (arrival.ambiguous) setOf("MULTI_NEAR") else emptySet()
                                        capture("arrival")
                                    } else {
                                        // موقع التوقف المختار (أفضل دقة) يُربط بالصورة حتى بلا قاعدة بيانات
                                        captureLocation = lastLocation
                                        capture("stop")
                                    }
                                }
                            }
                        }
                    }
                }
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

            // انتهى الدوام بعد أن عملت فيه الجلسة: تُغلق (على الخيط الرئيسي، لا داخل استدعاء الموقع)
            if (shiftOver()) {
                mainHandler.post { endShiftIfOver() }
                return
            }

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
            ACTION_SHIFT_END -> {
                if (!settings.active) {
                    stopForegroundAndSelf()
                    return START_NOT_STICKY
                }
                if (!enterForeground()) return START_NOT_STICKY
                // إن لم ينتهِ الدوام فعلًا (تغيّرت الساعات مثلًا) نعيد ضبط المنبّه على النهاية الصحيحة
                if (!endShiftIfOver()) scheduleShiftEnd()
            }
            ACTION_NOW -> {
                if (!enterForeground()) return START_NOT_STICKY
                capture("test")
            }
            ACTION_CALIB -> {
                if (!enterForeground()) return START_NOT_STICKY
                if (!capturing && intent != null) {
                    calibPositive = intent.getBooleanExtra(EXTRA_CALIB_POSITIVE, false)
                    capture("calib")
                }
            }
            ACTION_METER -> {
                if (!enterForeground()) return START_NOT_STICKY
                if (!capturing && intent != null) {
                    captureMeterId = intent.getLongExtra(EXTRA_METER_ID, -1L)
                    captureLocation = locationFromExtras(intent)
                    captureExtraFlags =
                        if (intent.getBooleanExtra(EXTRA_MOCK, false)) setOf("MOCK_LOCATION") else emptySet()
                    capture("meter")
                }
            }
            else -> {
                if (!enterForeground()) return START_NOT_STICKY
                // بداية جلسة جديدة تصفّر «عملت في الدوام»؛ إعادة تشغيل النظام للخدمة (active = true) تُبقيها
                if (!settings.active) settings.workedThisSession = false
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
                // لو أُعيد تشغيل الخدمة بعد نهاية الدوام تُغلق فورًا بدل أن تبقى حتى الغد
                if (!endShiftIfOver()) scheduleShiftEnd()
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

    private fun inWorkHours(): Boolean =
        WorkHours.inHours(
            Calendar.getInstance().get(Calendar.HOUR_OF_DAY), settings.startHour, settings.endHour
        )

    private fun shiftEndIntent(): PendingIntent = PendingIntent.getService(
        this, 2,
        Intent(this, CaptureService::class.java).setAction(ACTION_SHIFT_END),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** منبّه مستقل عند نهاية الدوام، حتى لا يتأخر الإغلاق إن غاب الـ GPS أو طال فاصل التصوير. */
    private fun scheduleShiftEnd() {
        try {
            val at = WorkHours.nextEndMillis(System.currentTimeMillis(), settings.endHour)
            getSystemService(AlarmManager::class.java)
                .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, shiftEndIntent())
        } catch (e: Exception) {
            // الفحص عند كل نقطة موقع وكل منبّه تصوير يبقى احتياطًا
        }
    }

    /**
     * هل انتهى الدوام بعد أن عملت فيه هذه الجلسة؟ ويسجّل أنها عملت متى كنا داخل الدوام.
     * لا يوقف شيئًا بنفسه (آمن للاستدعاء من داخل استدعاء الموقع).
     */
    private fun shiftOver(): Boolean {
        if (!settings.active) return false
        val inNow = inWorkHours()
        if (inNow && !settings.workedThisSession) settings.workedThisSession = true
        return WorkHours.shouldEnd(settings.workedThisSession, inNow)
    }

    /** يغلق الجلسة إن انتهى الدوام. يعيد true إن أُغلقت. */
    private fun endShiftIfOver(): Boolean {
        if (!shiftOver()) return false
        stopSession("AUTO_END_OF_HOURS")
        return true
    }

    private fun onAlarm() {
        if (!settings.active) {
            stopSession()
            return
        }
        if (endShiftIfOver()) return
        scheduleShiftEnd() // يلتقط أي تغيير في ساعة النهاية أثناء الجلسة
        if (settings.paused || !inWorkHours()) {
            scheduleNext()
            return
        }
        if (walking) {
            // أثناء المشي لا نصوّر: نعيد المحاولة بعد 15 ثانية حتى يقف العامل أو يهدأ
            val at = System.currentTimeMillis() + RETRY_WHILE_WALKING_MS
            settings.nextCaptureAt = at
            getSystemService(AlarmManager::class.java)
                .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent())
            return
        }
        if (CapturePolicy.skipRandomDuringStop(
                settings.motionState == "stopped", settings.stopSince, lastStopPhotoAt,
                System.currentTimeMillis()
            )
        ) {
            // وقفة طويلة أو صُوّر وصولها: لا صورة عشوائية، نؤجّل للموعد العادي التالي
            scheduleNext()
            return
        }
        capture("auto")
    }

    // ---------------------------------------------------------------- التصوير

    private fun findArrival(fix: MotionTracker.Fix): Arrival? {
        val dLat = 60.0 / 111_320.0
        val dLon = dLat / maxOf(0.2, Math.cos(Math.toRadians(fix.lat)))
        val items = roundDb.nearby(fix.lat - dLat, fix.lat + dLat, fix.lon - dLon, fix.lon + dLon)
        return RoundLogic.pickArrival(items, fix.lat, fix.lon, fix.acc)
    }

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
            logFailure(mode, CapturePolicy.Reason.NO_CAMERA_PERMISSION)
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
                logFailure(mode, CapturePolicy.Reason.CAMERA_OPEN_FAILED)
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
                    jpeg = processImage(image, mode)
                } catch (e: Throwable) {
                    settings.lastError = "فشل معالجة الصورة: ${e.javaClass.simpleName}"
                    discardDetail = GateEventLog.discardDetail(mode, listOf("PROCESS_FAILED"), 0L)
                } finally {
                    image.close()
                }
                if (jpeg != null) {
                    try {
                        val meterId = captureMeterId
                        val loc = captureLocation ?: lastLocation
                        val meter = if (meterId > 0) roundDb.get(meterId) else null

                        val flagSet = java.util.TreeSet<String>()
                        currentFlags(loc).split(',').filter { it.isNotEmpty() }.forEach { flagSet.add(it) }
                        flagSet.addAll(captureExtraFlags)
                        if (meter != null && loc != null && meter.lat != null && meter.lon != null) {
                            val d = Geo.distanceM(loc.latitude, loc.longitude, meter.lat, meter.lon)
                            if (d > RoundLogic.nearLimitM(loc.accuracy)) flagSet.add("FAR_FROM_METER")
                        }
                        val flags = flagSet.joinToString(",")

                        val recordId = store.addRecord(jpeg, mode, loc, flags, meter?.meterNo ?: "")
                        logDelivery(recordId, mode, loc)
                        if (meter != null) {
                            if (mode == "arrival") {
                                roundDb.markArrival(meter.id, recordId, flags, System.currentTimeMillis())
                            } else {
                                roundDb.markDone(meter.id, recordId, flags, System.currentTimeMillis())
                                // عداد بلا إحداثية: نتعلّمها من أول صورة دقيقة التُقطت عنده
                                if (!meter.hasGps && loc != null && loc.accuracy <= 30f) {
                                    roundDb.learn(meter.id, loc.latitude, loc.longitude)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        settings.lastError = "فشل الحفظ: ${e.javaClass.simpleName}"
                        logFailure(mode, CapturePolicy.Reason.SAVE_FAILED)
                    }
                } else {
                    // الحدث المفصّل للبوابة إن وُجد، وإلا حدث فشل عام: النقطة لا تضيع بصمت
                    if (discardDetail != null) logDiscard()
                    else if (mode != "calib") logFailure(mode, CapturePolicy.Reason.GATE_DISCARDED)
                }
                mainHandler.post {
                    releaseCamera()
                    finishCapture(mode)
                }
            }

            override fun onError(exception: ImageCaptureException) {
                settings.lastError = "فشل الالتقاط (رمز ${exception.imageCaptureError})"
                logFailure(mode, CapturePolicy.Reason.CAPTURE_ERROR)
                mainHandler.post {
                    releaseCamera()
                    finishCapture(mode)
                }
            }
        })
    }

    /**
     * الصورة الأصلية تبقى في الذاكرة فقط ولا تُكتب أبدًا.
     *
     * - كل الصور (مجدولة، توقف، تجريبية) تمر على بوابة الخصوصية: إما صورة مجرّدة،
     *   أو لا صورة (null) ويُسجَّل حدث الإتلاف.
     * - الوضع الافتراضي الآن (privacyMode = FACES): كل الصور على المرشح القديم (طمس الوجوه فقط)
     *   وتُوسم PRIVACY_LEGACY، وكشف الفواتير معطّل لأنه يعتمد على البوابة الكاملة.
     *   وضع FULL يعيد البوابة الكاملة. عينات المعايرة تمر دائمًا على البوابة ولا تُحفظ لها صورة.
     * - استثناء مؤقت: صور وضع الجرد (meter) تبقى على المرشح القديم (طمس الوجوه) حتى المرحلة 5،
     *   وتُوسم بعلامة PRIVACY_LEGACY داخل السجل المبصوم.
     */
    private fun processImage(image: ImageProxy, mode: String): ByteArray? {
        val raw = image.toBitmap()
        val rotation = image.imageInfo.rotationDegrees
        val oriented = if (rotation != 0) {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        } else {
            raw
        }

        if (mode == "calib") return processCalibration(oriented, raw)

        if (mode == "meter" || settings.privacyMode != "FULL") {
            val safe = PrivacyFilter.process(oriented)
            if (safe == null) {
                settings.discardedCount = settings.discardedCount + 1
                settings.lastError = "فشل التشويه: أُتلفت الصورة ولم تُحفظ"
                discardDetail = GateEventLog.discardDetail(mode, listOf("FILTER_FAILED"), 0L)
                return null
            }
            captureExtraFlags = captureExtraFlags + GateEventLog.FLAG_LEGACY
            val out = ByteArrayOutputStream()
            safe.compress(Bitmap.CompressFormat.JPEG, 80, out)
            return out.toByteArray()
        }

        val probe = buildDeliveryProbe()
        val gate = PrivacyGate.process(oriented, probe)
        if (gate.safeToRecycle) {
            if (oriented !== raw) oriented.recycle()
            raw.recycle()
        }
        val jpeg = gate.jpeg
        if (jpeg == null) {
            settings.discardedCount = settings.discardedCount + 1
            val reasons = gate.decision.reasons
            if (GateEventLog.isFailure(reasons)) {
                settings.lastError = "فشلت بوابة الخصوصية: ${reasons.joinToString(",")}"
            }
            discardDetail = GateEventLog.discardDetail(mode, reasons, gate.totalMs)
            return null
        }
        captureExtraFlags = captureExtraFlags + GateEventLog.flag(gate.decision.tier)
        captureHadProbe = probe != null
        if (probe != null) noteDelivery(gate.decision.tier, probe)
        return jpeg
    }

    /** كاشف الفواتير لهذا الالتقاط، أو null إن كان معطّلًا أو لا نموذج معايَر (فلا كشف إطلاقًا). */
    private fun buildDeliveryProbe(): DeliveryRevealProbe? {
        if (!settings.deliveryDetection) return null
        val store = DeliveryStore(this)
        val windowModel = try {
            store.windowModel()
        } catch (e: Exception) {
            null
        }
        val legacy = if (windowModel == null) {
            try {
                store.model()
            } catch (e: Exception) {
                null
            }
        } else null
        if (windowModel == null && legacy == null) return null
        val detail = if (settings.deliveryOutlineOnly) InvoiceDetail.OUTLINE_ONLY else InvoiceDetail.FULL
        val prior = try {
            InstructionStores.forTask(this).prior()
        } catch (e: Exception) {
            PlacementPrior.NONE
        }
        // الكاشف المتعلَّم بالنوافذ هو الأساس؛ القديم احتياط فقط ما دام لا نموذج جديد
        return if (windowModel != null) {
            val verifier = try {
                store.verifier()
            } catch (e: Exception) {
                null
            }
            DeliveryRevealProbe(
                com.meterproof.app.delivery.WindowDetector(
                    windowModel, prior, verifier, if (verifier != null) SemanticLabeler else null
                ),
                detail
            )
        } else {
            DeliveryRevealProbe(DeliveryDetector(legacy!!, prior = prior), detail)
        }
    }

    /** يحدد علامات السجل المبصوم والحدث المؤجَّل بحسب قرار البوابة ونتيجة الكاشف. */
    private fun noteDelivery(tier: PrivacyTier, probe: DeliveryRevealProbe) {
        val r = probe.last ?: return
        val type = DeliveryEventLog.eventType(tier, r.state) ?: return
        lastCaptureDetected = type == DeliveryEventLog.EVENT_DETECTED
        captureExtraFlags = captureExtraFlags + DeliveryEventLog.flags(tier, probe.detail)
        pendingDelivery = PendingDelivery(
            type, r.score, r.modelId, probe.detail, DeliveryEventLog.evidenceStatus(tier, r.state)
        )
    }

    /** يكتب حدث الكشف في سلسلة الأحداث بعد حفظ سجل الصورة، مع معرّف السجل. */
    private fun logDelivery(recordId: String, mode: String, loc: Location?) {
        val pd = pendingDelivery ?: return
        pendingDelivery = null
        val detail = DeliveryEventLog.detail(mode, pd.score, pd.modelId, pd.detail, recordId, pd.status)
        val now = System.currentTimeMillis()
        executor.execute {
            try {
                store.addEvent(pd.eventType, now, loc?.latitude, loc?.longitude, loc?.accuracy, detail)
            } catch (e: Exception) {
                // تجاهل
            }
        }
    }

    /**
     * عينة معايرة: الصورة تمر على البوابة كاملة، فإن ظهر شخص أو وجه أو فشل كاشف تُرفض العينة ولا يُحفظ شيء.
     * وإن سلمت تُحفظ سمات رقمية فقط. لا تُكتب أي صورة، ويعيد null دائمًا.
     */
    private fun processCalibration(oriented: Bitmap, raw: Bitmap): ByteArray? {
        val probe = CalibrationProbe()
        val gate = PrivacyGate.process(oriented, probe)
        if (gate.safeToRecycle) {
            if (oriented !== raw) oriented.recycle()
            raw.recycle()
        }
        val positive = calibPositive
        var result = "FAILED"
        if (gate.decision.tier == PrivacyTier.DISCARD) {
            settings.discardedCount = settings.discardedCount + 1
            val reasons = gate.decision.reasons
            discardDetail = GateEventLog.discardDetail("calib", reasons, gate.totalMs)
            result = if (GateEventLog.isFailure(reasons)) "FAILED" else "HAZARD"
        } else {
            val cand = probe.candidate
            if (cand == null) {
                result = if (positive) "NO_PAPER" else "NEG_NO_CANDIDATE"
            } else {
                try {
                    DeliveryStore(this).addSample(Sample(cand.features, positive, System.currentTimeMillis()))
                    result = if (positive) "SAVED_POS" else "SAVED_NEG"
                } catch (e: Exception) {
                    settings.lastError = "فشل حفظ عينة المعايرة: ${e.javaClass.simpleName}"
                    settings.calibResult = "FAILED;${System.currentTimeMillis()}"
                    return null
                }
            }
        }
        settings.calibResult = "$result;${System.currentTimeMillis()}"
        return null
    }

    /**
     * فشل أو تخطٍّ لصورة: يُكتب حدث CAPTURE_FAILED بالسبب (رمز ثابت) وموقع [at] أو آخر موقع معروف،
     * فتبقى نقطة GPS لكل توقف حتى لو لم تُلتقط الصورة.
     */
    private fun logFailure(mode: String, reason: String, at: MotionTracker.Fix? = null) {
        val loc = lastLocation
        val lat = at?.lat ?: loc?.latitude
        val lon = at?.lon ?: loc?.longitude
        val acc = at?.acc ?: loc?.accuracy
        val now = System.currentTimeMillis()
        val detail = CapturePolicy.failureDetail(mode, reason)
        executor.execute {
            try {
                store.addEvent(CapturePolicy.EVENT_FAILED, now, lat, lon, acc, detail)
            } catch (e: Exception) {
                // تجاهل
            }
        }
    }

    /** صورة أُتلفت: يبقى سجل الحدث فقط (الرموز والزمن، بلا أي بيانات عن المشهد). */
    private fun logDiscard() {
        val detail = discardDetail ?: return
        discardDetail = null
        logEvent(GateEventLog.EVENT_DISCARDED, detail)
    }

    private fun releaseCamera() {
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            // الكاميرا أُغلقت أصلًا
        }
    }

    private fun locationFromExtras(i: Intent): Location? {
        if (!i.hasExtra(EXTRA_LAT) || !i.hasExtra(EXTRA_LON)) return null
        val l = Location("gps")
        l.latitude = i.getDoubleExtra(EXTRA_LAT, 0.0)
        l.longitude = i.getDoubleExtra(EXTRA_LON, 0.0)
        l.accuracy = i.getFloatExtra(EXTRA_ACC, 0f)
        l.time = System.currentTimeMillis()
        l.elapsedRealtimeNanos = i.getLongExtra(EXTRA_ELAPSED, SystemClock.elapsedRealtimeNanos())
        return l
    }

    private fun finishCapture(mode: String) {
        val now = System.currentTimeMillis()
        val since = settings.stopSince
        val retry = settings.active && CapturePolicy.shouldRetryStopPhoto(
            mode, captureHadProbe, lastCaptureDetected, settings.motionState == "stopped",
            retryUsedForStopSince == since, now, since
        )
        val retryMeter = captureMeterId
        captureHadProbe = false
        lastCaptureDetected = false
        if (retry) {
            retryUsedForStopSince = since
            mainHandler.postDelayed({
                // ما زال الوقوف نفسه ولم تبدأ لقطة أخرى، وإلا فلا إعادة
                if (settings.active && !capturing && settings.motionState == "stopped" &&
                    settings.stopSince == since
                ) {
                    captureMeterId = retryMeter
                    captureLocation = lastLocation
                    captureExtraFlags = setOf("RETRY")
                    capture(mode)
                }
            }, CapturePolicy.RETRY_DELAY_MS)
        }
        capturing = false
        captureMeterId = -1L
        captureLocation = null
        captureExtraFlags = emptySet()
        discardDetail = null
        pendingDelivery = null
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
                walking = moving
                if (tracking) tracker.onMotion(t, moving)
            }
        }
        motionSensor?.start()
    }

    private fun stopMotionSensor() {
        motionSensor?.stop()
        walking = false
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

    /** reason فارغ = إيقاف يدوي، وAUTO_END_OF_HOURS = أُغلقت عند نهاية الدوام. */
    private fun stopSession(reason: String = "") {
        val wasActive = settings.active
        settings.active = false
        settings.workedThisSession = false
        settings.nextCaptureAt = 0L
        if (wasActive) {
            settings.lastStopReason = reason
            settings.lastStopAt = System.currentTimeMillis()
        }
        try {
            val am = getSystemService(AlarmManager::class.java)
            am.cancel(alarmIntent())
            am.cancel(shiftEndIntent())
        } catch (e: Exception) {
            // لا شيء
        }
        if (tracking) {
            tracker.finish(System.currentTimeMillis())
            tracking = false
        }
        settings.motionState = "idle"
        stopMotionSensor()
        if (wasActive) logEvent("SESSION_STOP", if (reason.isEmpty()) "" else "reason=$reason")
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
