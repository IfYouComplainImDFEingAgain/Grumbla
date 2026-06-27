package app.notmumla.protocol.identity

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date

/**
 * Generation and (de)serialization of the Mumble self-signed identity certificate.
 *
 * Mirrors the reference client (src/SelfSignedCertificate.cpp): RSA-2048, self-signed X.509v3,
 * clientAuth EKU, BasicConstraints CA:false, ~20-year validity. The reference signs with SHA-1;
 * we modernize to SHA-256. The cert is sent automatically as the TLS client certificate, and the
 * server identifies the user by its SHA-1 fingerprint (UserState.hash).
 */
object IdentityCertificate {

    const val KEYSTORE_ALIAS = "identity"
    private const val KEY_SIZE = 2048
    private val VALIDITY_MS = 60L * 60 * 24 * 365 * 20 * 1000 // 20 years

    /**
     * Our own BouncyCastle provider *instance*. We intentionally reference it by instance rather
     * than by name: Android already registers a stripped-down provider named "BC", so resolving
     * algorithms by the "BC" name hits the system provider (which cannot sign SHA256withRSA).
     */
    private val bc: BouncyCastleProvider = BouncyCastleProvider()

    /**
     * Generate a fresh identity. [commonName] becomes the certificate CN (typically the username),
     * [email] is added as a SubjectAltName when non-blank.
     */
    fun generate(commonName: String, email: String? = null): Identity {
        val keyGen = KeyPairGenerator.getInstance("RSA", bc)
        keyGen.initialize(KEY_SIZE)
        val keyPair: KeyPair = keyGen.generateKeyPair()

        val now = System.currentTimeMillis()
        val notBefore = Date(now - 24L * 60 * 60 * 1000) // tolerate small clock skew
        val notAfter = Date(now + VALIDITY_MS)
        val subject = X500Name("CN=${escape(commonName.ifBlank { "Mumble User" })}")
        val serial = BigInteger.valueOf(now)

        val extUtils = JcaX509ExtensionUtils()
        val builder = JcaX509v3CertificateBuilder(
            /* issuer = */ subject, // self-signed: issuer == subject
            serial, notBefore, notAfter, subject, keyPair.public,
        ).apply {
            addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            addExtension(
                Extension.keyUsage, true,
                KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment),
            )
            addExtension(
                Extension.extendedKeyUsage, false,
                ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth),
            )
            addExtension(
                Extension.subjectKeyIdentifier, false,
                extUtils.createSubjectKeyIdentifier(keyPair.public),
            )
            if (!email.isNullOrBlank()) {
                addExtension(
                    Extension.subjectAlternativeName, false,
                    GeneralNames(GeneralName(GeneralName.rfc822Name, email)),
                )
            }
        }

        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(bc)
            .build(keyPair.private)
        val cert = JcaX509CertificateConverter()
            .setProvider(bc)
            .getCertificate(builder.build(signer))
        cert.verify(keyPair.public)

        return Identity(cert, keyPair.private)
    }

    /** Serialize an identity to a password-protected PKCS#12 blob for at-rest storage. */
    fun toPkcs12(identity: Identity, password: CharArray): ByteArray {
        val store = KeyStore.getInstance("PKCS12")
        store.load(null, null)
        store.setKeyEntry(
            KEYSTORE_ALIAS, identity.privateKey, password, arrayOf(identity.certificate),
        )
        return ByteArrayOutputStream().use { out ->
            store.store(out, password)
            out.toByteArray()
        }
    }

    /** Load an identity previously written with [toPkcs12]. */
    fun fromPkcs12(bytes: ByteArray, password: CharArray): Identity {
        val store = KeyStore.getInstance("PKCS12")
        ByteArrayInputStream(bytes).use { store.load(it, password) }
        val alias = store.aliases().asSequence().firstOrNull { store.isKeyEntry(it) }
            ?: error("PKCS#12 contains no private-key entry")
        val key = store.getKey(alias, password) as PrivateKey
        val cert = store.getCertificate(alias) as X509Certificate
        return Identity(cert, key)
    }

    /**
     * Build an in-memory [KeyStore] suitable for a TLS KeyManager, containing this identity under
     * [KEYSTORE_ALIAS] guarded by [password] (an ephemeral in-process password is fine).
     */
    fun toKeyStore(identity: Identity, password: CharArray): KeyStore {
        val store = KeyStore.getInstance("PKCS12")
        store.load(null, null)
        store.setKeyEntry(
            KEYSTORE_ALIAS, identity.privateKey, password, arrayOf(identity.certificate),
        )
        return store
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace(",", "\\,").replace("+", "\\+")
            .replace("\"", "\\\"").replace("<", "\\<").replace(">", "\\>").replace(";", "\\;")
}

/** A loaded identity: the X.509 certificate plus its private key. */
data class Identity(
    val certificate: X509Certificate,
    val privateKey: PrivateKey,
)
