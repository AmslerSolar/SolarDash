// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec

/** A software P-256 key, as the tests' stand-in for the collector's keystore key. */
class SoftwareSigner(private val privateKey: java.security.PrivateKey, override val publicKey: ByteArray) : PlantSigner {
    override fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(privateKey); update(data); sign()
    }

    val pkcs8: ByteArray get() = privateKey.encoded

    companion object {
        fun generate(): SoftwareSigner {
            val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            return SoftwareSigner(pair.private, pair.public.encoded)
        }

        fun of(pkcs8: ByteArray, spki: ByteArray) =
            SoftwareSigner(KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8)), spki)
    }
}

class WireTest {
    private val signer = SoftwareSigner.generate()
    private val key = Wire.randomBytes(32)

    @Test
    fun aSealedEnvelopeRoundTripsAndIsBoundToItsHeader() {
        val e = Wire.envelope(signer, Kind.DAY, 7, 1_762_250_000_000, "d2026-11-04", "{\"x\":1}".toByteArray(), key)
        val back = Envelope.decode(e.encode())
        assertTrue(Wire.verify(back, signer.publicKey))
        assertEquals("{\"x\":1}", String(Wire.open(back, key)))
        // Another name under the same payload and signature: the signature fails, and so does decryption.
        val renamed = Envelope(back.kind, back.seq, back.time, "d2026-11-05", back.payload, back.signature)
        assertFalse(Wire.verify(renamed, signer.publicKey))
        assertTrue(runCatching { Wire.open(renamed, key) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { Wire.open(back, Wire.randomBytes(32)) }.exceptionOrNull() is IOException)
    }

    @Test
    fun framesCarrySeveralEnvelopes() {
        val a = Wire.envelope(signer, Kind.LIVE, 1, 1, "live", "a".toByteArray(), key)
        val b = Wire.envelope(signer, Kind.REGISTER, 2, 2, "register", signer.publicKey + ByteArray(32), null)
        val back = Frames.decode(Frames.encode(listOf(a, b)))
        assertEquals(listOf("live", "register"), back.map { it.name })
        assertArrayEquals(b.payload, back[1].payload)
        assertTrue(runCatching { Frames.decode(Frames.encode(listOf(a)).dropLast(1).toByteArray()) }.exceptionOrNull() is IOException)
    }

    @Test
    fun thePlantIdIsTheKeysHash() {
        assertEquals(22, Wire.plantId(signer.publicKey).length)
        assertEquals(Wire.b64(Wire.sha256(signer.publicKey).copyOf(16)), Wire.plantId(signer.publicKey))
    }

    @Test
    fun aBlockKeepsItsValuesAtTheirResolution() {
        val pv = List(60) { i -> if (i in 10..14) null else 1000.0 + i * 3 }
        val volts = List(60) { 230.04 + it * 0.1 }
        val soc = List(60) { if (it == 0) 50.0 else null }
        val block = Block(1_762_250_400, listOf(Block.Channel("pv", 0, pv), Block.Channel("dev1/l1_voltage", -1, volts), Block.Channel("soc", 0, soc)))
        val back = Block.decode(block.encode())
        assertEquals(1_762_250_400, back.start)
        assertEquals(listOf("pv", "dev1/l1_voltage", "soc"), back.channels.map { it.name })
        assertEquals(pv, back.channels[0].values)
        back.channels[1].values.forEachIndexed { i, v -> assertEquals(Math.round(volts[i] * 10) / 10.0, v!!, 1e-9) }
        assertEquals(50.0, back.channels[2].values[0]!!, 0.0)
        assertNull(back.channels[2].values[1])
        // An hour of smooth values packs into very little.
        assertTrue(Wire.deflate(block.encode()).size < 200)
    }

    @Test
    fun negativeAndLargeChangesSurvive() {
        val grid = listOf(-5000.0, 12000.0, -12000.0, 0.0, -1.0, 1.0) + List(54) { null }
        val back = Block.decode(Block(0, listOf(Block.Channel("grid", 0, grid))).encode())
        assertEquals(grid, back.channels[0].values)
    }

    /**
     * The shared test vectors (`store/tests/vectors.json`): made once by this test with `-Dvectors.write=true`,
     * then checked by every run, by the store's conformance tests and by the web view.
     */
    @Test
    fun theSharedTestVectorsDecode() {
        val file = File("../store/tests/vectors.json")
        if (System.getProperty("vectors.write") == "true" || System.getenv("VECTORS_WRITE") == "true") writeVectors(file)
        if (!file.exists()) return
        val v = Json.parseToJsonElement(file.readText()).jsonObject
        val spki = Wire.unb64(v.getValue("publicKey").jsonPrimitive.content)
        val dataKey = Wire.unb64(v.getValue("dataKey").jsonPrimitive.content)
        assertEquals(v.getValue("plantId").jsonPrimitive.content, Wire.plantId(spki))
        for (item in v.getValue("envelopes").jsonArray) {
            val o = item.jsonObject
            val e = Envelope.decode(Wire.unb64(o.getValue("envelope").jsonPrimitive.content))
            assertTrue(Wire.verify(e, spki))
            assertEquals(o.getValue("name").jsonPrimitive.content, e.name)
            o["content"]?.let { assertEquals(it.jsonPrimitive.content, String(Wire.open(e, dataKey))) }
            o["block"]?.let { assertEquals(it.jsonPrimitive.content, Wire.b64(Wire.open(e, dataKey))) }
        }
    }

    private fun writeVectors(file: File) {
        val s = SoftwareSigner.generate()
        val dataKey = Wire.randomBytes(32)
        val token = Wire.randomBytes(16)
        val id = Wire.plantId(s.publicKey)
        val t = 1_762_250_000_000L
        val block = Block(1_762_250_400, listOf(Block.Channel("pv", 0, List(60) { 1000.0 + it }), Block.Channel("soc", 0, List(60) { if (it < 30) 64.0 else null }))).encode()
        val items = listOf(
            Triple(Wire.envelope(s, Kind.REGISTER, 1, t, "register", s.publicKey + Wire.sha256(token), null), null, null),
            Triple(Wire.envelope(s, Kind.PLANT, 2, t, "plant", """{"name":"Home","devices":[]}""".toByteArray(), dataKey), """{"name":"Home","devices":[]}""", null),
            Triple(Wire.envelope(s, Kind.BLOCK, 3, t, Block.name(1_762_250_400), block, dataKey), null, block),
            Triple(Wire.envelope(s, Kind.LIVE, 4, t, "live", """{"t":$t,"pv":3200}""".toByteArray(), dataKey), """{"t":$t,"pv":3200}""", null),
            Triple(Wire.envelope(s, Kind.DELETE, 5, t, "delete", ByteArray(0), null), null, null),
        )
        val json: JsonObject = buildJsonObject {
            put("note", "Made by WireTest with -Dvectors.write=true; a software key, never a real plant's.")
            put("plantId", id)
            put("publicKey", Wire.b64(s.publicKey))
            put("privateKeyPkcs8", Wire.b64(s.pkcs8))
            put("dataKey", Wire.b64(dataKey))
            put("readToken", Wire.b64(token))
            put("envelopes", buildJsonArray {
                for ((e, content, blk) in items) add(buildJsonObject {
                    put("kind", e.kind.code); put("name", e.name); put("seq", e.seq)
                    put("envelope", Wire.b64(e.encode()))
                    content?.let { put("content", it) }
                    blk?.let { put("block", Wire.b64(it)) }
                })
            })
        }
        file.parentFile.mkdirs()
        file.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), json) + "\n")
    }
}
