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
 * Thrown by [TofuTrustManager] when the server's leaf isn't trusted: either nothing is pinned yet
 * ([firstUse]) or the pin no longer matches. The handshake aborts, so nothing (password, tokens)
 * reaches the server before the user has accepted [fingerprint].
 */
class UntrustedServerCertificateException(
    val fingerprint: String,
    val firstUse: Boolean,
    message: String,
) : java.security.cert.CertificateException(message)

/**
 * Trust-on-first-use server trust manager.
 *
 * Mumble servers almost always present self-signed certificates, so PKI validation is not
 * meaningful. Instead we pin the leaf certificate's SHA-256 fingerprint per server:
 *
 *  - If [pinnedSha256] is null (first connection), the handshake fails with a first-use
 *    [UntrustedServerCertificateException] so the caller can ask the user, then reconnect with the
 *    accepted fingerprint pinned. [trustUnpinned] skips that (tests against a local server only).
 *  - If [pinnedSha256] is set, the presented leaf must match it, otherwise the handshake fails
 *    (loud warning territory — the server identity changed).
 *
 * The observed leaf is recorded in [observedLeaf] / [observedSha256] either way.
 */
class TofuTrustManager(
    private val pinnedSha256: String? = null,
    private val trustUnpinned: Boolean = false,
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
        if (pin == null && !trustUnpinned) {
            throw UntrustedServerCertificateException(fp, firstUse = true, "Server certificate not pinned yet ($fp)")
        }
        if (pin != null && !pin.equals(fp, ignoreCase = true)) {
            throw UntrustedServerCertificateException(
                fp, firstUse = false, "Server certificate fingerprint mismatch (pinned $pin, got $fp)",
            )
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
