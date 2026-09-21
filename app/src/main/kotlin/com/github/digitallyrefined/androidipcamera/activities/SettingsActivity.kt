package com.github.digitallyrefined.androidipcamera.activities

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.github.digitallyrefined.androidipcamera.R
import com.github.digitallyrefined.androidipcamera.StreamingService
import com.github.digitallyrefined.androidipcamera.helpers.InputValidator
import com.github.digitallyrefined.androidipcamera.helpers.RecordingsHelper
import com.github.digitallyrefined.androidipcamera.helpers.SecureStorage

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportFragmentManager
            .beginTransaction()
            .replace(android.R.id.content, SettingsFragment())
            .commit()
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        companion object {
            private const val PICK_CERTIFICATE_FILE = 1
            private const val PICK_RECORDING_FOLDER = 2
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            // Set up certificate selection preference
            findPreference<Preference>("certificate_path")?.apply {
                setOnPreferenceClickListener {
                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "*/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }
                    startActivityForResult(
                        Intent.createChooser(intent, "Select TLS Certificate"),
                        PICK_CERTIFICATE_FILE
                    )
                    true
                }

                setOnPreferenceChangeListener { _, _ ->
                    // Restart server when certificate path changes
                    restartStreamingServer()
                    true
                }
            }

            val secureStorage = SecureStorage(requireContext())

            // Configure authentication enable/disable checkbox
            findPreference<androidx.preference.CheckBoxPreference>("enable_auth")?.apply {
                // Initialize visibility based on current value
                val enabled = isChecked
                findPreference<EditTextPreference>("username")?.isVisible = enabled
                findPreference<EditTextPreference>("password")?.isVisible = enabled

                setOnPreferenceChangeListener { _, newValue ->
                    val enabled = newValue as Boolean
                    // Show/hide username and password preferences
                    findPreference<EditTextPreference>("username")?.isVisible = enabled
                    findPreference<EditTextPreference>("password")?.isVisible = enabled
                    // Restart server when authentication setting changes
                    restartStreamingServer()
                    true
                }
            }

            // Configure TLS version preference to hide/show certificate options
            findPreference<androidx.preference.ListPreference>("tls_version")?.apply {
                // Initialize visibility based on current value
                val tlsEnabled = value != "disabled"
                findPreference<Preference>("certificate_path")?.isVisible = tlsEnabled
                findPreference<EditTextPreference>("certificate_password")?.isVisible = tlsEnabled
                findPreference<Preference>("test_certificate")?.isVisible = tlsEnabled

                setOnPreferenceChangeListener { _, newValue ->
                    val tlsEnabled = newValue != "disabled"
                    // Show/hide certificate preferences
                    findPreference<Preference>("certificate_path")?.isVisible = tlsEnabled
                    findPreference<EditTextPreference>("certificate_password")?.isVisible = tlsEnabled
                    findPreference<Preference>("test_certificate")?.isVisible = tlsEnabled
                    // Restart server when TLS version changes
                    restartStreamingServer()
                    true
                }
            }

            // Configure username (optional - defaults available)
            findPreference<EditTextPreference>("username")?.apply {
                // Load current value from secure storage
                text = secureStorage.getSecureString(SecureStorage.KEY_USERNAME, "")

                setOnPreferenceChangeListener { _, newValue ->
                    val username = newValue.toString()
                    if (username.isNotEmpty() && !InputValidator.isValidUsername(username)) {
                        Toast.makeText(requireContext(),
                            "Username must be 1-50 characters, letters/numbers/hyphens/underscores only",
                            Toast.LENGTH_LONG).show()
                        return@setOnPreferenceChangeListener false
                    }
                    // Store securely (empty string means use default)
                    secureStorage.putSecureString(SecureStorage.KEY_USERNAME, username)
                    // Restart server when username changes
                    restartStreamingServer()
                    true
                }
            }

            // Configure password (optional - defaults available)
            findPreference<EditTextPreference>("password")?.apply {
                // Do not show the existing password when editing
                setOnBindEditTextListener { editText ->
                    editText.text = null
                    editText.hint = "Enter new password"
                }

                setOnPreferenceChangeListener { _, newValue ->
                    val password = newValue.toString()

                    // Empty input means "no change" – keep existing password
                    if (password.isEmpty()) {
                        return@setOnPreferenceChangeListener false
                    }

                    if (!InputValidator.isValidPassword(password)) {
                        Toast.makeText(
                            requireContext(),
                            "Password must be 8-128 characters with uppercase, lowercase, and number",
                            Toast.LENGTH_LONG
                        ).show()
                        return@setOnPreferenceChangeListener false
                    }

                    // Store securely only; do not persist plaintext in SharedPreferences
                    secureStorage.putSecureString(SecureStorage.KEY_PASSWORD, password)
                    // Restart server when password changes
                    restartStreamingServer()
                    // Returning false prevents EditTextPreference from saving the plaintext
                    false
                }
            }

            // Configure streaming port preference
            findPreference<EditTextPreference>("server_port")?.apply {
                setOnBindEditTextListener { editText ->
                    editText.inputType = android.text.InputType.TYPE_CLASS_NUMBER
                }

                setOnPreferenceChangeListener { _, newValue ->
                    val portStr = newValue.toString()
                    val port = portStr.toIntOrNull()
                    if (port == null || port !in 1..65535) {
                        Toast.makeText(
                            requireContext(),
                            "Port must be between 1 and 65535",
                            Toast.LENGTH_LONG
                        ).show()
                        return@setOnPreferenceChangeListener false
                    }
                    summary = "Port $port"
                    restartStreamingServer()
                    true
                }
            }

            // Add validation for certificate password
            findPreference<EditTextPreference>("certificate_password")?.apply {
                // Do not pre-fill the existing certificate password when editing
                setOnBindEditTextListener { editText ->
                    editText.text = null
                    editText.hint = "Enter certificate password"
                }

                setOnPreferenceChangeListener { _, newValue ->
                    val password = newValue.toString()
                    if (!InputValidator.isValidCertificatePassword(password)) {
                        Toast.makeText(
                            requireContext(),
                            "Certificate password too long (max 256 characters)",
                            Toast.LENGTH_LONG
                        ).show()
                        return@setOnPreferenceChangeListener false
                    }

                    // Basic validation - check if password is not empty for certificate usage
                    if (password.isEmpty()) {
                        Toast.makeText(
                            requireContext(),
                            "Certificate password is required",
                            Toast.LENGTH_LONG
                        ).show()
                        return@setOnPreferenceChangeListener false
                    }

                    // Store securely only; do not persist plaintext in SharedPreferences
                    secureStorage.putSecureString(SecureStorage.KEY_CERT_PASSWORD, password)
                    Toast.makeText(
                        requireContext(),
                        "Certificate password saved, use 'Test Certificate Setup' to validate",
                        Toast.LENGTH_SHORT
                    ).show()
                    // Restart server when certificate password changes
                    restartStreamingServer()
                    // Returning false prevents EditTextPreference from saving the plaintext
                    false
                }
            }

            // Configure the video recording storage location (defaults to Movies/AndroidIPCamera)
            findPreference<Preference>("recording_storage_uri")?.apply {
                summary = recordingStorageSummary()
                setOnPreferenceClickListener {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                        addFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                        )
                    }
                    startActivityForResult(
                        Intent.createChooser(intent, "Select recording folder"),
                        PICK_RECORDING_FOLDER
                    )
                    true
                }
            }

            findPreference<Preference>("reset_recording_storage")?.apply {
                setOnPreferenceClickListener {
                    val current = RecordingsHelper.customStorageUri(requireContext())
                    if (current != null) {
                        try {
                            requireContext().contentResolver.releasePersistableUriPermission(
                                current,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            )
                        } catch (_: Exception) {
                            // Permission may already be gone; nothing to release
                        }
                    }
                    PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()
                        .remove(RecordingsHelper.PREF_RECORDING_STORAGE_URI)
                        .apply()
                    findPreference<Preference>("recording_storage_uri")?.summary = recordingStorageSummary()
                    Toast.makeText(requireContext(),
                        "Recordings will be saved to Movies/AndroidIPCamera",
                        Toast.LENGTH_SHORT).show()
                    true
                }
            }

            // Add test certificate functionality
            findPreference<Preference>("test_certificate")?.apply {
                setOnPreferenceClickListener {
                    val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
                    val certificatePath = prefs.getString("certificate_path", null)
                    val certPassword = secureStorage.getSecureString(SecureStorage.KEY_CERT_PASSWORD, "")

                    if (certPassword.isNullOrEmpty()) {
                        Toast.makeText(requireContext(),
                            "Certificate password not configured, set it above first",
                            Toast.LENGTH_LONG).show()
                        return@setOnPreferenceClickListener true
                    }

                    val isValid = if (certificatePath != null) {
                        // Test custom certificate
                        val certUri = android.net.Uri.parse(certificatePath)
                        InputValidator.validateCertificateUsability(requireContext(), certUri, certPassword)
                    } else {
                        // Test built-in certificate
                        InputValidator.validateBuiltInCertificate(requireContext(), certPassword)
                    }

                    if (isValid) {
                        Toast.makeText(requireContext(),
                            "✅ Certificate configuration is valid",
                            Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(requireContext(),
                            "❌ Certificate validation failed, check password and certificate file",
                            Toast.LENGTH_LONG).show()
                    }

                    true
                }
            }

        }

        override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            if (requestCode == PICK_CERTIFICATE_FILE && resultCode == Activity.RESULT_OK) {
                data?.data?.let { uri ->
                    val certificatePath = uri.toString()

                    // Enhanced certificate validation
                    if (!InputValidator.isValidCertificatePath(certificatePath)) {
                        Toast.makeText(requireContext(),
                            "Invalid certificate file, must be a valid .p12 or .pfx file under 10MB",
                            Toast.LENGTH_LONG).show()
                        return@let
                    }

                    // Validate certificate can actually be loaded and used
                    val secureStorage = SecureStorage(requireContext())
                    val certPassword = secureStorage.getSecureString(SecureStorage.KEY_CERT_PASSWORD, "")
                    val certificateUri = Uri.parse(certificatePath)

                    if (!InputValidator.validateCertificateUsability(requireContext(), certificateUri, certPassword)) {
                        Toast.makeText(requireContext(),
                            "Certificate cannot be loaded, check password and file integrity",
                            Toast.LENGTH_LONG).show()
                        return@let
                    }

                    // Store the certificate path
                    preferenceManager.sharedPreferences?.edit()?.apply {
                        putString("certificate_path", certificatePath)
                        apply()
                    }
                    // Update the preference summary
                    findPreference<Preference>("certificate_path")?.summary = certificatePath

                    Toast.makeText(requireContext(),
                        "Certificate configured, restart the app for changes to take effect",
                        Toast.LENGTH_SHORT).show()
                }
            }

            if (requestCode == PICK_RECORDING_FOLDER && resultCode == Activity.RESULT_OK) {
                data?.data?.let { uri ->
                    try {
                        requireContext().contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                        PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()
                            .putString(RecordingsHelper.PREF_RECORDING_STORAGE_URI, uri.toString())
                            .apply()
                        findPreference<Preference>("recording_storage_uri")?.summary = recordingStorageSummary()
                        Toast.makeText(requireContext(),
                            "Recording location updated", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(),
                            "Could not use that folder: ${e.message}",
                            Toast.LENGTH_LONG).show()
                    }
                }
            }
            super.onActivityResult(requestCode, resultCode, data)
        }

        /** Summary for the storage preference: the chosen folder name or the default path. */
        private fun recordingStorageSummary(): String {
            val uri = RecordingsHelper.customStorageUri(requireContext())
                ?: return "Default: ${RecordingsHelper.DEFAULT_RELATIVE_PATH}"
            val name = DocumentFile.fromTreeUri(requireContext(), uri)?.name
            return "Custom folder: ${name ?: uri}"
        }

        private fun restartStreamingServer() {
            val intent = Intent(requireContext(), StreamingService::class.java).apply {
                action = StreamingService.ACTION_RESTART_SERVER
            }
            requireContext().startService(intent)
            Toast.makeText(requireContext(), "Server restarting...", Toast.LENGTH_SHORT).show()
        }
    }
}
