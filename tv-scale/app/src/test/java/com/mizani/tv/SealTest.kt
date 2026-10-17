package com.mizani.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

class SealTest {
    @Test fun sealRoundTripsAndWritesThePayloadForThePythonCheck() {
        val salt = Seal.randomBytes(16)
        val key = Seal.deriveKey("correct horse battery staple", salt)
        val json = Seal.readingsJson(listOf(Huawei.Reading(1760670000000, 85.42, 24.1, 420, false),
            Huawei.Reading(1760756400000, 85.1, null, null, true)), "AH100", "mizani-tv/1.0.0")
        assertTrue(json.contains("\"w\":85.42") && json.contains("\"fat\":24.1") && json.contains("\"imp\":420"))
        val sealed = Seal.seal(json, key, salt)
        val f = { k: String -> Regex("\"$k\":\"([^\"]+)\"").find(sealed)!!.groupValues[1] }
        assertEquals(Base64.getEncoder().encodeToString(salt), f("salt"))
        assertEquals(json, Seal.open(f("salt"), f("iv"), f("ct"), key))
        // يكتب الحمولة عشان نتأكد إن scale.py في Whoop يفكها (يشغّله سكربت الاختبار)
        System.getProperty("sealOut")?.takeIf { it.isNotBlank() }?.let { File(it).writeText(sealed) }
    }
}
