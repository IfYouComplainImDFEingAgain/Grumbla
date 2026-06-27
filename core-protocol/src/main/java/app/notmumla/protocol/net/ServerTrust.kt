package app.notmumla.protocol.net

import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/** Hex SHA-256 / SHA-1 fingerprints of a DER-encoded certificate. */
object CertFingerprint {
    fun sha256(cert: X509Certificate): String = hex("SHA-256", cert.encoded)
    fun sha1(cert: X509Certificate): String = hex("SHA-1", cert.encoded)

    private fun hex(algorithm: String, bytes: ByteArray): String {
        val digest = MessageDigest.getInstance(algorithm).digest(bytes)
        return digest.joinToString(":") { "%02X".format(it) }
    }
}

/**
 * Trust-on-first-use server trust manager.
 *
 * Mumble servers almost always present self-signed certificates, so PKI validation is not
 * meaningful. Instead we pin the leaf certificate's SHA-256 fingerprint per server:
 *
 *  - If [pinnedSha256] is null (first connection), any certificate is accepted and the observed
 *    leaf is recorded in [observedLeaf] / [observedSha256] so the caller can prompt the user and
 *    persist the pin.
 *  - If [pinnedSha256] is set, the presented leaf must match it, otherwise the handshake fails
 *    (loud warning territory — the server identity changed).
 */
class TofuTrustManager(
    private val pinnedSha256: String? = null,
) : X509TrustManager {

    @Volatile var observedLeaf: X509Certificate? = null
        private set
    @Volatile var observedSha256: String? = null
        private set

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw java.security.cert.CertificateException("Empty chain")
        observedLeaf = leaf
        val fp = CertFingerprint.sha256(leaf)
        observedSha256 = fp
        val pin = pinnedSha256
        if (pin != null && !pin.equals(fp, ignoreCase = true)) {
            throw java.security.cert.CertificateException(
                "Server certificate fingerprint mismatch (pinned $pin, got $fp)",
            )
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
