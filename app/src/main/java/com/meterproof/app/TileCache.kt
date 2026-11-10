package com.meterproof.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * مخزن البلاطات على الجهاز (SQLite المدمج، بلا مكتبات إضافية). يقرأ منه المُرسِم دون إنترنت،
 * ويكتب إليه كل بلاطة تُعرض أو تُحمَّل. المفتاح يتضمن معرّف الطبقة فلا تختلط الطبقات.
 * يبقى داخل مساحة التطبيق الخاصة (allowBackup=false).
 */
class TileCache private constructor(context: Context) : TileStoreApi {

    private val helper = object : SQLiteOpenHelper(context.applicationContext, "tilecache.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE tiles (k TEXT PRIMARY KEY, d BLOB NOT NULL, t INTEGER NOT NULL)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // لا ترقيات بعد
        }
    }.also { it.setWriteAheadLoggingEnabled(true) }

    fun get(key: String): ByteArray? =
        helper.readableDatabase.rawQuery("SELECT d FROM tiles WHERE k=?", arrayOf(key)).use {
            if (it.moveToFirst()) it.getBlob(0) else null
        }

    override fun has(key: String): Boolean =
        helper.readableDatabase.rawQuery("SELECT 1 FROM tiles WHERE k=? LIMIT 1", arrayOf(key)).use {
            it.moveToFirst()
        }

    override fun put(key: String, bytes: ByteArray) {
        val v = ContentValues().apply {
            put("k", key); put("d", bytes); put("t", System.currentTimeMillis())
        }
        helper.writableDatabase.insertWithOnConflict("tiles", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun count(): Long =
        helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM tiles", null).use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }

    fun sizeBytes(): Long =
        helper.readableDatabase.rawQuery("SELECT COALESCE(SUM(LENGTH(d)),0) FROM tiles", null).use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }

    fun clear() {
        val db = helper.writableDatabase
        db.delete("tiles", null, null)
        try {
            db.execSQL("VACUUM")
        } catch (e: Exception) {
            // الحذف تم؛ تقليص الملف اختياري
        }
    }

    companion object {
        @Volatile private var instance: TileCache? = null

        fun get(context: Context): TileCache =
            instance ?: synchronized(this) {
                instance ?: TileCache(context).also { instance = it }
            }
    }
}
