package com.meterproof.app.delivery

import android.content.Context
import java.io.File

/** مدخل أندرويد لمخازن التعليمات: مجلد المهمة داخل مساحة التطبيق الخاصة (لا يدخل التصدير ولا النسخ الاحتياطي). */
object InstructionStores {
    fun forTask(context: Context, task: TrainingTask = TrainingTask.INVOICE): InstructionStore =
        InstructionStore(File(context.applicationContext.filesDir, task.dirName))
}
