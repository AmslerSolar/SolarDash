// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.remote

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The observer protocol's wire format (docs/design/observer-protocol.md): envelopes, frames, sealing (AES-256-GCM
 * over zlib) and signatures (ECDSA P-256, SHA-256, DER).
 */
enum class Kind(val code: Int) {
    REGISTER(0), BLOCK(1), DAY(2), EVENT(3), LIVE(4), PLANT(5), STORES(6), DELETE(7);

    /** Kinds whose payload is sealed with the data key. */
    val sealed: Boolean get() = this != REGISTER && this != DELETE

    companion object {
        fun of(code: Int): Kind = entries.firstOrNull { it.code == code } ?: throw IOException("Unknown kind $code")
    }
}

class Envelope(
    val kind: Kind,
    val seq: Long,
    val time: Long,
    val name: String,
    val payload: ByteArray,
    val signature: ByteArray,
) {
    /** The header (magic to the end of the name): the additional authenticated data of a sealed payload. */
    val header: ByteArray get() = header(kind, seq, time, name)

    /** Every byte the signature covers. */
    val signed: ByteArray get() = signedPart(kind, seq, time, name, payload)

    fun encode(): ByteArray = signed + ByteBuffer.allocate(2).putShort(signature.size.toShort()).array() + signature

    companion object {
        const val MAX_PAYLOAD = 262_144
        private val MAGIC = "SDO1".toByteArray()
        private val NAME = Regex("[A-Za-z0-9._-]{1,64}")

        fun header(kind: Kind, seq: Long, time: Long, name: String): ByteArray {
            require(NAME.matches(name)) { "Bad name $name" }
            val n = name.toByteArray()
            return ByteBuffer.allocate(22 + n.size).put(MAGIC).put(kind.code.toByte()).putLong(seq).putLong(time)
                .put(n.size.toByte()).put(n).array()
        }

        fun signedPart(kind: Kind, seq: Long, time: Long, name: String, payload: ByteArray): ByteArray {
            require(payload.size <= MAX_PAYLOAD) { "Payload of ${payload.size} bytes" }
            return header(kind, seq, time, name) + ByteBuffer.allocate(4).putInt(payload.size).array() + payload
        }

        fun decode(bytes: ByteArray): Envelope {
            try {
                val b = ByteBuffer.wrap(bytes)
                val magic = ByteArray(4).also { b.get(it) }
                if (!magic.contentEquals(MAGIC)) throw IOException("Not an envelope")
                val kind = Kind.of(b.get().toInt() and 0xFF)
                val seq = b.long
                val time = b.long
                val name = String(ByteArray(b.get().toInt() and 0xFF).also { b.get(it) })
                if (!NAME.matches(name)) throw IOException("Bad name")
                val p = b.int
                if (p < 0 || p > MAX_PAYLOAD) throw IOException("Payload of $p bytes")
                val payload = ByteArray(p).also { b.get(it) }
                val signature = ByteArray(b.short.toInt() and 0xFFFF).also { b.get(it) }
                if (b.hasRemaining()) throw IOException("Trailing bytes")
                return Envelope(kind, seq, time, name, payload, signature)
            } catch (e: java.nio.BufferUnderflowException) {
                throw IOException("Truncated envelope", e)
            }
        }
    }
}

/** Several envelopes in one body: each a 4-byte length, then the envelope. */
object Frames {
    fun encode(envelopes: List<Envelope>): ByteArray {
        val out = ByteArrayOutputStream()
        for (e in envelopes) {
            val bytes = e.encode()
            out.write(ByteBuffer.allocate(4).putInt(bytes.size).array())
            out.write(bytes)
        }
        return out.toByteArray()
    }

    fun decode(body: ByteArray): List<Envelope> {
        val b = ByteBuffer.wrap(body)
        val out = mutableListOf<Envelope>()
        while (b.hasRemaining()) {
            if (b.remaining() < 4) throw IOException("Truncated frame")
            val n = b.int
            if (n < 0 || n > b.remaining()) throw IOException("Truncated frame")
            out += Envelope.decode(ByteArray(n).also { b.get(it) })
        }
        return out
    }
}

/** Signs a plant's envelopes: the collector's key (Android keystore), a software key in tests. */
interface PlantSigner {
    /** SubjectPublicKeyInfo, DER. */
    val publicKey: ByteArray

    fun sign(data: ByteArray): ByteArray
}

object Wire {
    private val random = SecureRandom()

    fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    fun unb64(text: String): ByteArray = Base64.getUrlDecoder().decode(text)

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    /** The plant id: the first 16 bytes of SHA-256(public key), base64url. */
    fun plantId(publicKey: ByteArray): String = b64(sha256(publicKey).copyOf(16))

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    fun publicKey(spki: ByteArray): PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))

    fun verify(e: Envelope, publicKey: ByteArray): Boolean = try {
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey(publicKey))
            update(e.signed)
            verify(e.signature)
        }
    } catch (_: Exception) {
        false
    }

    /** A signed envelope; sealed kinds get [content] zlib-compressed and encrypted with [dataKey]. */
    fun envelope(signer: PlantSigner, kind: Kind, seq: Long, time: Long, name: String, content: ByteArray, dataKey: ByteArray?): Envelope {
        val payload = if (kind.sealed) seal(requireNotNull(dataKey), Envelope.header(kind, seq, time, name), content) else content
        val signature = signer.sign(Envelope.signedPart(kind, seq, time, name, payload))
        return Envelope(kind, seq, time, name, payload, signature)
    }

    /** The content of a sealed envelope; fails when the key or the header doesn't fit. */
    fun open(e: Envelope, dataKey: ByteArray): ByteArray {
        require(e.kind.sealed) { "${e.kind} isn't sealed" }
        if (e.payload.size < 12 + 16) throw IOException("Sealed payload too short")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(dataKey, "AES"), GCMParameterSpec(128, e.payload, 0, 12))
        cipher.updateAAD(e.header)
        val plain = try {
            cipher.doFinal(e.payload, 12, e.payload.size - 12)
        } catch (ex: Exception) {
            throw IOException("Can't decrypt ${e.name}", ex)
        }
        return inflate(plain)
    }

    fun seal(dataKey: ByteArray, header: ByteArray, content: ByteArray, nonce: ByteArray = randomBytes(12)): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(dataKey, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(header)
        return nonce + cipher.doFinal(deflate(content))
    }

    fun deflate(data: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION)
        d.setInput(data)
        d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    /** At most [limit] bytes out, against compression bombs. */
    fun inflate(data: ByteArray, limit: Int = 16 shl 20): ByteArray {
        val i = Inflater()
        i.setInput(data)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!i.finished()) {
            val n = i.inflate(buf)
            if (n == 0 && (i.needsInput() || i.needsDictionary())) throw IOException("Truncated zlib data")
            out.write(buf, 0, n)
            if (out.size() > limit) throw IOException("Content too large")
        }
        i.end()
        return out.toByteArray()
    }
}
