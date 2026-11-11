package com.meterproof.app.delivery

import java.io.File
import java.util.UUID

/**
 * مخزن تعليمات التدريب لمهمة واحدة ([TrainingTask]). ملف نصي واحد داخل مجلد المهمة، يحوي أرقامًا
 * وإحداثيات نسبية وملاحظات المالك فقط (لا صور ولا بكسلات). لا يغادر الجهاز، ولا يدخل التصدير ولا
 * سلسلة البصمات. مستقل تمامًا عن مخزن أي مهمة أخرى لأن المجلد يُشتق من [TrainingTask.dirName].
 *
 * يأخذ المجلد مباشرة كي يُختبر على JVM بمجلد مؤقت؛ استعمل [InstructionStores.forTask] في التطبيق.
 */
class InstructionStore(dir: File) {

    private val file = File(dir.apply { mkdirs() }, "instructions.txt")

    companion object {
        const val MAX_INSTRUCTIONS = 300
        private val LOCK = Any()

        fun newId(): String = UUID.randomUUID().toString()
    }

    fun list(): List<TrainingInstruction> = synchronized(LOCK) {
        if (!file.exists()) emptyList() else InstructionCodec.decode(file.readText())
    }

    fun get(id: String): TrainingInstruction? = list().firstOrNull { it.id == id }

    /** يضيف تعليمة جديدة. الحد الأقصى [MAX_INSTRUCTIONS] والأقدم يُحذف أولًا. */
    fun add(i: TrainingInstruction) {
        synchronized(LOCK) {
            val all = (list() + i).takeLast(MAX_INSTRUCTIONS)
            file.writeText(InstructionCodec.encode(all))
        }
    }

    /** يستبدل تعليمة بمعرّفها (التعديل). يعيد false إن لم توجد. */
    fun update(i: TrainingInstruction): Boolean = synchronized(LOCK) {
        val all = list()
        if (all.none { it.id == i.id }) return false
        file.writeText(InstructionCodec.encode(all.map { if (it.id == i.id) i else it }))
        true
    }

    fun delete(id: String): Boolean = synchronized(LOCK) {
        val all = list()
        val rest = all.filter { it.id != id }
        if (rest.size == all.size) return false
        file.writeText(InstructionCodec.encode(rest))
        true
    }

    /** عينات كل التعليمات، لتُضاف إلى عينات المعايرة عند التدريب. */
    fun samples(): List<Sample> = list().flatMap { i ->
        i.samples.map { Sample(it.features, it.positive, i.createdAt) }
    }

    fun prior(): PlacementPrior = PlacementPrior.from(list())

    fun clear() {
        synchronized(LOCK) { file.delete() }
    }
}
