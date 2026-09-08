/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Native crypto bridge for the mtcute (MTProto) host that runs inside QuickJS.
 * Implements mtcute's ICryptoProvider on top of javax.crypto / java.security:
 * MTProto packet crypto (AES-CTR + AES-IGE), digests, HMAC, PBKDF2 (2FA SRP),
 * gzip, randomness and PQ factorization for the DH handshake.
 *
 * Every function here is called synchronously from the single QuickJS thread
 * and must never block on IO — all operations are CPU-only and fast.
 */

package moe.rukamori.archivetune.telegram

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

internal object TgJsCrypto {
    private val random = SecureRandom()

    private val ctrHandles = HashMap<Int, Cipher>()
    private var nextCtrHandle = 1

    @Synchronized
    fun clear() {
        ctrHandles.clear()
        nextCtrHandle = 1
    }

    fun sha1(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(data)

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun hmacSha256(data: ByteArray, key: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun pbkdf2(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        keylen: Int,
        algo: String,
    ): ByteArray {
        val algorithm =
            when (algo.lowercase()) {
                "sha512" -> "PBKDF2WithHmacSHA512"
                "sha256" -> "PBKDF2WithHmacSHA256"
                else -> "PBKDF2WithHmacSHA1"
            }
        val factory = javax.crypto.SecretKeyFactory.getInstance(algorithm)
        val chars = CharArray(password.size) { (password[it].toInt() and 0xff).toChar() }
        val spec = PBEKeySpec(chars, salt, iterations, keylen)
        val key = factory.generateSecret(spec)
        val bytes = key.encoded
        spec.clearPassword()
        return bytes
    }

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    fun aesIge(
        encrypt: Boolean,
        key: ByteArray,
        iv: ByteArray,
        data: ByteArray,
    ): ByteArray {
        if (data.isEmpty()) return data
        require(data.size % 16 == 0) { "AES-IGE input must be a multiple of 16 bytes" }
        require(iv.size == 32) { "AES-IGE iv must be 32 bytes" }

        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))

        var ivX = iv.copyOfRange(0, 16)
        var ivY = iv.copyOfRange(16, 32)
        val out = ByteArray(data.size)
        var offset = 0
        while (offset < data.size) {
            val block = data.copyOfRange(offset, offset + 16)
            val xored = ByteArray(16)
            for (i in 0 until 16) {
                xored[i] = (block[i].toInt() xor ivX[i].toInt()).toByte()
            }
            val encrypted = cipher.doFinal(xored)
            for (i in 0 until 16) {
                out[offset + i] = (encrypted[i].toInt() xor ivY[i].toInt()).toByte()
            }
            ivX = encrypted.copyOf()
            ivY = block.copyOf()
            offset += 16
        }
        return out
    }

    @Synchronized
    fun aesCtrOpen(
        key: ByteArray,
        iv: ByteArray,
        encrypt: Boolean,
    ): Int {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val handle = nextCtrHandle++
        ctrHandles[handle] = cipher
        return handle
    }

    @Synchronized
    fun aesCtrProcess(
        handle: Int,
        data: ByteArray,
    ): ByteArray {
        val cipher = ctrHandles[handle] ?: return data
        return cipher.update(data)
    }

    @Synchronized
    fun aesCtrClose(handle: Int) {
        ctrHandles.remove(handle)
    }

    fun gzip(data: ByteArray, maxSize: Int): ByteArray? {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, false)
        deflater.setInput(data)
        deflater.finish()
        val buffer = ByteArray(4096)
        val out = ByteArrayOutputStream()
        while (!deflater.finished()) {
            val n = deflater.deflate(buffer)
            if (n > 0) out.write(buffer, 0, n)
            if (out.size() > maxSize) {
                deflater.end()
                return null
            }
        }
        deflater.end()
        return out.toByteArray()
    }

    fun gunzip(data: ByteArray): ByteArray {
        GZIPInputStream(ByteArrayInputStream(data)).use { input ->
            return input.readBytes()
        }
    }

    /**
     * Factorizes the 64-bit unsigned PQ from the DH handshake into two 32-bit primes.
     * Mirrors mtcute's pure-JS fallback (trial division + Pollard's rho) but runs on
     * BigInteger, which is faster than anything QuickJS can do with BigInt.
     */
    fun factorizePq(pq: ByteArray): Pair<ByteArray, ByteArray> {
        val value = BigInteger(1, pq)
        var factor = trialDivide(value)
        if (factor == null) {
            factor = pollardRho(value)
        }
        if (factor == null || factor <= BigInteger.ZERO) {
            // extremely unlikely fallback — treat as 1 x pq
            return trimTo4(BigInteger.ONE) to trimTo4(value)
        }
        val other = value.divide(factor)
        val small = if (factor.compareTo(other) <= 0) factor else other
        val large = if (factor.compareTo(other) <= 0) other else factor
        return trimTo4(small) to trimTo4(large)
    }

    private fun trialDivide(value: BigInteger): BigInteger? {
        for (i in 2..65535L) {
            val big = BigInteger.valueOf(i)
            if (big.multiply(big).compareTo(value) > 0) break
            if (value.mod(big) == BigInteger.ZERO) return big
        }
        return null
    }

    private fun pollardRho(value: BigInteger): BigInteger? {
        if (value.mod(BigInteger.TWO) == BigInteger.ZERO) return BigInteger.TWO
        var attempts = 0
        var c = BigInteger.ONE
        while (attempts < 32) {
            var x = BigInteger.TWO
            var y = BigInteger.TWO
            var d = BigInteger.ONE
            while (d == BigInteger.ONE) {
                x = pollardStep(x, c, value)
                y = pollardStep(pollardStep(y, c, value), c, value)
                d = x.subtract(y).abs().gcd(value)
            }
            if (d != value) return d
            c = c.add(BigInteger.ONE)
            attempts++
        }
        return null
    }

    private fun pollardStep(
        x: BigInteger,
        c: BigInteger,
        n: BigInteger,
    ): BigInteger = x.multiply(x).add(c).mod(n)

    private fun trimTo4(value: BigInteger): ByteArray {
        val bytes = value.toByteArray() // may include a leading sign byte
        val unsigned = if (bytes.size > 4 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
        return if (unsigned.size >= 4) {
            unsigned.copyOfRange(unsigned.size - 4, unsigned.size)
        } else {
            ByteArray(4).also { unsigned.copyInto(it, 4 - unsigned.size) }
        }
    }
}
