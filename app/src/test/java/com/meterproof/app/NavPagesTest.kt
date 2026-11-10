package com.meterproof.app

import com.meterproof.app.NavPages.Page
import com.meterproof.app.NavPages.Section
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavPagesTest {

    @Test fun everySectionIsOnExactlyOnePage() {
        val all = Page.values().flatMap { NavPages.cardsOf(it) }.sorted()
        assertEquals(NavPages.SECTIONS.indices.toList(), all)
    }

    @Test fun everyPageHasAtLeastOneCard() {
        for (p in Page.values()) assertTrue("empty page ${p.id}", NavPages.cardsOf(p).isNotEmpty())
    }

    @Test fun pageIdsAndTitlesAreUnique() {
        assertEquals(Page.values().size, Page.values().map { it.id }.toSet().size)
        assertEquals(Page.values().size, Page.values().map { it.title }.toSet().size)
    }

    @Test fun assignMatchesSectionOrder() {
        val a = NavPages.assign(NavPages.SECTIONS.size)!!
        assertEquals(NavPages.SECTIONS.map { it.page }, a)
    }

    @Test fun mismatchedCardCountFallsBackSoNothingIsHidden() {
        assertNull(NavPages.assign(NavPages.SECTIONS.size - 1))
        assertNull(NavPages.assign(NavPages.SECTIONS.size + 1))
        assertNull(NavPages.assign(0))
    }

    @Test fun homeStaysShort() {
        // الرئيسية: الحالة والجلسة فقط (الشكوى كانت من طول الصفحة الواحدة)
        val home = NavPages.cardsOf(Page.HOME).map { NavPages.SECTIONS[it] }
        assertEquals(listOf(Section.HEADER, Section.SESSION), home)
    }

    @Test fun cardsWithinAPageKeepScreenOrder() {
        for (p in Page.values()) {
            val idx = NavPages.cardsOf(p)
            assertEquals(idx.sorted(), idx)
        }
    }

    @Test fun keyGroupingsMatchTheRoadmap() {
        assertEquals(Page.PHOTOS, Section.EVIDENCE.page)
        assertEquals(Page.MAP, Section.MAP.page)
        assertEquals(Page.ROUND, Section.ROUND.page)
        assertEquals(Page.ROUND, Section.DELIVERY.page)
        assertEquals(Page.SETTINGS, Section.RANDOM.page)
        assertEquals(Page.EXPORT, Section.EXPORT.page)
    }

    @Test fun fromIdRoundTripsAndFallsBackToHome() {
        for (p in Page.values()) assertEquals(p, NavPages.fromId(p.id))
        assertEquals(Page.HOME, NavPages.fromId(null))
        assertEquals(Page.HOME, NavPages.fromId(""))
        assertEquals(Page.HOME, NavPages.fromId("nope"))
        assertNotNull(NavPages.fromId("map"))
    }
}
