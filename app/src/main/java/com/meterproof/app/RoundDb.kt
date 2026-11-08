package com.meterproof.app

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** إحصاءات الجولة. */
data class RoundTotals(val total: Int, val done: Int, val withGps: Int, val flagged: Int)

/** إحصاءات حي واحد. */
data class QuartierStat(val name: String, val total: Int, val done: Int)

/**
 * قاعدة الجولة على الهاتف (SQLite). تحفظ الحقول اللازمة فقط: لا أسماء زبائن.
 * حالة الإنجاز مستقلة عن ملف الاستيراد: إعادة استيراد الملف تحفظ ما أُنجز وما تعلّمه التطبيق من إحداثيات.
 */
class RoundDb(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "round.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE meters (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                meter_no TEXT NOT NULL UNIQUE,
                contract TEXT,
                address TEXT,
                quartier TEXT,
                lat REAL,
                lon REAL,
                old_index INTEGER,
                customer_name TEXT,
                new_index INTEGER,
                learned INTEGER NOT NULL DEFAULT 0,
                done_at INTEGER NOT NULL DEFAULT 0,
                record_id TEXT,
                flags TEXT
            )"""
        )
        db.execSQL("CREATE INDEX idx_meters_quartier ON meters(quartier)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE meters ADD COLUMN customer_name TEXT")
            db.execSQL("ALTER TABLE meters ADD COLUMN new_index INTEGER")
        }
    }

    /** يستبدل قائمة العدادات بالجديدة مع حفظ حالة الإنجاز والإحداثيات المتعلَّمة. يعيد عدد العدادات. */
    @Synchronized
    fun importAll(rows: List<MeterRow>): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            class Kept(
                val doneAt: Long, val recordId: String?, val flags: String?,
                val learned: Boolean, val lat: Double?, val lon: Double?
            )

            val kept = HashMap<String, Kept>()
            db.rawQuery(
                "SELECT meter_no, done_at, record_id, flags, learned, lat, lon FROM meters " +
                    "WHERE done_at > 0 OR learned = 1",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    kept[c.getString(0)] = Kept(
                        c.getLong(1), c.getString(2), c.getString(3), c.getInt(4) == 1,
                        if (c.isNull(5)) null else c.getDouble(5),
                        if (c.isNull(6)) null else c.getDouble(6)
                    )
                }
            }

            db.delete("meters", null, null)
            val st = db.compileStatement(
                "INSERT OR IGNORE INTO meters " +
                    "(meter_no, contract, address, quartier, lat, lon, old_index, customer_name, new_index) " +
                    "VALUES (?,?,?,?,?,?,?,?,?)"
            )
            var n = 0
            for (r in rows) {
                st.clearBindings()
                st.bindString(1, r.meterNo)
                st.bindString(2, r.contract)
                st.bindString(3, r.address)
                st.bindString(4, r.quartier)
                if (r.lat != null && r.lon != null) {
                    st.bindDouble(5, r.lat)
                    st.bindDouble(6, r.lon)
                } else {
                    st.bindNull(5)
                    st.bindNull(6)
                }
                if (r.oldIndex != null) st.bindLong(7, r.oldIndex) else st.bindNull(7)
                st.bindString(8, r.customerName)
                if (r.newIndex != null) st.bindLong(9, r.newIndex) else st.bindNull(9)
                if (st.executeInsert() != -1L) n++
            }

            for ((no, k) in kept) {
                if (k.doneAt > 0) {
                    val cv = ContentValues()
                    cv.put("done_at", k.doneAt)
                    cv.put("record_id", k.recordId)
                    cv.put("flags", k.flags)
                    db.update("meters", cv, "meter_no = ?", arrayOf(no))
                }
                if (k.learned && k.lat != null && k.lon != null) {
                    val cv = ContentValues()
                    cv.put("lat", k.lat)
                    cv.put("lon", k.lon)
                    cv.put("learned", 1)
                    // نطبّقها فقط إن لم يأتِ الملف الجديد بإحداثية لهذا العداد
                    db.update("meters", cv, "meter_no = ? AND lat IS NULL", arrayOf(no))
                }
            }
            db.setTransactionSuccessful()
            return n
        } finally {
            db.endTransaction()
        }
    }

    private fun item(c: Cursor): MeterItem = MeterItem(
        id = c.getLong(0),
        meterNo = c.getString(1) ?: "",
        contract = c.getString(2) ?: "",
        address = c.getString(3) ?: "",
        quartier = c.getString(4) ?: "",
        lat = if (c.isNull(5)) null else c.getDouble(5),
        lon = if (c.isNull(6)) null else c.getDouble(6),
        oldIndex = if (c.isNull(7)) null else c.getLong(7),
        customerName = c.getString(8) ?: "",
        newIndex = if (c.isNull(9)) null else c.getLong(9),
        learned = c.getInt(10) == 1,
        doneAt = c.getLong(11),
        recordId = c.getString(12),
        flags = c.getString(13) ?: ""
    )

    private val columns =
        "id, meter_no, contract, address, quartier, lat, lon, old_index, customer_name, new_index, learned, done_at, record_id, flags"

    @Synchronized
    fun get(id: Long): MeterItem? =
        readableDatabase.rawQuery("SELECT $columns FROM meters WHERE id = ?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) item(c) else null
        }

    /** عدادات حي (أو كل الأحياء إن كان null)، مع أو بدون المنجزة. */
    @Synchronized
    fun candidates(quartier: String?, includeDone: Boolean): List<MeterItem> {
        val where = ArrayList<String>()
        val args = ArrayList<String>()
        if (!quartier.isNullOrEmpty()) {
            where.add("quartier = ?")
            args.add(quartier)
        }
        if (!includeDone) where.add("done_at = 0")
        val sql = "SELECT $columns FROM meters" +
            (if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND "))
        val out = ArrayList<MeterItem>()
        readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) out.add(item(c))
        }
        return out
    }

    @Synchronized
    fun totals(): RoundTotals =
        readableDatabase.rawQuery(
            "SELECT COUNT(*), " +
                "COALESCE(SUM(CASE WHEN done_at > 0 THEN 1 ELSE 0 END), 0), " +
                "COALESCE(SUM(CASE WHEN lat IS NOT NULL THEN 1 ELSE 0 END), 0), " +
                "COALESCE(SUM(CASE WHEN flags LIKE '%FAR_FROM_METER%' THEN 1 ELSE 0 END), 0) " +
                "FROM meters",
            null
        ).use { c ->
            if (c.moveToFirst()) RoundTotals(c.getInt(0), c.getInt(1), c.getInt(2), c.getInt(3))
            else RoundTotals(0, 0, 0, 0)
        }

    @Synchronized
    fun quartiers(): List<QuartierStat> {
        val out = ArrayList<QuartierStat>()
        readableDatabase.rawQuery(
            "SELECT quartier, COUNT(*), COALESCE(SUM(CASE WHEN done_at > 0 THEN 1 ELSE 0 END), 0) " +
                "FROM meters GROUP BY quartier ORDER BY COUNT(*) DESC",
            null
        ).use { c ->
            while (c.moveToNext()) out.add(QuartierStat(c.getString(0) ?: "", c.getInt(1), c.getInt(2)))
        }
        return out
    }

    @Synchronized
    fun markDone(id: Long, recordId: String, flags: String, timeMs: Long) {
        val cv = ContentValues()
        cv.put("done_at", timeMs)
        cv.put("record_id", recordId)
        cv.put("flags", flags)
        writableDatabase.update("meters", cv, "id = ?", arrayOf(id.toString()))
    }

    /** تعلّم إحداثية عداد لم تكن له (من أول صورة التُقطت عنده). */
    @Synchronized
    fun learn(id: Long, lat: Double, lon: Double) {
        val cv = ContentValues()
        cv.put("lat", lat)
        cv.put("lon", lon)
        cv.put("learned", 1)
        writableDatabase.update("meters", cv, "id = ? AND lat IS NULL", arrayOf(id.toString()))
    }
}
