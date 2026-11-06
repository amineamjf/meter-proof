package com.meterproof.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * يحاول إعادة الجلسة بعد إقلاع الهاتف إن كانت مفعّلة.
 * أندرويد الحديث قد يمنع بدء خدمة الكاميرا/الموقع من الخلفية، وعندها
 * يُسجَّل الخطأ ويحتاج الأمر إلى فتح التطبيق مرة واحدة.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val settings = Settings(context)
        if (!settings.active) return
        try {
            CaptureService.start(context)
        } catch (e: Exception) {
            settings.lastError = "تعذّر التشغيل بعد الإقلاع (قيد من النظام): افتح التطبيق مرة واحدة"
        }
    }
}
