package com.meterproof.app

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** نوع العنصر المرسوم. الأرقام ثابتة لأنها تُكتب في الملفات المحمّلة. */
object WayKind {
    const val MOTORWAY = 1
    const val MAJOR = 2
    const val MINOR = 3
    const val SERVICE = 4
    const val PATH = 5
    const val BUILDING = 6
    const val WATER_LINE = 7
    const val WATER_AREA = 8

    fun isRoad(k: Int): Boolean = k in MOTORWAY..PATH

    /** يصلح للمشي (يدخل في حساب المسار). الطرق السريعة لا يمشي عليها أحد. */
    fun walkable(k: Int): Boolean = k in MAJOR..PATH

    /**
     * يصنّف وسوم OpenStreetMap لعنصر مرسوم، أو 0 لما نتجاهله.
     * الأولوية للطرق ثم المباني ثم المياه.
     */
    fun classify(highway: String?, building: String?, waterway: String?, natural: String?): Int {
        if (!highway.isNullOrEmpty()) {
            return when (highway) {
                "motorway", "motorway_link" -> MOTORWAY
                "trunk", "trunk_link", "primary", "primary_link",
                "secondary", "secondary_link", "tertiary", "tertiary_link" -> MAJOR
                "residential", "unclassified", "living_street", "road" -> MINOR
                "service" -> SERVICE
                "footway", "pedestrian", "path", "steps", "track", "cycleway", "bridleway" -> PATH
                else -> 0
            }
        }
        if (!building.isNullOrEmpty() && building != "no") return BUILDING
        if (waterway == "river" || waterway == "stream" || waterway == "canal" || waterway == "drain") {
            return WATER_LINE
        }
        if (natural == "water") return WATER_AREA
        return 0
    }
}

/** عنصر مرسوم: خط أو مضلّع. الإحداثيات أعداد صحيحة بالدرجات × 1e6 لتوفير المساحة. */
class OsmWay(val id: Long, val kind: Int, val lat: IntArray, val lon: IntArray) {
    val size: Int get() = lat.size
}

/**
 * ترميز ثنائي بسيط لقطعة واحدة. ملف تالف أو مقطوع يُرفض كله بـ IOException،
 * فلا تُعدّ القطعة مكتملة وتُعاد عند الاستئناف.
 */
object OsmCellCodec {
    private const val MAGIC = 0x4D504131 // "MPA1"
    private const val MAX_WAYS = 2_000_000
    private const val MAX_POINTS = 100_000

    fun write(out: OutputStream, ways: List<OsmWay>) {
        val d = DataOutputStream(out)
        d.writeInt(MAGIC)
        d.writeInt(ways.size)
        for (w in ways) {
            d.writeLong(w.id)
            d.writeByte(w.kind)
            d.writeInt(w.size)
            for (i in 0 until w.size) {
                d.writeInt(w.lat[i])
                d.writeInt(w.lon[i])
            }
        }
        d.writeInt(MAGIC) // علامة نهاية: تثبت أن الكتابة اكتملت
        d.flush()
    }

    fun read(input: InputStream): List<OsmWay> {
        val d = DataInputStream(input)
        if (d.readInt() != MAGIC) throw IOException("ملف خارطة غير صالح")
        val n = d.readInt()
        if (n < 0 || n > MAX_WAYS) throw IOException("ملف خارطة تالف")
        val out = ArrayList<OsmWay>(n)
        for (i in 0 until n) {
            val id = d.readLong()
            val kind = d.readUnsignedByte()
            val pts = d.readInt()
            if (pts < 0 || pts > MAX_POINTS) throw IOException("ملف خارطة تالف")
            val la = IntArray(pts)
            val lo = IntArray(pts)
            for (k in 0 until pts) {
                la[k] = d.readInt()
                lo[k] = d.readInt()
            }
            out.add(OsmWay(id, kind, la, lo))
        }
        if (d.readInt() != MAGIC) throw IOException("ملف خارطة مقطوع")
        return out
    }
}
