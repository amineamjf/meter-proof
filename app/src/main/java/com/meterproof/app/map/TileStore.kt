package com.meterproof.app.map

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File

/**
 * مخزن بلاطات محلي بصيغة MBTiles (SQLite المدمج في أندرويد، بلا مكتبات إضافية).
 * يقرأ منه المُرسِم دون إنترنت، وتكتب إليه شاشة التنزيل مرة واحدة.
 * الملف داخل مجلد التطبيق الخاص (allowBackup=false)، لا يخرج من الجهاز.
 */
class TileStore private constructor(private val db: SQLiteDatabase) : TileProvider, TileSink {

    private val cache = object : LruCache<Long, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
    }
    private val misses = HashSet<Long>()
    private val writeLock = Any()
    private var pending = 0

    @Volatile override var minZoom = 0; private set
    @Volatile override var maxZoom = 0; private set
    @Volatile var tileCount = 0L; private set

    override val attribution: String get() = meta("attribution") ?: "© OpenStreetMap contributors"
    val hasTiles: Boolean get() = tileCount > 0

    init { refresh() }

    private fun key(z: Int, x: Int, y: Int): Long = (z.toLong() shl 42) or (x.toLong() shl 21) or y.toLong()

    override fun tile(z: Int, x: Int, y: Int): Bitmap? {
        val k = key(z, x, y)
        cache.get(k)?.let { return it }
        synchronized(misses) { if (k in misses) return null }
        val bytes = readBlob(z, x, y)
        val bmp = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        if (bmp != null) cache.put(k, bmp) else synchronized(misses) { misses.add(k) }
        return bmp
    }

    private fun readBlob(z: Int, x: Int, y: Int): ByteArray? {
        db.rawQuery(
            "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
            arrayOf(z.toString(), x.toString(), TileMath.tmsRow(y, z).toString())
        ).use { c -> return if (c.moveToFirst()) c.getBlob(0) else null }
    }

    override fun has(z: Int, x: Int, y: Int): Boolean =
        db.rawQuery(
            "SELECT 1 FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=? LIMIT 1",
            arrayOf(z.toString(), x.toString(), TileMath.tmsRow(y, z).toString())
        ).use { it.moveToFirst() }

    override fun put(z: Int, x: Int, y: Int, bytes: ByteArray) {
        synchronized(writeLock) {
            if (pending == 0) db.beginTransaction()
            val v = ContentValues().apply {
                put("zoom_level", z); put("tile_column", x)
                put("tile_row", TileMath.tmsRow(y, z)); put("tile_data", bytes)
            }
            db.insertWithOnConflict("tiles", null, v, SQLiteDatabase.CONFLICT_REPLACE)
            pending++
            if (pending >= 50) commitLocked()
        }
        val k = key(z, x, y)
        cache.remove(k)
        synchronized(misses) { misses.remove(k) }
    }

    private fun commitLocked() {
        if (pending > 0) {
            db.setTransactionSuccessful()
            db.endTransaction()
            pending = 0
        }
    }

    /** يثبّت ما كُتب ويحدّث الإحصاءات. يُستدعى عند انتهاء التنزيل أو إلغائه. */
    fun flush() {
        synchronized(writeLock) { commitLocked() }
        refresh()
    }

    fun refresh() {
        db.rawQuery("SELECT COUNT(*), IFNULL(MIN(zoom_level),0), IFNULL(MAX(zoom_level),0) FROM tiles", null).use {
            if (it.moveToFirst()) {
                tileCount = it.getLong(0); minZoom = it.getInt(1); maxZoom = it.getInt(2)
            }
        }
    }

    fun meta(name: String): String? =
        db.rawQuery("SELECT value FROM metadata WHERE name=?", arrayOf(name)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    fun setMeta(name: String, value: String) {
        val v = ContentValues().apply { put("name", name); put("value", value) }
        db.insertWithOnConflict("metadata", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun sizeBytes(): Long = File(db.path).length()

    companion object {
        private const val FILE = "offline.mbtiles"
        @Volatile private var instance: TileStore? = null

        private fun file(context: Context): File =
            File(context.applicationContext.filesDir, "maps").apply { mkdirs() }.let { File(it, FILE) }

        @Synchronized
        fun get(context: Context): TileStore {
            instance?.let { return it }
            val db = SQLiteDatabase.openOrCreateDatabase(file(context), null)
            db.enableWriteAheadLogging()
            db.execSQL("CREATE TABLE IF NOT EXISTS metadata (name TEXT, value TEXT)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS metadata_name ON metadata (name)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS tiles (zoom_level INTEGER, tile_column INTEGER, " +
                    "tile_row INTEGER, tile_data BLOB)"
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS tile_index ON tiles (zoom_level, tile_column, tile_row)")
            return TileStore(db).also { instance = it }
        }

        /** المخزن إن كان فيه بلاطات، وإلا null (فيعمل المُرسِم بالرسم المبسط كما كان). */
        fun providerOrNull(context: Context): TileProvider? =
            try {
                if (instance == null && !file(context).exists()) null
                else get(context).takeIf { it.hasTiles }
            } catch (e: Exception) { null }

        @Synchronized
        fun deleteAll(context: Context) {
            instance?.db?.close()
            instance = null
            val f = file(context)
            f.delete()
            File(f.path + "-wal").delete(); File(f.path + "-shm").delete(); File(f.path + "-journal").delete()
        }
    }
}
