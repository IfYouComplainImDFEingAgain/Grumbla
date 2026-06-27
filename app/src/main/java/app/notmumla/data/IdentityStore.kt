package app.notmumla.data

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import app.notmumla.protocol.identity.Identity
import app.notmumla.protocol.identity.IdentityCertificate
import app.notmumla.protocol.net.CertFingerprint
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the user's Mumble identity certificate (a PKCS#12 blob) at rest, encrypted via
 * Jetpack Security's [EncryptedFile] (key material held in the Android Keystore). The certificate
 * is generated lazily on first access.
 *
 * The PKCS#12 password is a fixed in-process constant; confidentiality at rest comes from the
 * EncryptedFile wrapper, not the PKCS#12 password.
 */
@Singleton
class IdentityStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val pkcsPassword = "not-mumla".toCharArray()
    private val file: File get() = File(context.filesDir, FILE_NAME)

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    @Volatile private var cached: Identity? = null

    /** The current identity, generating and persisting one on first use. */
    @Synchronized
    fun getOrCreate(commonName: String = "Mumble User"): Identity {
        cached?.let { return it }
        val loaded = if (file.exists()) runCatching { readIdentity() }.getOrNull() else null
        val identity = loaded ?: IdentityCertificate.generate(commonName).also { writeIdentity(it) }
        cached = identity
        return identity
    }

    /** Replace the stored identity (e.g. user regenerates or imports one). */
    @Synchronized
    fun replace(identity: Identity) {
        writeIdentity(identity)
        cached = identity
    }

    /** Import an identity from an external PKCS#12 blob. */
    @Synchronized
    fun importPkcs12(bytes: ByteArray, password: CharArray): Identity {
        val identity = IdentityCertificate.fromPkcs12(bytes, password)
        replace(identity)
        return identity
    }

    /** Export the current identity as a password-protected PKCS#12 blob. */
    fun exportPkcs12(password: CharArray): ByteArray =
        IdentityCertificate.toPkcs12(getOrCreate(), password)

    fun fingerprintSha256(): String = CertFingerprint.sha256(getOrCreate().certificate)

    private fun encryptedFile(): EncryptedFile =
        EncryptedFile.Builder(
            context, file, masterKey, EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
        ).build()

    private fun writeIdentity(identity: Identity) {
        if (file.exists()) file.delete()
        val bytes = IdentityCertificate.toPkcs12(identity, pkcsPassword)
        encryptedFile().openFileOutput().use { it.write(bytes) }
    }

    private fun readIdentity(): Identity {
        val bytes = encryptedFile().openFileInput().use { it.readBytes() }
        return IdentityCertificate.fromPkcs12(bytes, pkcsPassword)
    }

    private companion object {
        const val FILE_NAME = "identity.p12.enc"
    }
}
