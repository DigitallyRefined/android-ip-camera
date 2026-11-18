package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureStorage(context: Context) {

    private val encryptedPrefs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        createEncryptedPreferences(context)
    } else {
        // Fallback for older Android versions
        context.getSharedPreferences("secure_prefs", Context.MODE_PRIVATE)
    }

    companion object {
        private const val TAG = "SecureStorage"
        private val initializationLock = Any()

        // Keys for sensitive data
        const val KEY_USERNAME = "secure_username"
        const val KEY_PASSWORD = "secure_password"
        const val KEY_CERT_PASSWORD = "secure_cert_password"
        const val KEY_JWT_SECRET = "secure_jwt_secret"
    }

    private fun createEncryptedPreferences(context: Context) = synchronized(initializationLock) {
        try {
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
        } catch (e: Exception) {
            // Handle corrupted encrypted preferences from previous installations
            Log.w(TAG, "Failed to open encrypted preferences, attempting to clear corrupted data", e)

            // Delete the corrupted encrypted preferences file
            val prefsFile = File(context.filesDir.parent, "shared_prefs/secure_prefs.xml")
            if (prefsFile.exists()) {
                prefsFile.delete()
                Log.i(TAG, "Deleted corrupted encrypted preferences file")
            }

            // Delete the master key if it exists
            try {
                val keyStore = KeyStore.getInstance("AndroidKeyStore")
                keyStore.load(null)
                keyStore.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                Log.i(TAG, "Deleted master key")
            } catch (keyDeleteException: Exception) {
                Log.w(TAG, "Could not delete master key", keyDeleteException)
            }

            // Retry creating encrypted preferences with fresh data after a delay
            // Try multiple times with increasing delays to handle Keystore initialization
            var lastException: Exception? = null
            val delays = listOf(100L, 200L, 300L) // Increasing delays: 100ms, 200ms, 300ms

            for (delay in delays) {
                try {
                    Thread.sleep(delay)
                    val masterKey = MasterKey.Builder(context)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build()

                    return EncryptedSharedPreferences.create(
                        context,
                        "secure_prefs",
                        masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                    )
                } catch (e: Exception) {
                    lastException = e
                    Log.w(TAG, "Retry attempt failed after ${delay}ms delay", e)
                }
            }
            
            // All retries failed, fall back to regular SharedPreferences
            Log.e(TAG, "Failed to create encrypted preferences after multiple retries, falling back to regular SharedPreferences", lastException)
            context.getSharedPreferences("secure_prefs", Context.MODE_PRIVATE)
        }
    }

    // Store sensitive data securely
    fun putSecureString(key: String, value: String?): Boolean {
        // Use commit() to ensure synchronous write for critical data like certificate passwords
        return encryptedPrefs.edit().putString(key, value).commit()
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
}
