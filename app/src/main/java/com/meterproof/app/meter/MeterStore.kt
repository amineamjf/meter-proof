package com.meterproof.app.meter

import com.meterproof.app.delivery.AtomicText
import java.io.File

/**
 * تخزين الجرد محليًا (java.io فقط): مهام من ملف CSV وسجلات القراءة بتصحيحاتها، في مجلد meter المستقل
 * (لا يلمس مجلد delivery). كتابة ذرّية، ولا شيء يغادر الجهاز.
 */
class MeterStore(private val dir: File) {

    private val tasksFile = File(dir, "tasks.txt").also { dir.mkdirs() }
    private val recordsFile = File(dir, "records.txt")

    class ImportResult(val tasks: List<MeterTask>, val rejected: List<String>)

    @Synchronized
    fun tasks(): List<MeterTask> =
        if (!tasksFile.exists()) emptyList() else tasksFile.readLines().mapNotNull { MeterCodec.decodeTask(it) }

    @Synchronized
    fun records(): List<MeterRecord> =
        if (!recordsFile.exists()) emptyList() else recordsFile.readLines().mapNotNull { MeterCodec.decodeRecord(it) }

    /** يستبدل المهام بمحتوى CSV (taskId,serial[,lat,lon[,label]]). الأسطر الفاسدة أو المكررة تُرفض وتُبلَّغ. */
    @Synchronized
    fun importTasks(csv: String): ImportResult {
        val r = TaskCsv.parse(csv)
        AtomicText.write(tasksFile, r.tasks.joinToString("\n") { MeterCodec.encodeTask(it) })
        return r
    }

    /** يضيف أو يحدّث سجل مهمة بمعرّفها (لا بالترتيب). */
    @Synchronized
    fun upsert(rec: MeterRecord) {
        val all = records().filter { it.taskId != rec.taskId } + rec
        AtomicText.write(recordsFile, all.joinToString("\n") { MeterCodec.encodeRecord(it) })
    }

    @Synchronized
    fun clear() {
        tasksFile.delete()
        recordsFile.delete()
    }
}

object TaskCsv {
    fun parse(csv: String): MeterStore.ImportResult {
        val tasks = ArrayList<MeterTask>()
        val rejected = ArrayList<String>()
        val seen = HashSet<String>()
        for ((i, raw) in csv.lines().withIndex()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val c = line.split(',').map { it.trim().trim('"') }
            if (i == 0 && c[0].lowercase() in setOf("taskid", "task_id", "id")) continue // سطر العناوين
            val id = c.getOrNull(0).orEmpty()
            val serial = c.getOrNull(1).orEmpty()
            val lat = c.getOrNull(2)?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()
            val lon = c.getOrNull(3)?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()
            when {
                id.isEmpty() || MeterMatcher.normalize(serial).isEmpty() -> rejected.add("سطر ${i + 1}: معرّف أو رقم ناقص")
                !seen.add(id) -> rejected.add("سطر ${i + 1}: معرّف مكرر $id")
                (lat == null) != (lon == null) -> rejected.add("سطر ${i + 1}: إحداثية ناقصة")
                lat != null && (lat !in -90.0..90.0 || lon!! !in -180.0..180.0) -> rejected.add("سطر ${i + 1}: إحداثيات خارج المدى")
                else -> tasks.add(MeterTask(id, MeterMatcher.normalize(serial), lat, lon, c.getOrNull(4).orEmpty().take(60)))
            }
        }
        return MeterStore.ImportResult(tasks, rejected)
    }
}

/** ترميز أسطر بفاصل | مع هروب | و\ والسطر الجديد. */
object MeterCodec {
    private fun esc(s: String) = s.replace("\\", "\\\\").replace("|", "\\p").replace("\n", "\\n")
    private fun unesc(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) { 'p' -> sb.append('|'); 'n' -> sb.append('\n'); else -> sb.append(s[i + 1]) }
                i += 2
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }
    private fun split(line: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < line.length) {
            if (line[i] == '\\' && i + 1 < line.length) { cur.append(line[i]).append(line[i + 1]); i += 2 }
            else if (line[i] == '|') { out.add(cur.toString()); cur.setLength(0); i++ }
            else { cur.append(line[i]); i++ }
        }
        out.add(cur.toString())
        return out
    }

    fun encodeTask(t: MeterTask) = listOf("MT1", t.taskId, t.expectedSerial, t.lat?.toString() ?: "", t.lon?.toString() ?: "", t.label)
        .joinToString("|") { esc(it) }

    fun decodeTask(line: String): MeterTask? = try {
        val p = split(line).map { unesc(it) }
        if (p.size != 6 || p[0] != "MT1" || p[1].isEmpty()) null
        else MeterTask(p[1], p[2], p[3].toDoubleOrNull(), p[4].toDoubleOrNull(), p[5])
    } catch (e: Exception) { null }

    fun encodeRecord(r: MeterRecord): String {
        val h = r.history.joinToString("\u0001") { listOf(it.t.toString(), it.field, it.old, it.new, it.reason).joinToString("\u0002") }
        return listOf("MR1", r.taskId, r.originalSerial, r.confidence.toString(), r.status.name, r.capturedAt.toString(),
            r.correctedSerial ?: "", if (r.manuallyReviewed) "1" else "0", h).joinToString("|") { esc(it) }
    }

    fun decodeRecord(line: String): MeterRecord? = try {
        val p = split(line).map { unesc(it) }
        if (p.size != 9 || p[0] != "MR1" || p[1].isEmpty()) null
        else MeterRecord(
            p[1], p[2], p[3].toFloat(), MeterStatus.valueOf(p[4]), p[5].toLong(),
            p[6].ifEmpty { null }, p[7] == "1",
            if (p[8].isEmpty()) emptyList() else p[8].split('\u0001').map {
                val e = it.split('\u0002'); Edit(e[0].toLong(), e[1], e[2], e[3], e[4])
            }
        )
    } catch (e: Exception) { null }
}
