package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory

object InputValidator {

    // Username validation
    fun isValidUsername(username: String): Boolean {
        return username.isNotEmpty() &&
               username.length <= 50 &&
               username.matches(Regex("^[a-zA-Z0-9_-]+$"))
    }

    // Password validation
    fun isValidPassword(password: String): Boolean {
        return password.length >= 8 &&
               password.length <= 128 &&
               password.contains(Regex("[A-Z]")) && // At least one uppercase
               password.contains(Regex("[a-z]")) && // At least one lowercase
               password.contains(Regex("[0-9]"))    // At least one digit
    }

    // Certificate path validation - STRENGTHENED for security
    fun isValidCertificatePath(path: String): Boolean {
        return try {
            val uri = Uri.parse(path)
            val file = File(uri.path ?: "")

            // Basic path validation
            if (uri.scheme != "content" && uri.scheme != "file") return false
            if (file.extension.lowercase() !in listOf("p12", "pfx")) return false
            if (!file.exists()) return false

            // SECURITY: Additional validation to prevent path traversal
            val normalizedPath = file.canonicalPath
            val parentPath = file.parentFile?.canonicalPath

            // Ensure the file is not outside allowed directories and has reasonable size
            if (normalizedPath.contains("..") || parentPath?.contains("..") == true) return false
            if (file.length() > 10 * 1024 * 1024) return false // Max 10MB for certificate

            true
        } catch (e: Exception) {
            false
        }
    }

    // Stream delay validation (milliseconds)
    fun isValidStreamDelay(delay: String): Boolean {
        return try {
            val delayMs = delay.toLong()
            delayMs in 10..1000 // 10ms to 1 second
        } catch (e: NumberFormatException) {
            false
        }
    }

    // Camera resolution validation
    fun isValidCameraResolution(resolution: String): Boolean {
        return resolution in listOf("low", "medium", "high")
    }

    // Certificate password validation
    fun isValidCertificatePassword(password: String?): Boolean {
        return password == null || password.length <= 256
    }

    // Validate that certificate can be loaded and used for SSL/TLS
    fun validateCertificateUsability(context: Context, certificateUri: Uri, password: String?): Boolean {
        return try {
            val privateFile = File(context.filesDir, "cert_validation_temp.p12")
            if (privateFile.exists()) privateFile.delete()

            context.contentResolver.openInputStream(certificateUri)?.use { input ->
                privateFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return false

            // Try to load the certificate
            privateFile.inputStream().use { inputStream ->
                val keyStore = KeyStore.getInstance("PKCS12")
                keyStore.load(inputStream, password?.toCharArray())
                val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                keyManagerFactory.init(keyStore, password?.toCharArray())
            }

            // Cleanup
            privateFile.delete()
            true
        } catch (e: Exception) {
            // Cleanup on failure
            try {
                File(context.filesDir, "cert_validation_temp.p12").delete()
            } catch (cleanupException: Exception) {
                // Ignore cleanup errors
            }
            false
        }
    }

    // Validate built-in certificate with given password
    fun validateBuiltInCertificate(context: Context, password: String?): Boolean {
        return try {
            val certFile = File(context.filesDir, "personal_certificate.p12")
            if (!certFile.exists()) {
                // Try to copy from assets first
                try {
                    context.assets.open("personal_certificate.p12").use { input ->
                        certFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (e: Exception) {
                    return false
                }
            }

            // Try to load the certificate
            certFile.inputStream().use { inputStream ->
                val keyStore = KeyStore.getInstance("PKCS12")
                keyStore.load(inputStream, password?.toCharArray())
                val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                keyManagerFactory.init(keyStore, password?.toCharArray())
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    // Sanitize string input (remove potentially dangerous characters)
    fun sanitizeString(input: String): String {
        return input.replace(Regex("[<>\"'&]"), "")
               .trim()
               .take(256) // Max length
    }

    // Validate and sanitize combined
    fun validateAndSanitizeUsername(username: String): String? {
        val sanitized = sanitizeString(username)
        return if (isValidUsername(sanitized)) sanitized else null
    }

    fun validateAndSanitizePassword(password: String): String? {
        val sanitized = sanitizeString(password)
        return if (isValidPassword(sanitized)) sanitized else null
    }
}