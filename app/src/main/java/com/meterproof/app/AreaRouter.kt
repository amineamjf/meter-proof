package com.meterproof.app

/**
 * يبني رسم الطرق من القطع المحمّلة ويحتفظ به حتى يتغير المحتوى. البناء ثقيل نسبيًا، فيُستدعى من خيط خلفي.
 */
class AreaRouter(private val store: AreaStore) {

    @Volatile private var graph: RoadGraph? = null
    private var builtVersion = Long.MIN_VALUE

    /** الرسم الحالي، أو null إن لم تُحمَّل أي قطعة. */
    @Synchronized
    fun graph(): RoadGraph? {
        val v = store.version()
        if (v != builtVersion || graph == null) {
            val all = ArrayList<OsmWay>()
            for (cell in store.allCells()) {
                val ways = store.read(cell) ?: continue
                for (w in ways) if (WayKind.walkable(w.kind)) all.add(w)
            }
            graph = if (all.isEmpty()) null else RoadGraph.build(all)
            builtVersion = v
        }
        return graph
    }
}
