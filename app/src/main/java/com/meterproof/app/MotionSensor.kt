package com.meterproof.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler

/**
 * غلاف أندرويد لمقياس التسارع. لا يحتاج أي صلاحية.
 * يعمل داخل الخدمة الأمامية فقط (أندرويد 9+ يمنع الحساسات عن التطبيقات الخلفية العادية).
 * الأحداث تصل على الخيط الذي يملكه [handler].
 */
class MotionSensor(
    context: Context,
    private val handler: Handler,
    private val onChange: (Long, Boolean) -> Unit
) : SensorEventListener {

    private val manager: SensorManager? =
        context.applicationContext.getSystemService(SensorManager::class.java)
    private val accel: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var detector = MotionDetector()
    private var running = false

    val available: Boolean get() = accel != null

    fun start() {
        val s = accel ?: return
        if (running) return
        detector = MotionDetector()
        manager?.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL, handler)
        running = true
    }

    fun stop() {
        if (!running) return
        manager?.unregisterListener(this)
        running = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.values.size < 3) return
        val t = System.currentTimeMillis()
        val change = detector.onSample(t, event.values[0], event.values[1], event.values[2]) ?: return
        onChange(t, change)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
