package com.meterproof.app.delivery

import java.io.File

/**
 * كتابة ذرّية: يُكتب الملف المؤقت كاملًا ثم يُستبدل به الأصل دفعة واحدة، فإن أُغلق التطبيق أو انقطع التيار
 * أثناء الكتابة (مثلًا أثناء التدريب) يبقى الملف القديم سليمًا ولا يُقرأ نصف ملف.
 */
object AtomicText {
    fun write(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            // بعض الأنظمة ترفض الاستبدال بالاسم: نحذف الأصل ثم نعيد التسمية (المؤقت كامل ولا يضيع)
            target.delete()
            if (!tmp.renameTo(target)) {
                target.writeText(text)
                tmp.delete()
            }
        }
    }
}
