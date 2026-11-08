package com.meterproof.app.delivery

import java.util.Random

/**
 * مولّد تسلسلات خصائص اصطناعية لسيناريوهات التسليم والحركات العادية (للاختبار فقط).
 *
 * تنبيه صادق: هذه المشاهد من تصميمي أنا، وليست لقطات حقيقية من كاميرا الصدر. هي تثبت أن
 * المنطق يعمل كما صُمّم، ولا تثبت دقة الكشف في الميدان.
 */
object SyntheticScenes {

    const val DT = 143L // نحو 7 إطارات في الثانية

    private class Clock(var t: Long = 0L)

    private fun Random.range(lo: Float, hi: Float) = lo + nextFloat() * (hi - lo)

    private fun frames(
        c: Clock, rnd: Random, ms: Long,
        skin: ClosedFloatingPointRange<Float> = 0f..0f,
        skinXY: Pair<Float, Float> = Pair(0.5f, 0.75f),
        held: ClosedFloatingPointRange<Float> = 0f..0f,
        heldXY: Pair<Float, Float> = Pair(0.5f, 0.7f),
        obj: ClosedFloatingPointRange<Float> = 0f..0f,
        objXY: Pair<Float, Float> = Pair(0.5f, 0.75f),
        motion: ClosedFloatingPointRange<Float> = 0.01f..0.03f
    ): List<Feat> {
        val out = ArrayList<Feat>()
        var spent = 0L
        while (spent < ms) {
            val s = rnd.range(skin.start, skin.endInclusive)
            val h = rnd.range(held.start, held.endInclusive)
            val o = rnd.range(obj.start, obj.endInclusive)
            out.add(
                Feat(
                    t = c.t,
                    skin = s,
                    skinX = if (s > 0f) skinXY.first else -1f,
                    skinY = if (s > 0f) skinXY.second else -1f,
                    held = h,
                    heldX = if (h > 0f) heldXY.first else -1f,
                    heldY = if (h > 0f) heldXY.second + rnd.range(-0.02f, 0.02f) else -1f,
                    obj = o,
                    objX = if (o > 0f) objXY.first + rnd.range(-0.01f, 0.01f) else -1f,
                    objY = if (o > 0f) objXY.second + rnd.range(-0.01f, 0.01f) else -1f,
                    objW = if (o > 0f) 0.16f else 0f,
                    objH = if (o > 0f) 0.12f else 0f,
                    motion = rnd.range(motion.start, motion.endInclusive),
                    lum = 0.5f
                )
            )
            c.t += DT
            spent += DT
        }
        return out
    }

    /** تسليم حقيقي: ورقة في اليد، تُوضع، تنسحب اليد، يبقى جسم ثابت. place: ON أو BESIDE. */
    fun deliver(seed: Long, besideLeft: Boolean? = null): List<Feat> {
        val rnd = Random(seed)
        val c = Clock(1000L)
        val x = when (besideLeft) {
            null -> 0.5f + rnd.range(-0.1f, 0.1f)
            true -> rnd.range(0.12f, 0.25f)
            false -> rnd.range(0.75f, 0.88f)
        }
        val y = rnd.range(0.62f, 0.82f)
        val all = ArrayList<Feat>()
        all += frames(c, rnd, 1500)                                              // وقوف
        all += frames(
            c, rnd, 2500, skin = 0.05f..0.09f, held = 0.012f..0.03f,
            heldXY = Pair(x, y), motion = 0.03f..0.06f
        )                                                                         // يد بورقة
        all += frames(
            c, rnd, 800, skin = 0.04f..0.06f, held = 0.008f..0.012f,
            heldXY = Pair(x, y), motion = 0.03f..0.05f
        )                                                                         // ترك الورقة
        all += frames(c, rnd, 900, skin = 0.0f..0.008f, motion = 0.03f..0.05f)  // انسحاب
        all += frames(
            c, rnd, 1800, obj = 0.015f..0.03f, objXY = Pair(x, y), motion = 0.01f..0.03f
        )                                                                         // جسم ثابت
        return all
    }

    /** تسليم تحت الباب: تختفي الورقة واليد ظاهرة قرب أسفل الإطار، ولا يبقى جسم. */
    fun deliverUnder(seed: Long): List<Feat> {
        val rnd = Random(seed)
        val c = Clock(1000L)
        val all = ArrayList<Feat>()
        all += frames(c, rnd, 1500)
        all += frames(
            c, rnd, 2500, skin = 0.05f..0.09f, held = 0.012f..0.03f,
            heldXY = Pair(0.5f, 0.85f), motion = 0.03f..0.06f
        )
        all += frames(c, rnd, 900, skin = 0.05f..0.07f, held = 0f..0f, motion = 0.03f..0.05f)
        all += frames(c, rnd, 900, skin = 0.0f..0.008f, motion = 0.03f..0.05f)
        all += frames(c, rnd, 4000, motion = 0.01f..0.03f)
        return all
    }

    fun walk(seed: Long): List<Feat> {
        val rnd = Random(seed)
        val c = Clock(1000L)
        return frames(c, rnd, 12000, skin = 0f..0.03f, motion = 0.12f..0.30f)
    }

    fun bend(seed: Long): List<Feat> {
        val rnd = Random(seed)
        val c = Clock(1000L)
        val all = ArrayList<Feat>()
        all += frames(c, rnd, 1500)
        all += frames(c, rnd, 3000, skin = 0f..0.07f, motion = 0.18f..0.40f)
        all += frames(c, rnd, 2000, motion = 0.01f..0.04f)
        return all
    }

    fun touchDoor(seed: Long): List<Feat> {
        val rnd = Random(seed)
        val c = Clock(1000L)
        val all = ArrayList<Feat>()
        all += frames(c, rnd, 1500)
        all += frames(c, rnd, 4000, skin = 0.04f..0.08f, motion = 0.03f..0.06f)
        all += frames(c, rnd, 2000)
        return all
    }

    /** يد تحمل ورقة ثم تنسحب بالورقة (أو تعيدها إلى الجيب): لا جسم يبقى. */
    fun movePaper(seed: Long): List<Feat> {
        val rnd = Random(seed)
        val c = Clock(1000L)
        val all = ArrayList<Feat>()
        all += frames(c, rnd, 1500)
        all += frames(
            c, rnd, 2500, skin = 0.05f..0.09f, held = 0.012f..0.03f,
            heldXY = Pair(0.5f, 0.55f), motion = 0.04f..0.07f
        )
        all += frames(c, rnd, 900, skin = 0.0f..0.008f, motion = 0.04f..0.07f)
        all += frames(c, rnd, 4000, motion = 0.01f..0.03f)
        return all
    }

    fun handOnly(seed: Long): List<Feat> {
        val rnd = Random(seed)
        val c = Clock(1000L)
        return frames(c, rnd, 5000, skin = 0.04f..0.08f, motion = 0.03f..0.06f)
    }

    /** جسم ساطع كبير ثابت (جدار أو باب أبيض) بلا يد: ليس ورقة. */
    fun brightWall(seed: Long): List<Feat> {
        val rnd = Random(seed)
        val c = Clock(1000L)
        return frames(c, rnd, 6000, obj = 0.33f..0.40f, objXY = Pair(0.5f, 0.5f), motion = 0.01f..0.03f)
    }

    /** جسم ساطع صغير يظهر دون أن تمرّ يد تحمل ورقة (مثلًا شيء ساقط أو ضوء). */
    fun objectWithoutHand(seed: Long): List<Feat> {
        val rnd = Random(seed)
        val c = Clock(1000L)
        val all = ArrayList<Feat>()
        all += frames(c, rnd, 1500)
        all += frames(c, rnd, 4000, obj = 0.015f..0.03f, objXY = Pair(0.5f, 0.7f))
        return all
    }

    fun asSamples(label: Label, gen: (Long) -> List<Feat>, seeds: LongRange): List<Sample> =
        seeds.map { Sample(label, 1, gen(it)) }
}
