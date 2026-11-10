package com.meterproof.app

/**
 * توزيع أقسام الشاشة الرئيسية على صفحات القائمة الجانبية (منطق نقي بلا أندرويد، يُختبر على JVM).
 *
 * الأقسام بالترتيب الذي تُبنى به بطاقات [MainActivity.buildUi]؛ كل قسم بطاقة واحدة. لا يتغير أي زر ولا
 * سلوكه: تُنقل البطاقات بين الصفحات فقط.
 *
 * أمان: إن لم يطابق عدد البطاقات الفعلي [SECTIONS] (تعديل لاحق على الأقسام دون تحديث هذا الملف)
 * يعيد [assign] القيمة null، فتعرض الشاشة كل البطاقات في صفحة واحدة ولا يُخفى أي زر.
 */
object NavPages {

    enum class Page(val id: String, val title: String) {
        HOME("home", "الرئيسية"),
        PHOTOS("photos", "الصور والتدريب"),
        MAP("map", "الخارطة"),
        ROUND("round", "الجولة"),
        SETTINGS("settings", "الإعدادات"),
        EXPORT("export", "التصدير والتحقق")
    }

    enum class Section(val page: Page) {
        HEADER(Page.HOME),
        SETUP(Page.SETTINGS),
        RANDOM(Page.SETTINGS),
        SESSION(Page.HOME),
        EVIDENCE(Page.PHOTOS),
        MAP(Page.MAP),
        EXPORT(Page.EXPORT),
        SUMMARY(Page.EXPORT),
        ROUND(Page.ROUND),
        DELIVERY(Page.ROUND)
    }

    /** ترتيب البطاقات كما تُبنى في الشاشة. */
    val SECTIONS: List<Section> = Section.values().toList()

    /** صفحة كل بطاقة بالترتيب، أو null إن لم يطابق [cardCount] عدد الأقسام المعروفة. */
    fun assign(cardCount: Int): List<Page>? =
        if (cardCount == SECTIONS.size) SECTIONS.map { it.page } else null

    /** الصفحة بمعرّفها المحفوظ، أو الرئيسية إن كان المعرّف مجهولًا أو فارغًا. */
    fun fromId(id: String?): Page = Page.values().firstOrNull { it.id == id } ?: Page.HOME

    /** أرقام البطاقات التي تظهر في صفحة، بترتيبها في الشاشة. */
    fun cardsOf(page: Page): List<Int> =
        SECTIONS.indices.filter { SECTIONS[it].page == page }
}
