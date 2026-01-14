package com.github.digitallyrefined.androidipcamera

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.github.digitallyrefined.androidipcamera.helpers.InputValidator
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
            }

            val secureStorage = SecureStorage(requireContext())

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
                    true
                }
            }

            // Configure password (optional - defaults available)
            findPreference<EditTextPreference>("password")?.apply {
                setOnPreferenceChangeListener { _, newValue ->
                    val password = newValue.toString()
                    if (password.isNotEmpty() && !InputValidator.isValidPassword(password)) {
                        Toast.makeText(requireContext(),
                            "Password must be 8-128 characters with uppercase, lowercase, and number",
                            Toast.LENGTH_LONG).show()
                        return@setOnPreferenceChangeListener false
                    }
                    // Store securely (empty string means use default)
                    secureStorage.putSecureString(SecureStorage.KEY_PASSWORD, password)
                    true
                }
            }

            // Add validation for stream delay
            findPreference<EditTextPreference>("stream_delay")?.apply {
                setOnPreferenceChangeListener { _, newValue ->
                    val delay = newValue.toString()
                    if (!InputValidator.isValidStreamDelay(delay)) {
                        Toast.makeText(requireContext(),
                            "Stream delay must be between 10-1000 milliseconds",
                            Toast.LENGTH_LONG).show()
                        return@setOnPreferenceChangeListener false
                    }
                    true
                }
            }

            // Add validation for certificate password
            findPreference<EditTextPreference>("certificate_password")?.apply {
                // Load current value from secure storage
                text = secureStorage.getSecureString(SecureStorage.KEY_CERT_PASSWORD, "")

                setOnPreferenceChangeListener { _, newValue ->
                    val password = newValue.toString()
                    if (!InputValidator.isValidCertificatePassword(password)) {
                        Toast.makeText(requireContext(),
                            "Certificate password too long (max 256 characters)",
                            Toast.LENGTH_LONG).show()
                        return@setOnPreferenceChangeListener false
                    }

                    // Basic validation - check if password is not empty for certificate usage
                    if (password.isEmpty()) {
                        Toast.makeText(requireContext(),
                            "Certificate password is required",
                            Toast.LENGTH_LONG).show()
                        return@setOnPreferenceChangeListener false
                    }

                    // Store securely
                    secureStorage.putSecureString(SecureStorage.KEY_CERT_PASSWORD, password)
                    Toast.makeText(requireContext(),
                        "Certificate password saved. Use 'Test Certificate Setup' to validate.",
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
                            "Certificate password not configured. Set it above first.",
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
                            "✅ Certificate configuration is valid!",
                            Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(requireContext(),
                            "❌ Certificate validation failed. Check password and certificate file.",
                            Toast.LENGTH_LONG).show()
                    }

                    true
                }
            }

            // Add listener for camera resolution changes
            findPreference<Preference>("camera_resolution")?.apply {
                setOnPreferenceChangeListener { preference, newValue ->
                    val resolution = newValue.toString()
                    if (!InputValidator.isValidCameraResolution(resolution)) {
                        Toast.makeText(requireContext(),
                            "Invalid camera resolution setting",
                            Toast.LENGTH_SHORT).show()
                        return@setOnPreferenceChangeListener false
                    }

                    // Save the new value first
                    preferenceManager.sharedPreferences?.edit()?.apply {
                        putString("camera_resolution", resolution)
                        apply()
                    }

                    // Delay the restart to ensure preference is saved
                    Handler(Looper.getMainLooper()).postDelayed({
                        val intent = Intent(requireActivity(), MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        }
                        startActivity(intent)
                        Runtime.getRuntime().exit(0)
                    }, 500) // 500ms delay

                    true
                }
            }

            // Add listeners for zoom and scale changes (restart camera instead of app)
            findPreference<Preference>("camera_zoom")?.apply {
                setOnPreferenceChangeListener { _, newValue ->
                    val zoom = newValue.toString()
                    val zoomFloat = zoom.toFloatOrNull()
                    if (zoomFloat == null || zoomFloat < 0.5f || zoomFloat > 2.0f) {
                        Toast.makeText(requireContext(),
                            "Zoom must be between 0.5x and 2.0x",
                            Toast.LENGTH_SHORT).show()
                        return@setOnPreferenceChangeListener false
                    }

                    // Send broadcast to restart camera with new zoom
                    val intent = Intent("com.github.digitallyrefined.androidipcamera.RESTART_CAMERA").apply {
                        setPackage(requireContext().packageName)
                    }
                    requireContext().sendBroadcast(intent)

                    true
                }
            }

            findPreference<Preference>("stream_scale")?.apply {
                setOnPreferenceChangeListener { _, newValue ->
                    val scale = (newValue as? String)?.toFloatOrNull() ?: 1.0f
                    if (scale < 0.5f || scale > 2.0f) {
                        Toast.makeText(requireContext(),
                            "Scale must be between 0.5x and 2.0x",
                            Toast.LENGTH_SHORT).show()
                        return@setOnPreferenceChangeListener false
                    }

                    // Send broadcast to restart camera with new scale
                    val intent = Intent("com.github.digitallyrefined.androidipcamera.RESTART_CAMERA").apply {
                        setPackage(requireContext().packageName)
                    }
                    requireContext().sendBroadcast(intent)

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
                            "Invalid certificate file. Must be a valid .p12 or .pfx file under 10MB",
                            Toast.LENGTH_LONG).show()
                        return@let
                    }

                    // Validate certificate can actually be loaded and used
                    val secureStorage = SecureStorage(requireContext())
                    val certPassword = secureStorage.getSecureString(SecureStorage.KEY_CERT_PASSWORD, "")
                    val certificateUri = Uri.parse(certificatePath)

                    if (!InputValidator.validateCertificateUsability(requireContext(), certificateUri, certPassword)) {
                        Toast.makeText(requireContext(),
                            "Certificate cannot be loaded. Check password and file integrity.",
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
                        "Certificate configured. Restart the app for changes to take effect.",
                        Toast.LENGTH_SHORT).show()
                }
            }
            super.onActivityResult(requestCode, resultCode, data)
        }
    }
}
