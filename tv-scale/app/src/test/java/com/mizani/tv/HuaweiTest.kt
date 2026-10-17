package com.mizani.tv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * المراجع: اختبارات openScale (HuaweiAhCh100HandlerTest) — منها إطاران حقيقيان من ميزان CH100
 * فُكّا وطابقا شاشة الميزان (126.6 كجم / 33.8% / 306 Ω).
 */
class HuaweiTest {
    private val captureMac = Huawei.parseMac("5C:CA:D3:14:2B:B3")!!
    private val capture1 = Huawei.hex("bc110eabf78678303ba462fc93d1db4b348861")
    private val capture2 = Huawei.hex("bc110eabf48679303ba462fc93dce84b3f8861")

    private fun scaleFrame(op: Int, payload: ByteArray, mac: ByteArray) =
        byteArrayOf(0xBD.toByte(), (payload.size + 1).toByte(), op.toByte()) + Huawei.xorMac(payload, mac)

    @Test fun authTokenAndKeyMatchReference() {
        val mac = Huawei.parseMac("5CCAD3087B4F")!!
        assertEquals("11 22 33 44 55 10 01", Huawei.toHex(Huawei.authToken(1)))
        assertEquals("4de8e04c2e5f5d2a980fde3456732156", Huawei.toHex(Huawei.magicKey(Huawei.authToken(1), mac)).replace(" ", ""))
    }

    @Test fun referenceFrameDecodes() {
        val mac = Huawei.parseMac("5C:CA:D3:08:7B:4F")!!
        val m = Huawei.decode(Huawei.hex("bc100edef702c9bbf2176c755b6eccdd5b92b0"), Huawei.magicKey(Huawei.authToken(1), mac), mac)!!
        assertEquals(97.0, m.weightKg, 1e-9); assertEquals(28.0, m.fatPct, 1e-9); assertEquals(540, m.impedance)
        val c = Calendar.getInstance().apply { timeInMillis = m.timeMs!! }
        assertEquals(2026, c.get(Calendar.YEAR)); assertEquals(4, c.get(Calendar.MONTH)); assertEquals(19, c.get(Calendar.DAY_OF_MONTH))
        assertEquals(8, c.get(Calendar.HOUR_OF_DAY)); assertEquals(12, c.get(Calendar.MINUTE)); assertEquals(33, c.get(Calendar.SECOND))
    }

    @Test fun realHardwareFramesMatchTheDisplay() {
        val key = Huawei.magicKey(Huawei.authToken(1), captureMac)
        val a = Huawei.decode(capture1, key, captureMac)!!
        assertEquals(126.6, a.weightKg, 1e-9); assertEquals(33.8, a.fatPct, 1e-9); assertEquals(306, a.impedance); assertTrue(a.valid)
        val b = Huawei.decode(capture2, key, captureMac)!!
        assertEquals(126.5, b.weightKg, 1e-9); assertEquals(33.9, b.fatPct, 1e-9); assertEquals(313, b.impedance)
    }

    @Test fun sessionFlow() {
        val s = Huawei.Session(captureMac, Huawei.Profile(180, 35, false, 126.0))
        val k = s.kick()
        assertEquals(1, k.writes.size)
        assertArrayEquals(byteArrayOf(0xDB.toByte(), 8, 36) + Huawei.xorMac(Huawei.authToken(1), captureMac), k.writes[0].first)
        assertEquals(0, s.kick().writes.size)   // ما نعيدها

        val ok = s.onData(scaleFrame(0x26, byteArrayOf(1), captureMac))
        assertTrue(s.authorised)
        assertEquals(listOf(2, 8, 9, 12), ok.writes.map { it.first[2].toInt() })
        val ui = ok.writes[2].first
        assertEquals(0xDC, ui[0].toInt() and 0xFF); assertEquals(14, ui[1].toInt()); assertEquals(19, ui.size)
        val key = Huawei.magicKey(Huawei.authToken(1), captureMac)
        val plain = Huawei.aesCtr(Huawei.xorMac(ui.copyOfRange(3, ui.size), captureMac), key)
        assertArrayEquals(Huawei.authToken(1) + byteArrayOf(35, 180.toByte(), 0, (1260 and 0xFF).toByte(), (1260 shr 8).toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0x1C, 0xE2.toByte()), plain)
        assertEquals(0, s.onData(scaleFrame(0x26, byteArrayOf(1), captureMac)).writes.size)  // تحقق مكرر

        val m = s.onData(capture1)
        assertEquals(1, m.readings.size)
        assertEquals(126.6, m.readings[0].weightKg, 1e-9); assertEquals(33.8, m.readings[0].fatPct!!, 1e-9)
        assertEquals(306, m.readings[0].impedance); assertFalse(m.readings[0].fromHistory)
        assertEquals(listOf(19, 11), m.writes.map { it.first[2].toInt() })      // تأكيد + طلب السجل
        assertEquals(7, m.writes[1].first[1].toInt())                            // طول طلب السجل 7
        assertEquals(0, s.onData(byteArrayOf(0xBC.toByte(), 0x11, 0x8E.toByte(), 1, 2, 3)).readings.size)

        val f = s.setProfile(Huawei.Profile(160, 30, true, 60.0))
        val fp = Huawei.aesCtr(Huawei.xorMac(f.writes[0].first.copyOfRange(3, 19), captureMac), key)
        assertEquals(30 or 0x80, fp[7].toInt() and 0xFF); assertEquals(160, fp[8].toInt() and 0xFF)
    }

    @Test fun historyRecordAsksForTheNextOne() {
        val s = Huawei.Session(captureMac, Huawei.Profile())
        s.onData(scaleFrame(0x26, byteArrayOf(1), captureMac))
        val h = capture1.copyOf().also { it[2] = 0x10 }
        val out = s.onData(h)
        assertTrue(out.readings[0].fromHistory)
        assertEquals(listOf(11), out.writes.map { it.first[2].toInt() })
        assertEquals(2, out.writes[0].first[1].toInt())
    }

    @Test fun refusedAuthRetriesThenBinds() {
        val s = Huawei.Session(captureMac, Huawei.Profile())
        val no = { s.onData(scaleFrame(0x26, byteArrayOf(0), captureMac)).writes.map { it.first[2].toInt() } }
        assertEquals(listOf(36), no()); assertEquals(listOf(36), no()); assertEquals(listOf(37), no())
        assertEquals(listOf(36), s.onData(scaleFrame(0x27, byteArrayOf(1), captureMac)).writes.map { it.first[2].toInt() })
    }

    @Test fun wrongMacGivesDecodeErrorNotABogusWeight() {
        val wrong = Huawei.parseMac("5C:CA:D3:14:2B:B4")!!
        val s = Huawei.Session(wrong, Huawei.Profile())
        s.onData(scaleFrame(0x26, byteArrayOf(1), wrong))
        val out = s.onData(capture1)
        assertEquals(0, out.readings.size); assertEquals("decode", out.error)
    }

    @Test fun namesAndMacs() {
        assertTrue(Huawei.isHuaweiName("AH100")); assertTrue(Huawei.isHuaweiName("HUAWEI AH100"))
        assertTrue(Huawei.isHuaweiName("CH100-12")); assertFalse(Huawei.isHuaweiName("CH100S")); assertFalse(Huawei.isHuaweiName(null))
        assertNull(Huawei.parseMac("5C:CA:D3")); assertNull(Huawei.parseMac("00:00:00:00:00:00")); assertNotNull(Huawei.parseMac("5cca d314 2bb3"))
    }
}
