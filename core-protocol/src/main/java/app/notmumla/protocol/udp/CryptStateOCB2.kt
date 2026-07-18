package app.notmumla.protocol.udp

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * OCB-AES128 for the Mumble UDP voice channel — a faithful port of the reference
 * `src/crypto/CryptStateOCB2.cpp`. Encrypted UDP packets are `[iv byte][tag[0..2]] + ciphertext`.
 *
 * The scheme keeps a 16-byte encrypt IV (incremented per packet, low byte first) and a 16-byte
 * decrypt IV that tracks the peer's IV, tolerating a small reorder/loss window and rejecting
 * replays via [decryptHistory]. The block primitive is AES-128 in ECB mode (single 16-byte block).
 *
 * OCB2 has known weaknesses (see the counter-cryptanalysis guards below, from
 * https://eprint.iacr.org/2019/311); it is used only because it is the crypto Mumble servers speak.
 * The wire layout is word-size- and endianness-independent, so the byte-wise GF(2^128) doubling
 * here matches both the 32- and 64-bit reference builds.
 */
class CryptStateOCB2 {

    private val rawKey = ByteArray(BLOCK)
    private val encryptIv = ByteArray(BLOCK)
    private val decryptIv = ByteArray(BLOCK)
    /** For each possible IV[0] value, the IV[1] byte last accepted — used to reject replays. */
    private val decryptHistory = IntArray(0x100)

    @Volatile var initialized = false
        private set

    // Separate AES cipher instances per direction so the (single-threaded) send and receive paths
    // never share a stateful Cipher. ECB has no chaining state, so doFinal() is reusable per block.
    private lateinit var encForEncrypt: Cipher // AES-encrypt in the encrypt path
    private lateinit var encForDecrypt: Cipher // AES-encrypt in the decrypt path (delta/pad/tag)
    private lateinit var decForDecrypt: Cipher // AES-decrypt in the decrypt path (block decode)

    /** Apply a full CryptSetup: raw key, our encrypt IV (client_nonce), our decrypt IV (server_nonce). */
    @Synchronized
    fun setKey(key: ByteArray, encryptIv: ByteArray, decryptIv: ByteArray): Boolean {
        if (key.size != BLOCK || encryptIv.size != BLOCK || decryptIv.size != BLOCK) return false
        key.copyInto(rawKey)
        encryptIv.copyInto(this.encryptIv)
        decryptIv.copyInto(this.decryptIv)
        val spec = SecretKeySpec(rawKey, "AES")
        encForEncrypt = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, spec) }
        encForDecrypt = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, spec) }
        decForDecrypt = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.DECRYPT_MODE, spec) }
        decryptHistory.fill(0)
        initialized = true
        return true
    }

    /** Replace only the decrypt IV — a resync request from the server (CryptSetup with server_nonce only). */
    @Synchronized
    fun setDecryptIv(iv: ByteArray): Boolean {
        if (iv.size != BLOCK || !initialized) return false
        iv.copyInto(decryptIv)
        return true
    }

    /** Our current encrypt IV — echoed back to the server when it requests a resync. */
    @Synchronized
    fun encryptIvCopy(): ByteArray = encryptIv.copyOf()

    /** Encrypt one UDP payload. Returns `[iv byte][tag0..2] + ciphertext`. */
    @Synchronized
    fun encrypt(plain: ByteArray): ByteArray {
        // Increment the encrypt IV (128-bit little-endian counter).
        for (i in 0 until BLOCK) {
            val v = ((encryptIv[i].toInt() and 0xFF) + 1) and 0xFF
            encryptIv[i] = v.toByte()
            if (v != 0) break
        }
        val (cipher, tag) = ocbEncrypt(plain, encryptIv)
        val dst = ByteArray(cipher.size + 4)
        dst[0] = encryptIv[0]
        dst[1] = tag[0]
        dst[2] = tag[1]
        dst[3] = tag[2]
        cipher.copyInto(dst, 4)
        return dst
    }

    /** Decrypt one UDP packet, or null if it fails to authenticate / is a replay / is too short. */
    @Synchronized
    fun decrypt(source: ByteArray): ByteArray? {
        if (source.size < 4 || !initialized) return null

        val saveIv = decryptIv.copyOf()
        val ivbyte = source[0].toInt() and 0xFF
        var restore = false
        val head = decryptIv[0].toInt() and 0xFF

        if (((head + 1) and 0xFF) == ivbyte) {
            // Exactly the next packet, in order.
            if (ivbyte > head) {
                decryptIv[0] = ivbyte.toByte()
            } else {
                decryptIv[0] = ivbyte.toByte()
                incUpper()
            }
        } else {
            // Out of order or a repeat: figure out how far off, within a tolerance window.
            var diff = ivbyte - head
            if (diff > 128) diff -= 256 else if (diff < -128) diff += 256

            when {
                ivbyte < head && diff > -30 && diff < 0 -> { // late, no wraparound
                    decryptIv[0] = ivbyte.toByte()
                    restore = true
                }
                ivbyte > head && diff > -30 && diff < 0 -> { // late, wrapped around
                    decryptIv[0] = ivbyte.toByte()
                    decUpper()
                    restore = true
                }
                ivbyte > head && diff > 0 -> { // lost a few, still ahead
                    decryptIv[0] = ivbyte.toByte()
                }
                ivbyte < head && diff > 0 -> { // lost a few, wrapped around
                    decryptIv[0] = ivbyte.toByte()
                    incUpper()
                }
                else -> return null
            }
            if (decryptHistory[decryptIv[0].toInt() and 0xFF] == (decryptIv[1].toInt() and 0xFF)) {
                saveIv.copyInto(decryptIv)
                return null
            }
        }

        val body = source.copyOfRange(4, source.size)
        val result = ocbDecrypt(body, decryptIv)
        if (result == null || !tagMatches(result.second, source)) {
            saveIv.copyInto(decryptIv)
            return null
        }
        decryptHistory[decryptIv[0].toInt() and 0xFF] = decryptIv[1].toInt() and 0xFF
        if (restore) saveIv.copyInto(decryptIv)
        return result.first
    }

    /** Increment the decrypt IV above byte 0 (carry propagation). */
    private fun incUpper() {
        for (i in 1 until BLOCK) {
            val v = ((decryptIv[i].toInt() and 0xFF) + 1) and 0xFF
            decryptIv[i] = v.toByte()
            if (v != 0) break
        }
    }

    /** Decrement the decrypt IV above byte 0 (borrow propagation). */
    private fun decUpper() {
        for (i in 1 until BLOCK) {
            val was = decryptIv[i].toInt() and 0xFF
            decryptIv[i] = (was - 1).toByte()
            if (was != 0) break
        }
    }

    private fun tagMatches(tag: ByteArray, source: ByteArray): Boolean =
        tag[0] == source[1] && tag[1] == source[2] && tag[2] == source[3]

    // --- OCB core ---------------------------------------------------------------------------------

    private fun ocbEncrypt(plain: ByteArray, nonce: ByteArray): Pair<ByteArray, ByteArray> {
        val encrypted = ByteArray(plain.size)
        var delta = aes(encForEncrypt, nonce)
        var checksum = ByteArray(BLOCK)
        var pos = 0
        var len = plain.size

        while (len > BLOCK) {
            // Counter-cryptanalysis (section 9 of eprint 2019/311): if the second-to-last block is
            // all-zero except its last byte — which digital silence produces in bulk — flip a bit so
            // the packet can't be used to forge a tag. The flip is inaudible after decoding.
            var flip = false
            if (len - BLOCK <= BLOCK) {
                var sum = 0
                for (i in 0 until BLOCK - 1) sum = sum or (plain[pos + i].toInt() and 0xFF)
                if (sum == 0) flip = true
            }

            delta = times2(delta)
            val block = plain.copyOfRange(pos, pos + BLOCK)
            var tmp = xor(delta, block)
            if (flip) tmp[0] = (tmp[0].toInt() xor 1).toByte()
            tmp = aes(encForEncrypt, tmp)
            xor(delta, tmp).copyInto(encrypted, pos)
            checksum = xor(checksum, block)
            if (flip) checksum[0] = (checksum[0].toInt() xor 1).toByte()

            len -= BLOCK
            pos += BLOCK
        }

        delta = times2(delta)
        val lenBlock = ByteArray(BLOCK)
        val bits = len * 8
        lenBlock[BLOCK - 1] = bits.toByte()
        lenBlock[BLOCK - 2] = (bits ushr 8).toByte()
        val pad = aes(encForEncrypt, xor(lenBlock, delta))
        val tail = ByteArray(BLOCK)
        System.arraycopy(plain, pos, tail, 0, len)
        System.arraycopy(pad, len, tail, len, BLOCK - len)
        checksum = xor(checksum, tail)
        val encFinal = xor(pad, tail)
        System.arraycopy(encFinal, 0, encrypted, pos, len)

        delta = xor(delta, times2(delta)) // S3
        val tag = aes(encForEncrypt, xor(delta, checksum))
        return encrypted to tag
    }

    private fun ocbDecrypt(cipher: ByteArray, nonce: ByteArray): Pair<ByteArray, ByteArray>? {
        val plain = ByteArray(cipher.size)
        var delta = aes(encForDecrypt, nonce)
        var checksum = ByteArray(BLOCK)
        var pos = 0
        var len = cipher.size

        while (len > BLOCK) {
            delta = times2(delta)
            val block = cipher.copyOfRange(pos, pos + BLOCK)
            val tmp = aesDec(xor(delta, block))
            val p = xor(delta, tmp)
            p.copyInto(plain, pos)
            checksum = xor(checksum, p)
            len -= BLOCK
            pos += BLOCK
        }

        delta = times2(delta)
        val lenBlock = ByteArray(BLOCK)
        val bits = len * 8
        lenBlock[BLOCK - 1] = bits.toByte()
        lenBlock[BLOCK - 2] = (bits ushr 8).toByte()
        val pad = aes(encForDecrypt, xor(lenBlock, delta))
        val tail = ByteArray(BLOCK)
        System.arraycopy(cipher, pos, tail, 0, len)
        val tmp = xor(tail, pad)
        checksum = xor(checksum, tmp)
        System.arraycopy(tmp, 0, plain, pos, len)

        // Counter-cryptanalysis: reject a forged final block whose decrypt equals delta.
        var forged = true
        for (i in 0 until BLOCK - 1) if (tmp[i] != delta[i]) { forged = false; break }
        if (forged) return null

        delta = xor(delta, times2(delta)) // S3
        val tag = aes(encForDecrypt, xor(delta, checksum))
        return plain to tag
    }

    private fun aes(cipher: Cipher, block: ByteArray): ByteArray = cipher.doFinal(block)
    private fun aesDec(block: ByteArray): ByteArray = decForDecrypt.doFinal(block)

    /** XOR two 16-byte blocks. */
    private fun xor(a: ByteArray, b: ByteArray): ByteArray {
        val o = ByteArray(BLOCK)
        for (i in 0 until BLOCK) o[i] = (a[i].toInt() xor b[i].toInt()).toByte()
        return o
    }

    /**
     * GF(2^128) doubling: shift the block left by one bit (big-endian) and, if the top bit was set,
     * XOR the reduction polynomial 0x87 into the low byte. Word-size independent by construction.
     */
    private fun times2(b: ByteArray): ByteArray {
        val carry = (b[0].toInt() ushr 7) and 0x1
        val o = ByteArray(BLOCK)
        for (i in 0 until BLOCK - 1) {
            o[i] = (((b[i].toInt() shl 1) or ((b[i + 1].toInt() ushr 7) and 0x1)) and 0xFF).toByte()
        }
        o[BLOCK - 1] = (((b[BLOCK - 1].toInt() shl 1) and 0xFF) xor (carry * 0x87)).toByte()
        return o
    }

    companion object {
        const val BLOCK = 16
    }
}
