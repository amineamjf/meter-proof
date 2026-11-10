package com.meterproof.app

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * مخزن خارطة المنطقة المحمّلة: ملف لكل قطعة داخل مساحة التطبيق الخاصة.
 * القطعة مكتملة فقط إن وُجد ملفها (يُكتب مؤقتًا ثم يُعاد تسميته)، فلا تُحسب قطعة مقطوعة منجزة،
 * وهذا ما يجعل الاستئناف بعد انقطاع الشبكة آمنًا.
 */
class AreaStore(context: Context) {

    private val dir = File(context.applicationContext.filesDir, "area").apply { mkdirs() }

    private fun file(c: AreaCell) = File(dir, "${c.id}.bin")

    fun has(c: AreaCell): Boolean = file(c).isFile

    /** يكتب القطعة ذريًا. يرمي IOException إن فشل (مثلًا امتلاء المساحة). */
    fun write(c: AreaCell, ways: List<OsmWay>) {
        val tmp = File(dir, "${c.id}.tmp")
        try {
            FileOutputStream(tmp).use { fos ->
                BufferedOutputStream(fos, 64 * 1024).use { out ->
                    OsmCellCodec.write(out, ways)
                }
                fos.fd.sync()
            }
            val dest = file(c)
            if (!tmp.renameTo(dest)) {
                dest.delete()
                if (!tmp.renameTo(dest)) throw IOException("تعذّر حفظ القطعة")
            }
        } finally {
            tmp.delete()
        }
    }

    /** يقرأ القطعة، أو null إن لم توجد أو كانت تالفة (وتُحذف التالفة لتُعاد). */
    fun read(c: AreaCell): List<OsmWay>? {
        val f = file(c)
        if (!f.isFile) return null
        return try {
            BufferedInputStream(FileInputStream(f), 64 * 1024).use { OsmCellCodec.read(it) }
        } catch (e: Exception) {
            f.delete()
            null
        }
    }

    private fun cells(): List<AreaCell> {
        val out = ArrayList<AreaCell>()
        val files = dir.listFiles() ?: return out
        for (f in files) {
            val n = f.name
            if (!n.endsWith(".bin")) continue
            val p = n.removeSuffix(".bin").split('_')
            if (p.size != 2) continue
            val r = p[0].toIntOrNull() ?: continue
            val c = p[1].toIntOrNull() ?: continue
            out.add(AreaCell(r, c))
        }
        return out
    }

    fun cellCount(): Int = cells().size

    fun allCells(): List<AreaCell> = cells()

    /** معرّفات القطع المحمّلة (r_c). */
    fun cellIds(): List<String> = cells().map { it.id }

    fun hasAny(): Boolean = cellCount() > 0

    fun bytes(): Long = (dir.listFiles() ?: emptyArray()).filter { it.name.endsWith(".bin") }.sumOf { it.length() }

    /** المستطيل الذي تغطيه القطع المحمّلة، أو null إن لم توجد. */
    fun covered(): BBox? {
        val list = cells()
        if (list.isEmpty()) return null
        var minR = list[0].row
        var maxR = list[0].row
        var minC = list[0].col
        var maxC = list[0].col
        for (c in list) {
            minR = minOf(minR, c.row); maxR = maxOf(maxR, c.row)
            minC = minOf(minC, c.col); maxC = maxOf(maxC, c.col)
        }
        return BBox(
            minR * AreaPlan.CELL_DEG, minC * AreaPlan.CELL_DEG,
            (maxR + 1) * AreaPlan.CELL_DEG, (maxC + 1) * AreaPlan.CELL_DEG
        )
    }

    /** بصمة بسيطة تتغير بتغيّر المحتوى، لإعادة بناء رسم الطرق عند الحاجة. */
    fun version(): Long {
        val files = dir.listFiles() ?: return 0L
        var v = 17L
        for (f in files) if (f.name.endsWith(".bin")) v = v * 31 + f.name.hashCode() + f.length() + f.lastModified()
        return v
    }

    fun clear() {
        (dir.listFiles() ?: emptyArray()).forEach { it.delete() }
    }

    fun describe(): String {
        val n = cellCount()
        if (n == 0) return "لا توجد خارطة أزقة محمّلة (تُحمَّل من الإنترنت مرة واحدة)"
        val mb = bytes() / (1024.0 * 1024.0)
        return "خارطة أزقة محمّلة: $n قطعة، ${"%.1f".format(mb)} ميغابايت (تعمل دون إنترنت)"
    }
}
