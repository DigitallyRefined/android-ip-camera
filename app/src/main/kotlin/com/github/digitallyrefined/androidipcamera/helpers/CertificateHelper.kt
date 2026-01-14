package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import android.util.Log
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.ContentSigner
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date

object CertificateHelper {
    private const val TAG = "CertificateHelper"
    private const val CERT_ALIAS = "personal_certificate"
    private const val CERT_VALIDITY_DAYS = 3650L // 10 years
    private const val KEY_SIZE = 2048

    init {
        // Register BouncyCastle provider if not already registered
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    /**
     * Generates a self-signed certificate with the given password
     * @param password The password to protect the certificate
     * @return The generated certificate file, or null if generation failed
     */
    fun generateCertificate(context: Context, password: String): File? {
        return try {
            // Generate key pair - use default provider (more reliable on Android)
            val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
            keyPairGenerator.initialize(KEY_SIZE, SecureRandom())
            val keyPair: KeyPair = keyPairGenerator.generateKeyPair()

            // Create certificate
            val cert = createSelfSignedCertificate(keyPair)

            // Create PKCS12 keystore - use default provider
            val keyStore = KeyStore.getInstance("PKCS12")
            keyStore.load(null, null)
            keyStore.setKeyEntry(
                CERT_ALIAS,
                keyPair.private,
                password.toCharArray(),
                arrayOf(cert)
            )

            // Save to file
            val certFile = File(context.filesDir, "personal_certificate.p12")
            certFile.outputStream().use { output ->
                keyStore.store(output, password.toCharArray())
            }

            Log.i(TAG, "Certificate generated successfully at ${certFile.absolutePath}")
            certFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate certificate: ${e.message}", e)
            null
        }
    }

    /**
     * Creates a self-signed X.509 certificate using BouncyCastle
     */
    private fun createSelfSignedCertificate(keyPair: KeyPair): X509Certificate {
        val now = Date()
        val validityEnd = Date(now.time + CERT_VALIDITY_DAYS * 24 * 60 * 60 * 1000L)

        // Create X500 name for subject and issuer (self-signed)
        val subject = X500Name("CN=localhost, O=Personal IP Camera, OU=Home, L=Home, ST=Personal, C=US")
        
        // Generate serial number
        val serialNumber = BigInteger(160, SecureRandom())

        // Build certificate
        val certBuilder = JcaX509v3CertificateBuilder(
            subject,
            serialNumber,
            now,
            validityEnd,
            subject,
            keyPair.public
        )

        // Sign certificate - use default provider for signing (more reliable on Android)
        val contentSigner: ContentSigner = JcaContentSignerBuilder("SHA256withRSA")
            .build(keyPair.private)

        val certHolder: X509CertificateHolder = certBuilder.build(contentSigner)
        val certConverter = JcaX509CertificateConverter()
        
        return certConverter.getCertificate(certHolder)
    }

    /**
     * Checks if a certificate already exists
     */
    fun certificateExists(context: Context): Boolean {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val certificatePath = prefs.getString("certificate_path", null)
        
        // If custom certificate path is set, certificate exists
        if (certificatePath != null) {
            return true
        }

        // Check if personal certificate exists in files directory
        val personalCertFile = File(context.filesDir, "personal_certificate.p12")
        if (personalCertFile.exists()) {
            return true
        }

        // Check if certificate exists in assets
        return try {
            context.assets.open("personal_certificate.p12").use { }
            true
        } catch (e: Exception) {
            false
        }
    }
}
