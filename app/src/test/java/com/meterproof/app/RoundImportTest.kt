package com.meterproof.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RoundImportTest {

    // ---------------------------------------------------------------- xlsx مصطنع يحاكي بنية الملف الحقيقي

    private val headers = listOf(
        "Tournée", "N° Compteur", "Client / Nom", "Adresse", "GPS (carte)", "N° Contrat",
        "Ancien index", "Nouvel index", "Consommation (m³)", "Anomalie", "Annotation", "Statut", "Note", "Photo(s)"
    )

    /** يبني xlsx في الذاكرة: sharedStrings بعد الورقة (كما في الملف الحقيقي)، وخلية GPS معادلة t="str". */
    private fun buildXlsx(dataRows: List<List<String?>>): ByteArray {
        val shared = ArrayList<String>()
        fun sidx(s: String): Int {
            val i = shared.indexOf(s)
            if (i >= 0) return i
            shared.add(s)
            return shared.size - 1
        }

        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
        // عنوان التقرير في الصف 1 وصف فارغ مغلق ذاتيًا
        sb.append("""<row r="1"><c r="A1" t="s"><v>${sidx("Rapport de relève des compteurs")}</v></c></row>""")
        sb.append("""<row r="2" spans="1:14" />""")
        sb.append("""<row r="3"><c r="A3" s="1" /></row>""")
        sb.append("""<row r="4">""")
        headers.forEachIndexed { i, h ->
            sb.append("""<c r="${'A' + i}4" t="s"><v>${sidx(h)}</v></c>""")
        }
        sb.append("</row>")
        var r = 5
        for (row in dataRows) {
            sb.append("""<row r="$r">""")
            row.forEachIndexed { i, v ->
                val ref = "${'A' + i}$r"
                when {
                    v == null -> sb.append("""<c r="$ref" s="2" />""")
                    i == 4 -> sb.append(
                        """<c r="$ref" s="19" t="str"><f>HYPERLINK("https://www.google.com/maps?q=$v","$v")</f><v>$v</v></c>"""
                    )
                    i == 6 || i == 7 || i == 8 -> sb.append("""<c r="$ref" s="4"><v>$v</v></c>""")
                    else -> sb.append("""<c r="$ref" s="2" t="s"><v>${sidx(v)}</v></c>""")
                }
            }
            sb.append("</row>")
            r++
        }
        sb.append("</sheetData></worksheet>")

        val sst = StringBuilder()
        sst.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sst.append("""<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        for (s in shared) {
            val esc = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            sst.append("""<si><t xml:space="preserve">$esc</t></si>""")
        }
        sst.append("</sst>")

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            z.write(sb.toString().toByteArray(Charsets.UTF_8))
            z.closeEntry()
            z.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
            z.write(sst.toString().toByteArray(Charsets.UTF_8))
            z.closeEntry()
        }
        return out.toByteArray()
    }

    private fun row(
        meter: String?, address: String, gps: String?, contract: String = "123", old: String? = "499"
    ): List<String?> = listOf(
        null, meter, "CLIENT TEST", address, gps, contract, old, "505", "6", "RAS", "Aucune", "Non vu", null, null
    )

    // ---------------------------------------------------------------- xlsx

    @Test
    fun xlsx_is_parsed_with_gps_formula_cells_and_empty_rows() {
        val bytes = buildXlsx(
            listOf(
                row("4S2A18803701", "N° 12 RUE 38 QUARTIER EL KOUCH", "34.195681, -5.710463"),
                row("522022030769", "N° 7 RUE 38 QUARTIER EL KOUCH", null),
                row("4S2A181356001", "N° 5 RUE 17 QUARTIER SAHRAOUA", "34.195693, -5.710518")
            )
        )
        val res = RoundImport.parseFile(bytes)
        assertNull(res.error)
        assertEquals(3, res.meters.size)
        assertEquals(2, res.withGps)

        val first = res.meters[0]
        assertEquals("4S2A18803701", first.meterNo)
        assertEquals("EL KOUCH", first.quartier)
        assertEquals(34.195681, first.lat!!, 1e-9)
        assertEquals(-5.710463, first.lon!!, 1e-9)
        assertEquals(499L, first.oldIndex)
        assertEquals("123", first.contract)

        assertNull(res.meters[1].lat)
        assertEquals("SAHRAOUA", res.meters[2].quartier)
    }

    @Test
    fun customer_name_and_new_index_are_read_in_experiment_build() {
        val res = RoundImport.parseFile(
            buildXlsx(listOf(row("A1", "RUE 1 QUARTIER X", "34.1, -5.1")))
        )
        val m = res.meters[0]
        if (CustomerInfo.SHOW) {
            assertEquals("CLIENT TEST", m.customerName)
        } else {
            // عند إخفاء بيانات الزبون لا يُنقل الاسم إلى الهاتف إطلاقًا
            assertEquals("", m.customerName)
        }
        assertEquals(499L, m.oldIndex)
        assertEquals(505L, m.newIndex)
    }

    @Test
    fun customer_name_header_aliases_are_detected() {
        val aliases = listOf("Client / Nom", "Nom Client", "Abonné", "Titulaire", "Nom")
        for (h in aliases) {
            val cols = RoundColumns.detect(listOf("N° Compteur", "Adresse", h))
            if (CustomerInfo.SHOW) assertEquals("alias: $h", 2, cols.customerName)
        }
        assertEquals(3, RoundColumns.detect(listOf("N° Compteur", "Adresse", "x", "Nouvel index")).newIndex)
    }

    @Test
    fun duplicates_and_rows_without_meter_are_counted_and_skipped() {
        val res = RoundImport.parseFile(
            buildXlsx(
                listOf(
                    row("A1", "RUE 1 QUARTIER X", "34.1, -5.1"),
                    row("A1", "RUE 2 QUARTIER X", "34.2, -5.2"),
                    row(null, "RUE 3 QUARTIER X", "34.3, -5.3")
                )
            )
        )
        assertEquals(1, res.meters.size)
        assertEquals(1, res.duplicates)
        assertEquals(1, res.skippedNoMeter)
        assertEquals(3, res.scannedRows)
    }

    @Test
    fun entities_in_shared_strings_are_decoded() {
        val res = RoundImport.parseFile(
            buildXlsx(listOf(row("A1", "RUE A&B QUARTIER AIN & NOUR", "34.1, -5.1")))
        )
        assertEquals("RUE A&B QUARTIER AIN & NOUR", res.meters[0].address)
        assertEquals("AIN & NOUR", res.meters[0].quartier)
    }

    @Test
    fun file_without_round_table_gives_clear_error() {
        val res = RoundImport.parse(listOf(listOf("a", "b"), listOf("1", "2")))
        assertNotNull(res.error)
        assertTrue(res.meters.isEmpty())
    }

    // ---------------------------------------------------------------- CSV

    @Test
    fun csv_with_semicolons_quotes_and_bom() {
        val csv = "﻿N° Compteur;Adresse;GPS;Ancien index\n" +
            "M1;\"RUE 1, QUARTIER ZAOUIA\";34.1,-5.1;10\n" +
            "M2;RUE 2 QUARTIER ZAOUIA;;20\n"
        val res = RoundImport.parseFile(csv.toByteArray(Charsets.UTF_8))
        assertNull(res.error)
        assertEquals(2, res.meters.size)
        assertEquals("ZAOUIA", res.meters[0].quartier)
        assertEquals(34.1, res.meters[0].lat!!, 1e-9)
        assertNull(res.meters[1].lat)
        assertEquals(20L, res.meters[1].oldIndex)
    }

    // ---------------------------------------------------------------- الأعمدة والحي والإحداثية

    @Test
    fun header_detection_ignores_accents_symbols_and_case() {
        val c = RoundColumns.detect(listOf("X", "Nº  COMPTEUR", "adresse", "Gps (Carte)", "N° Contrat", "Ancien Index"))
        assertEquals(1, c.meter)
        assertEquals(2, c.address)
        assertEquals(3, c.gps)
        assertEquals(4, c.contract)
        assertEquals(5, c.oldIndex)
        assertEquals(-1, c.quartier)
    }

    @Test
    fun quartier_extraction_from_messy_addresses() {
        assertEquals("EL KOUCH", Quartier.of("N° 12 RUE 38 QUARTIER EL KOUCH"))
        assertEquals("ZAOUIA", Quartier.of("N° 3 QUARTIER ZAOUIA, 160  EAU 4YK0F-SIDI KACEM 16104"))
        assertEquals("ZAOUIA", Quartier.of("N° 3 QUARTIER ZAOUIA  EAU 4YK0E-SIDI KACEM 16104"))
        assertEquals("MILOUD", Quartier.of("rue 5 quartier miloud"))
        assertEquals(Quartier.UNKNOWN, Quartier.of("RUE 5 SANS MENTION"))
        assertEquals(Quartier.UNKNOWN, Quartier.of("RUE 5 QUARTIER"))
    }

    @Test
    fun gps_parsing() {
        assertEquals(34.195681 to -5.710463, GpsParse.parse("34.195681, -5.710463"))
        assertEquals(34.1 to -5.1, GpsParse.parse(" 34.1 ; -5.1 "))
        assertNull(GpsParse.parse(""))
        assertNull(GpsParse.parse("0.0, 0.0"))
        assertNull(GpsParse.parse("134.5, -5.1"))
        assertNull(GpsParse.parse("abc"))
    }

    // ---------------------------------------------------------------- الأقرب أولًا

    private fun item(id: Long, address: String, lat: Double?, lon: Double?) = MeterItem(
        id, "M$id", "", address, "Q", lat, lon, null, "", null, false, 0L, null, ""
    )

    @Test
    fun nearest_first_then_meters_without_gps_by_address() {
        val base = 34.0
        val items = listOf(
            item(1, "C", base + 300.0 / 111_320.0, -5.0),
            item(2, "B", null, null),
            item(3, "A", base + 50.0 / 111_320.0, -5.0),
            item(4, "A2", null, null)
        )
        val ranked = RoundLogic.rank(items, base, -5.0, 10)
        // بلا إحداثية: "A2" قبل "B" بترتيب العنوان
        assertEquals(listOf(3L, 1L, 4L, 2L), ranked.map { it.item.id })
        assertEquals(50.0, ranked[0].distanceM!!, 1.0)
        assertNull(ranked[2].distanceM)
    }

    @Test
    fun without_user_location_everything_is_sorted_by_address_and_limited() {
        val items = listOf(item(1, "B", 34.0, -5.0), item(2, "A", 34.0, -5.0), item(3, "C", 34.0, -5.0))
        val ranked = RoundLogic.rank(items, null, null, 2)
        assertEquals(listOf(2L, 1L), ranked.map { it.item.id })
    }

    @Test
    fun near_limit_grows_with_poor_accuracy() {
        assertEquals(50.0, RoundLogic.nearLimitM(10f), 0.001)
        assertEquals(90.0, RoundLogic.nearLimitM(60f), 0.001)
    }

    // ---------------------------------------------------------------- وصول موثّق عند التوقف قرب عداد

    @Test
    fun arrival_picks_nearest_meter_within_radius() {
        val base = 34.0
        val items = listOf(
            item(1, "A", base + 10.0 / 111_320.0, -5.0),
            item(2, "B", base + 40.0 / 111_320.0, -5.0)
        )
        val a = RoundLogic.pickArrival(items, base, -5.0, 10f)!!
        assertEquals(1L, a.meter.id)
        assertTrue(!a.ambiguous)
    }

    @Test
    fun arrival_is_ambiguous_when_two_meters_are_equally_close() {
        val base = 34.0
        val items = listOf(
            item(1, "A", base + 10.0 / 111_320.0, -5.0),
            item(2, "B", base + 15.0 / 111_320.0, -5.0)
        )
        assertTrue(RoundLogic.pickArrival(items, base, -5.0, 10f)!!.ambiguous)
    }

    @Test
    fun arrival_ignores_far_done_arrived_and_gpsless_meters() {
        val base = 34.0
        val near = base + 5.0 / 111_320.0
        val items = listOf(
            item(1, "far", base + 100.0 / 111_320.0, -5.0),
            item(2, "done", near, -5.0).copy(doneAt = 5L),
            item(3, "arrived", near, -5.0).copy(arrivalAt = 5L),
            item(4, "nogps", null, null)
        )
        assertNull(RoundLogic.pickArrival(items, base, -5.0, 10f))
    }

    @Test
    fun arrival_radius_grows_with_poor_accuracy_but_is_capped() {
        assertEquals(25.0, RoundLogic.arrivalRadiusM(10f), 0.001)
        assertEquals(30.0, RoundLogic.arrivalRadiusM(20f), 0.001)
        assertEquals(45.0, RoundLogic.arrivalRadiusM(100f), 0.001)
    }

    // ---------------------------------------------------------------- عدادات الجولة على الخارطة

    @Test
    fun marker_is_null_without_coordinates() {
        assertNull(RoundLogic.marker(item(1, "A", null, null)))
    }

    @Test
    fun marker_state_follows_delivery_and_arrival() {
        val base = item(1, "A", 34.0, -5.0)
        assertEquals(MeterMarkState.PENDING, RoundLogic.marker(base)!!.state)
        assertEquals(MeterMarkState.ARRIVED, RoundLogic.marker(base.copy(arrivalAt = 5L))!!.state)
        assertEquals(MeterMarkState.DONE, RoundLogic.marker(base.copy(doneAt = 9L))!!.state)
        assertEquals(
            MeterMarkState.FLAGGED,
            RoundLogic.marker(base.copy(doneAt = 9L, flags = "FAR_FROM_METER"))!!.state
        )
        // التسليم المسجَّل يغلب الوصول
        assertEquals(MeterMarkState.DONE, RoundLogic.marker(base.copy(arrivalAt = 5L, doneAt = 9L))!!.state)
    }

    @Test
    fun marker_links_photo_and_visit_time() {
        val m = item(1, "A", 34.0, -5.0)
            .copy(arrivalAt = 5L, arrivalRecord = "arr", doneAt = 9L, recordId = "done")
        val p = RoundLogic.marker(m)!!
        assertEquals("done", p.photoId)
        assertEquals(9L, p.visitedAt)
        val arrivedOnly = RoundLogic.marker(item(2, "B", 34.0, -5.0).copy(arrivalAt = 5L, arrivalRecord = "arr"))!!
        assertEquals("arr", arrivedOnly.photoId)
        assertEquals(5L, arrivedOnly.visitedAt)
        assertNull(RoundLogic.marker(item(3, "C", 34.0, -5.0))!!.photoId)
    }
}
