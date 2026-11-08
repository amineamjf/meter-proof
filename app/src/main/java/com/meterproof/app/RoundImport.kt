package com.meterproof.app

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.text.Normalizer
import java.util.zip.ZipInputStream

/**
 * استيراد جولة العدادات من ملف Excel (xlsx) أو CSV. منطق نقي (بلا اعتماد على أندرويد) لتسهيل الاختبار.
 *
 * نسخة التجربة (redesign): تُقرأ أيضًا بيانات الزبون (الاسم، الفهرس الجديد) لعرضها في البطاقة.
 * مفتاح الإخفاء الواحد هو [CustomerInfo.SHOW] في RoundLogic.kt: عند false لا يُقرأ الاسم من الملف
 * ولا يُعرض، وتبقى بقية الأعمدة كما كانت.
 */

/** صف عداد بعد الاستيراد. */
data class MeterRow(
    val meterNo: String,
    val contract: String,
    val address: String,
    val quartier: String,
    val lat: Double?,
    val lon: Double?,
    val oldIndex: Long?,
    val customerName: String = "",
    val newIndex: Long? = null
)

data class ImportResult(
    val meters: List<MeterRow>,
    val scannedRows: Int,
    val skippedNoMeter: Int,
    val duplicates: Int,
    val withGps: Int,
    val error: String?
)

/** قارئ xlsx بلا مكتبات: ملف xlsx حزمة ZIP من ملفات XML. */
object XlsxReader {

    private val SHEET_NAME = Regex("""xl/worksheets/sheet(\d+)\.xml""")
    private val SI = Regex("""<si\b[^>]*?(?:/>|>(.*?)</si>)""", RegexOption.DOT_MATCHES_ALL)
    private val T = Regex("""<t\b[^>]*?(?:/>|>(.*?)</t>)""", RegexOption.DOT_MATCHES_ALL)
    private val ROW = Regex("""<row\b[^>]*?(?:/>|>(.*?)</row>)""", RegexOption.DOT_MATCHES_ALL)
    private val CELL = Regex("""<c\b([^>]*?)(?:/>|>(.*?)</c>)""", RegexOption.DOT_MATCHES_ALL)
    private val REF = Regex("""\br="([A-Z]+)\d+"""")
    private val TYPE = Regex("""\bt="(\w+)"""")
    private val V = Regex("""<v>(.*?)</v>""", RegexOption.DOT_MATCHES_ALL)

    /** يعيد كل أوراق الملف بالترتيب، وكل ورقة قائمة صفوف من نصوص الخلايا. */
    fun readSheets(input: InputStream): List<List<List<String>>> {
        var shared: ByteArray? = null
        val sheets = java.util.TreeMap<Int, ByteArray>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                val m = SHEET_NAME.matchEntire(name)
                if (name == "xl/sharedStrings.xml" || m != null) {
                    val bytes = readAll(zip)
                    if (name == "xl/sharedStrings.xml") shared = bytes
                    else sheets[m!!.groupValues[1].toInt()] = bytes
                }
            }
        }
        val strings = shared?.let { parseShared(String(it, Charsets.UTF_8)) } ?: emptyList()
        return sheets.values.map { parseSheet(String(it, Charsets.UTF_8), strings) }
    }

    private fun readAll(zip: ZipInputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = zip.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    internal fun parseShared(xml: String): List<String> {
        val out = ArrayList<String>()
        for (si in SI.findAll(xml)) {
            val inner = si.groupValues[1]
            val sb = StringBuilder()
            for (t in T.findAll(inner)) sb.append(unescape(t.groupValues[1]))
            out.add(sb.toString())
        }
        return out
    }

    internal fun parseSheet(xml: String, strings: List<String>): List<List<String>> {
        val rows = ArrayList<List<String>>()
        for (row in ROW.findAll(xml)) {
            val cells = ArrayList<String>()
            for (c in CELL.findAll(row.groupValues[1])) {
                val attrs = c.groupValues[1]
                val inner = c.groupValues[2]
                val col = REF.find(attrs)?.groupValues?.get(1)?.let { columnIndex(it) } ?: cells.size
                val type = TYPE.find(attrs)?.groupValues?.get(1) ?: ""
                val value = cellValue(type, inner, strings)
                while (cells.size <= col) cells.add("")
                cells[col] = value
            }
            rows.add(cells)
        }
        return rows
    }

    private fun cellValue(type: String, inner: String, strings: List<String>): String {
        if (inner.isEmpty()) return ""
        return when (type) {
            "s" -> {
                val idx = V.find(inner)?.groupValues?.get(1)?.trim()?.toIntOrNull()
                if (idx != null && idx in strings.indices) strings[idx] else ""
            }
            "inlineStr" -> {
                val sb = StringBuilder()
                for (t in T.findAll(inner)) sb.append(unescape(t.groupValues[1]))
                sb.toString()
            }
            else -> unescape(V.find(inner)?.groupValues?.get(1) ?: "")
        }
    }

    internal fun columnIndex(letters: String): Int {
        var n = 0
        for (ch in letters) n = n * 26 + (ch - 'A' + 1)
        return n - 1
    }

    internal fun unescape(s: String): String {
        if (!s.contains('&')) return s
        var out = s
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
        out = Regex("""&#x([0-9a-fA-F]+);""").replace(out) {
            it.groupValues[1].toInt(16).toChar().toString()
        }
        out = Regex("""&#(\d+);""").replace(out) {
            it.groupValues[1].toInt().toChar().toString()
        }
        return out.replace("&amp;", "&")
    }
}

/** قارئ CSV بسيط: فواصل ; أو , أو Tab، واقتباس مزدوج. */
object CsvReader {
    fun read(text: String): List<List<String>> {
        val clean = text.removePrefix("﻿")
        val firstLine = clean.lineSequence().firstOrNull() ?: return emptyList()
        val delim = listOf(';', ',', '\t').maxByOrNull { d -> firstLine.count { it == d } } ?: ','

        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < clean.length) {
            val ch = clean[i]
            when {
                inQuotes -> {
                    if (ch == '"') {
                        if (i + 1 < clean.length && clean[i + 1] == '"') {
                            cell.append('"')
                            i++
                        } else {
                            inQuotes = false
                        }
                    } else {
                        cell.append(ch)
                    }
                }
                ch == '"' -> inQuotes = true
                ch == delim -> {
                    row.add(cell.toString())
                    cell.setLength(0)
                }
                ch == '\n' || ch == '\r' -> {
                    if (ch == '\r' && i + 1 < clean.length && clean[i + 1] == '\n') i++
                    row.add(cell.toString())
                    cell.setLength(0)
                    rows.add(row)
                    row = ArrayList()
                }
                else -> cell.append(ch)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row.add(cell.toString())
            rows.add(row)
        }
        return rows
    }
}

/** أعمدة الجولة المكتشفة من صف العناوين (-1 إن غاب العمود). */
data class RoundColumns(
    val meter: Int,
    val address: Int,
    val gps: Int,
    val contract: Int,
    val oldIndex: Int,
    val quartier: Int,
    val customerName: Int = -1,
    val newIndex: Int = -1
) {
    companion object {
        private val METER = setOf(
            "ncompteur", "nocompteur", "numerocompteur", "numcompteur", "compteur",
            "ncpt", "nserie", "meter", "meterno", "meternumber"
        )
        private val ADDRESS = setOf("adresse", "address")
        private val GPS = setOf(
            "gpscarte", "gps", "coordonnees", "coordonneesgps", "position", "latlon", "localisation"
        )
        private val CONTRACT = setOf("ncontrat", "nocontrat", "numerocontrat", "contrat")
        private val OLD_INDEX = setOf(
            "ancienindex", "ancienneindex", "indexancien", "dernierindex", "indexprecedent"
        )
        private val QUARTIER = setOf("quartier", "secteur", "zone")
        private val NAME = setOf(
            "nom", "clientnom", "nomclient", "nomabonne", "client", "abonne", "titulaire", "nomprenom",
            "nometprenom", "customer", "customername", "name", "الاسم", "اسمالزبون", "الزبون"
        )
        private val NEW_INDEX = setOf(
            "nouvelindex", "nouveauindex", "indexnouveau", "indexactuel", "nouvindex",
            "newindex", "indexnouvel", "indexreleve"
        )

        /** يوحّد العنوان: بلا حركات ولا رموز ولا فراغات، بأحرف صغيرة. */
        fun norm(s: String): String {
            val d = Normalizer.normalize(s, Normalizer.Form.NFKD)
            val sb = StringBuilder()
            for (ch in d) {
                if (Character.getType(ch) == Character.NON_SPACING_MARK.toInt()) continue
                if (ch.isLetterOrDigit()) sb.append(ch.lowercaseChar())
            }
            return sb.toString()
        }

        fun detect(headerRow: List<String>): RoundColumns {
            fun find(names: Set<String>): Int = headerRow.indexOfFirst { norm(it) in names }
            return RoundColumns(
                meter = find(METER),
                address = find(ADDRESS),
                gps = find(GPS),
                contract = find(CONTRACT),
                oldIndex = find(OLD_INDEX),
                quartier = find(QUARTIER),
                customerName = if (CustomerInfo.SHOW) find(NAME) else -1,
                newIndex = find(NEW_INDEX)
            )
        }
    }
}

object GpsParse {
    private val PAIR = Regex("""^\s*(-?\d{1,3}\.\d+)\s*[,;\s]\s*(-?\d{1,3}\.\d+)\s*$""")

    fun parse(text: String): Pair<Double, Double>? {
        val m = PAIR.find(text) ?: return null
        val lat = m.groupValues[1].toDoubleOrNull() ?: return null
        val lon = m.groupValues[2].toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        if (lat == 0.0 && lon == 0.0) return null
        return lat to lon
    }
}

object Quartier {
    const val UNKNOWN = "غير محدد"

    private val CUT = Regex("""[,(]| - | EAU\b| \d{4,}""")

    /** يستخرج اسم الحي من العنوان: النص بعد كلمة QUARTIER حتى أول فاصلة أو ملحق. */
    fun of(address: String): String {
        val up = address.uppercase().replace(Regex("\\s+"), " ").trim()
        val i = up.lastIndexOf("QUARTIER")
        if (i < 0) return UNKNOWN
        var rest = up.substring(i + "QUARTIER".length).trim()
        val cut = CUT.find(rest)
        if (cut != null) rest = rest.substring(0, cut.range.first)
        rest = rest.trim().trim('-', ':', '.').trim()
        return if (rest.isEmpty()) UNKNOWN else rest
    }
}

object RoundImport {

    /** صف العناوين أول صف (ضمن أول 40) يحوي عمود العداد وعمود العنوان أو الإحداثية. */
    private fun findHeader(rows: List<List<String>>): Pair<Int, RoundColumns>? {
        for (i in 0 until minOf(rows.size, 40)) {
            val cols = RoundColumns.detect(rows[i])
            if (cols.meter >= 0 && (cols.address >= 0 || cols.gps >= 0)) return i to cols
        }
        return null
    }

    /** يحلل جدولًا واحدًا (صفوف نصوص). */
    fun parse(rows: List<List<String>>): ImportResult {
        val header = findHeader(rows)
            ?: return ImportResult(
                emptyList(), 0, 0, 0, 0,
                "لم أجد صف العناوين: يلزم عمود «N° Compteur» مع «Adresse» أو «GPS»"
            )
        val (headerIdx, cols) = header

        fun cell(row: List<String>, idx: Int): String =
            if (idx >= 0 && idx < row.size) row[idx].trim() else ""

        val seen = HashSet<String>()
        val out = ArrayList<MeterRow>()
        var scanned = 0
        var noMeter = 0
        var dups = 0
        var withGps = 0

        for (i in headerIdx + 1 until rows.size) {
            val row = rows[i]
            if (row.all { it.isBlank() }) continue
            scanned++
            val meterNo = cell(row, cols.meter)
            if (meterNo.isEmpty()) {
                noMeter++
                continue
            }
            if (!seen.add(meterNo)) {
                dups++
                continue
            }
            val address = cell(row, cols.address)
            val gps = if (cols.gps >= 0) GpsParse.parse(cell(row, cols.gps)) else null
            if (gps != null) withGps++
            val explicitQuartier = cell(row, cols.quartier)
            val quartier = if (explicitQuartier.isNotEmpty()) {
                explicitQuartier.uppercase()
            } else {
                Quartier.of(address)
            }
            out.add(
                MeterRow(
                    meterNo = meterNo,
                    contract = cell(row, cols.contract),
                    address = address,
                    quartier = quartier,
                    lat = gps?.first,
                    lon = gps?.second,
                    oldIndex = cell(row, cols.oldIndex).toDoubleOrNull()?.toLong(),
                    customerName = cell(row, cols.customerName),
                    newIndex = cell(row, cols.newIndex).toDoubleOrNull()?.toLong()
                )
            )
        }
        return ImportResult(out, scanned, noMeter, dups, withGps, null)
    }

    /** من بايتات ملف: xlsx إن بدأ بتوقيع ZIP (PK) وإلا CSV نصي. */
    fun parseFile(bytes: ByteArray): ImportResult {
        val isZip = bytes.size > 3 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()
        return try {
            if (isZip) {
                val sheets = XlsxReader.readSheets(bytes.inputStream())
                var best: ImportResult? = null
                for (sheet in sheets) {
                    val r = parse(sheet)
                    if (r.error == null) {
                        best = r
                        break
                    }
                }
                best ?: ImportResult(
                    emptyList(), 0, 0, 0, 0,
                    "لم أجد ورقة فيها جدول جولة (عمود «N° Compteur» مع «Adresse» أو «GPS»)"
                )
            } else {
                parse(CsvReader.read(String(bytes, Charsets.UTF_8)))
            }
        } catch (e: Exception) {
            ImportResult(emptyList(), 0, 0, 0, 0, "تعذرت قراءة الملف: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}
