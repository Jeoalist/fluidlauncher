package dev.jeo.pencere

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/**
 * The launcher's own ADB client for the phone's adbd, reached over Wireless debugging. Its key
 * pair is made once and kept in the app's private files, so adbd trusts it after one pairing.
 */
class AdbClient private constructor(context: Context) : AbsAdbConnectionManager() {
    private val privateKey: PrivateKey
    private val certificate: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        val dir = File(context.filesDir, "adb").apply { mkdirs() }
        val keyFile = File(dir, "key.pk8")
        val certFile = File(dir, "cert.der")
        if (keyFile.exists() && certFile.exists()) {
            privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            certificate = certFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        } else {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_BITS) }.generateKeyPair()
            val name = X500Name("CN=$DEVICE_NAME")
            val now = Date()
            val holder = JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(now.time), now, Date(now.time + VALIDITY_MS), name, pair.public,
            ).build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private))
            privateKey = pair.private
            certificate = JcaX509CertificateConverter().getCertificate(holder)
            keyFile.writeBytes(privateKey.encoded)
            certFile.writeBytes(certificate.encoded)
        }
    }

    override fun getPrivateKey() = privateKey

    override fun getCertificate() = certificate

    override fun getDeviceName() = DEVICE_NAME

    /** Runs a shell command and returns what it printed; blocks, so call it off the main thread. */
    fun shell(command: String): String {
        val output = java.io.ByteArrayOutputStream()
        openStream("shell:$command").use { stream ->
            val input = stream.openInputStream()
            val buffer = ByteArray(4096)
            try {
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
            } catch (_: java.io.IOException) {
                // The library reports the end of a finished command as a closed stream.
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }

    companion object {
        private const val DEVICE_NAME = "FluidLauncher"
        private const val KEY_BITS = 2048
        private const val VALIDITY_MS = 30L * 365 * 24 * 60 * 60 * 1000

        @Volatile
        private var instance: AdbClient? = null

        /** Making the key the first time takes a moment; call it off the main thread. */
        fun get(context: Context): AdbClient =
            instance ?: synchronized(this) {
                instance ?: AdbClient(context.applicationContext).also { instance = it }
            }
    }
}
