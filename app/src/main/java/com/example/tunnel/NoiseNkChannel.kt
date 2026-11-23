package com.example.tunnel

import org.bouncycastle.crypto.digests.Blake2sDigest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.math.ec.rfc7748.X25519
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom

/**
 * Exact Noise_NK_25519_ChaChaPoly_BLAKE2s implementation matching David Fifield's dnstt noise/noise.go.
 *
 * - Protocol Name: "Noise_NK_25519_ChaChaPoly_BLAKE2s"
 * - Prologue:      "dnstt 2020-04-13"
 * - Framing:       2-byte big-endian uint16 length prefix on both handshake and transport messages
 * - AEAD:          RFC 8439 ChaCha20-Poly1305 (12-byte nonce = 4 zero bytes || uint64 LE counter)
 * - Hash / HKDF:   BLAKE2s-256
 * - DH:            X25519 (Curve25519)
 */
object NoisePrimitives {
    const val PROTOCOL_NAME = "Noise_NK_25519_ChaChaPoly_BLAKE2s"
    const val DNSTT_PROLOGUE = "dnstt 2020-04-13"
    const val MAX_TRANSPORT_PLAINTEXT = 4096

    fun parseHexKey32(hex: String): ByteArray {
        val clean = hex.trim().lowercase()
        require(clean.length == 64) { "Expected 64-char hex public key, got ${clean.length}" }
        return ByteArray(32) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    fun blake2sHash(vararg chunks: ByteArray): ByteArray {
        val digest = Blake2sDigest(256)
        for (c in chunks) {
            if (c.isNotEmpty()) digest.update(c, 0, c.size)
        }
        return ByteArray(32).also { digest.doFinal(it, 0) }
    }

    fun hmacBlake2s(key: ByteArray, vararg chunks: ByteArray): ByteArray {
        val hmac = HMac(Blake2sDigest(256))
        hmac.init(KeyParameter(key))
        for (c in chunks) {
            if (c.isNotEmpty()) hmac.update(c, 0, c.size)
        }
        return ByteArray(32).also { hmac.doFinal(it, 0) }
    }

    fun hkdf2(chainingKey: ByteArray, inputKeyMaterial: ByteArray): Pair<ByteArray, ByteArray> {
        val tempKey = hmacBlake2s(chainingKey, inputKeyMaterial)
        val out1 = hmacBlake2s(tempKey, byteArrayOf(0x01))
        val out2 = hmacBlake2s(tempKey, out1, byteArrayOf(0x02))
        return out1 to out2
    }

    fun generateX25519Keypair(random: SecureRandom = SecureRandom()): Pair<ByteArray, ByteArray> {
        val priv = ByteArray(X25519.SCALAR_SIZE)
        X25519.generatePrivateKey(random, priv)
        val pub = ByteArray(X25519.POINT_SIZE)
        X25519.generatePublicKey(priv, 0, pub, 0)
        return priv to pub
    }

    fun x25519SharedSecret(privateKey: ByteArray, peerPublicKey: ByteArray): ByteArray {
        val shared = ByteArray(X25519.POINT_SIZE)
        val ok = X25519.calculateAgreement(privateKey, 0, peerPublicKey, 0, shared, 0)
        if (!ok) throw IOException("X25519 key agreement failed (invalid peer key)")
        return shared
    }

    fun buildNoiseNonce(counter: Long): ByteArray {
        val nonce = ByteArray(12)
        // First 4 bytes are zero, followed by 8-byte little-endian counter
        for (i in 0 until 8) {
            nonce[4 + i] = ((counter ushr (i * 8)) and 0xFFL).toByte()
        }
        return nonce
    }

    fun encryptChaChaPoly(
        key: ByteArray,
        nonceCounter: Long,
        aad: ByteArray,
        plaintext: ByteArray
    ): ByteArray {
        val cipher = ChaCha20Poly1305()
        val params = AEADParameters(KeyParameter(key), 128, buildNoiseNonce(nonceCounter), aad)
        cipher.init(true, params)
        val out = ByteArray(cipher.getOutputSize(plaintext.size))
        var len = cipher.processBytes(plaintext, 0, plaintext.size, out, 0)
        len += cipher.doFinal(out, len)
        return if (len == out.size) out else out.copyOf(len)
    }

    fun decryptChaChaPoly(
        key: ByteArray,
        nonceCounter: Long,
        aad: ByteArray,
        ciphertext: ByteArray
    ): ByteArray {
        val cipher = ChaCha20Poly1305()
        val params = AEADParameters(KeyParameter(key), 128, buildNoiseNonce(nonceCounter), aad)
        cipher.init(false, params)
        val out = ByteArray(cipher.getOutputSize(ciphertext.size))
        var len = cipher.processBytes(ciphertext, 0, ciphertext.size, out, 0)
        try {
            len += cipher.doFinal(out, len)
        } catch (e: Exception) {
            throw IOException("Noise ChaCha20-Poly1305 auth failed: ${e.message}", e)
        }
        return if (len == out.size) out else out.copyOf(len)
    }
}

class NoiseNkChannel private constructor(
    private val rawIn: InputStream,
    private val rawOut: OutputStream,
    private val txKey: ByteArray,
    private val rxKey: ByteArray
) {
    private val txLock = Any()
    private val rxLock = Any()
    private var txNonce = 0L
    private var rxNonce = 0L

    private var rxRemainder: ByteArray = ByteArray(0)
    private var rxOffset: Int = 0

    val inputStream: InputStream = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n <= 0) -1 else (one[0].toInt() and 0xFF)
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len <= 0) return 0
            synchronized(rxLock) {
                while (rxOffset >= rxRemainder.size) {
                    val ct = readFramedMessage(rawIn) ?: return -1
                    if (ct.isEmpty()) continue
                    rxRemainder = NoisePrimitives.decryptChaChaPoly(rxKey, rxNonce++, ByteArray(0), ct)
                    rxOffset = 0
                }
                val copyLen = minOf(len, rxRemainder.size - rxOffset)
                System.arraycopy(rxRemainder, rxOffset, b, off, copyLen)
                rxOffset += copyLen
                return copyLen
            }
        }
    }

    val outputStream: OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len <= 0) return
            synchronized(txLock) {
                var pos = off
                val end = off + len
                while (pos < end) {
                    val chunkLen = minOf(NoisePrimitives.MAX_TRANSPORT_PLAINTEXT, end - pos)
                    val pt = b.copyOfRange(pos, pos + chunkLen)
                    val ct = NoisePrimitives.encryptChaChaPoly(txKey, txNonce++, ByteArray(0), pt)
                    writeFramedMessage(rawOut, ct)
                    pos += chunkLen
                }
            }
        }

        override fun flush() {
            synchronized(txLock) { rawOut.flush() }
        }
    }

    companion object {
        /**
         * Performs the full Noise_NK client handshake over the KCP stream and returns an
         * encrypted full-duplex NoiseNkChannel ready for SMUX multiplexing.
         */
        fun connectClient(
            rawIn: InputStream,
            rawOut: OutputStream,
            serverPubKeyHex: String,
            prologue: ByteArray = NoisePrimitives.DNSTT_PROLOGUE.toByteArray(Charsets.US_ASCII)
        ): NoiseNkChannel {
            val rs = NoisePrimitives.parseHexKey32(serverPubKeyHex)

            // InitializeSymmetric("Noise_NK_25519_ChaChaPoly_BLAKE2s") -> 33 bytes > 32 -> hash
            val protoBytes = NoisePrimitives.PROTOCOL_NAME.toByteArray(Charsets.US_ASCII)
            var h = if (protoBytes.size <= 32) {
                ByteArray(32).also { System.arraycopy(protoBytes, 0, it, 0, protoBytes.size) }
            } else {
                NoisePrimitives.blake2sHash(protoBytes)
            }
            var ck = h.copyOf()

            // MixHash(prologue)
            h = NoisePrimitives.blake2sHash(h, prologue)
            // Pre-message: <- s -> MixHash(rs)
            h = NoisePrimitives.blake2sHash(h, rs)

            // Message 1: -> e, es
            val (ePriv, ePub) = NoisePrimitives.generateX25519Keypair()
            h = NoisePrimitives.blake2sHash(h, ePub)
            val es = NoisePrimitives.x25519SharedSecret(ePriv, rs)
            val (ck1, k1) = NoisePrimitives.hkdf2(ck, es)
            ck = ck1
            val tag1 = NoisePrimitives.encryptChaChaPoly(k1, 0L, h, ByteArray(0))
            h = NoisePrimitives.blake2sHash(h, tag1)

            val msg1 = ByteArray(ePub.size + tag1.size)
            System.arraycopy(ePub, 0, msg1, 0, ePub.size)
            System.arraycopy(tag1, 0, msg1, ePub.size, tag1.size)
            writeFramedMessage(rawOut, msg1)
            rawOut.flush()

            // Message 2: <- e, ee
            val msg2 = readFramedMessage(rawIn)
                ?: throw IOException("EOF waiting for dnstt Noise handshake reply")
            if (msg2.size < 48) {
                throw IOException("Invalid dnstt Noise handshake reply (${msg2.size} bytes)")
            }
            val rePub = msg2.copyOfRange(0, 32)
            val ct2 = msg2.copyOfRange(32, msg2.size)
            h = NoisePrimitives.blake2sHash(h, rePub)
            val ee = NoisePrimitives.x25519SharedSecret(ePriv, rePub)
            val (ck2, k2) = NoisePrimitives.hkdf2(ck, ee)
            ck = ck2
            // Verify server MAC (decrypts empty payload)
            NoisePrimitives.decryptChaChaPoly(k2, 0L, h, ct2)
            h = NoisePrimitives.blake2sHash(h, ct2)

            // Split()
            val (c1, c2) = NoisePrimitives.hkdf2(ck, ByteArray(0))
            return NoiseNkChannel(rawIn, rawOut, txKey = c1, rxKey = c2)
        }

        internal fun writeFramedMessage(out: OutputStream, payload: ByteArray) {
            val len = payload.size
            val frame = ByteArray(2 + len)
            frame[0] = ((len ushr 8) and 0xFF).toByte()
            frame[1] = (len and 0xFF).toByte()
            System.arraycopy(payload, 0, frame, 2, len)
            out.write(frame)
        }

        internal fun readFramedMessage(input: InputStream): ByteArray? {
            val lenBuf = ByteArray(2)
            if (!readExact(input, lenBuf, 2)) return null
            val len = ((lenBuf[0].toInt() and 0xFF) shl 8) or (lenBuf[1].toInt() and 0xFF)
            val data = ByteArray(len)
            if (len > 0 && !readExact(input, data, len)) {
                throw IOException("Truncated Noise frame (expected $len bytes)")
            }
            return data
        }

        private fun readExact(input: InputStream, buf: ByteArray, length: Int): Boolean {
            var offset = 0
            while (offset < length) {
                val n = input.read(buf, offset, length - offset)
                if (n < 0) return false
                offset += n
            }
            return true
        }
    }
}
