package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureStorage(context: Context) {

    private val encryptedPrefs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        // Use AndroidX Security library for modern encryption
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            "secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } else {
        // Fallback for older Android versions
        context.getSharedPreferences("secure_prefs", Context.MODE_PRIVATE)
    }

    // Store sensitive data securely
    fun putSecureString(key: String, value: String?) {
        encryptedPrefs.edit().putString(key, value).apply()
    }

    // Retrieve sensitive data securely
    fun getSecureString(key: String, defaultValue: String? = null): String? {
        val stored = encryptedPrefs.getString(key, null)
        if (stored != null && stored.isNotEmpty()) return stored

        // No defaults - force user configuration for security
        return defaultValue
    }

    // Remove sensitive data
    fun removeSecureString(key: String) {
        encryptedPrefs.edit().remove(key).apply()
    }

    // Check if secure storage contains a key
    fun containsSecureString(key: String): Boolean {
        return encryptedPrefs.contains(key)
    }

    companion object {
        // Keys for sensitive data
        const val KEY_USERNAME = "secure_username"
        const val KEY_PASSWORD = "secure_password"
        const val KEY_CERT_PASSWORD = "secure_cert_password"
        const val KEY_JWT_SECRET = "secure_jwt_secret"
    }
}