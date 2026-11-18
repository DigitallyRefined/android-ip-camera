package com.github.digitallyrefined.androidipcamera.activities

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.github.digitallyrefined.androidipcamera.R
import com.github.digitallyrefined.androidipcamera.StreamingService
import com.github.digitallyrefined.androidipcamera.helpers.InputValidator
import com.github.digitallyrefined.androidipcamera.helpers.ListenInterface
import com.github.digitallyrefined.androidipcamera.helpers.RecordingsHelper
import com.github.digitallyrefined.androidipcamera.helpers.RtspServer
import com.github.digitallyrefined.androidipcamera.helpers.SecureStorage
import com.github.digitallyrefined.androidipcamera.helpers.TlsHelper
import com.github.digitallyrefined.androidipcamera.opentunnel.OpenTunnelConfig
import com.github.digitallyrefined.androidipcamera.opentunnel.OpenTunnelController
import com.github.digitallyrefined.androidipcamera.opentunnel.OpenTunnelStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportFragmentManager
            .beginTransaction()
            .replace(android.R.id.content, SettingsFragment())
            .commit()
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        private var openTunnelPollJob: Job? = null

        companion object {
            private const val PICK_CERTIFICATE_FILE = 1
            private const val PICK_RECORDING_FOLDER = 2
            private const val PREF_ONVIF_NAME = "onvif_name"
            private const val PREF_ONVIF_PORT = "onvif_port"
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            // Starting the camera/microphone foreground service from BOOT_COMPLETED is only
            // blocked on Android 14+, where the app posts a notification instead. Only warn while
            // the option is enabled.
            findPreference<androidx.preference.CheckBoxPreference>("start_on_boot")?.apply {
                summaryProvider = Preference.SummaryProvider<androidx.preference.CheckBoxPreference> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && it.isChecked) {
                        "⚠️ Warning: Android 14+ does not let the camera or microphone start automatically after boot. A notification will be shown to open the app so streaming can start."
                    } else {
                        "Auto start on Android 13 and older. On Android 14+ a notification will be shown"
                    }
                }
            }

            // Set up certificate selection preference
            findPreference<Preference>("certificate_path")?.apply {
                setOnPreferenceClickListener {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/x-pkcs12", "application/pkcs12", "*/*"))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
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

            // Configure TLS checkbox and version to hide/show certificate options
            val tlsCheckbox = findPreference<androidx.preference.CheckBoxPreference>("tls_enabled")
            fun updateTlsVisibility(enabled: Boolean) {
                findPreference<androidx.preference.ListPreference>("tls_version")?.isVisible = enabled
                findPreference<Preference>("certificate_path")?.isVisible = enabled
                findPreference<EditTextPreference>("certificate_password")?.isVisible = enabled
                findPreference<Preference>("test_certificate")?.isVisible = enabled
            }
            updateTlsVisibility(tlsCheckbox?.isChecked ?: true)
            tlsCheckbox?.setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                updateTlsVisibility(enabled)
                restartStreamingServer()
                true
            }
            findPreference<androidx.preference.ListPreference>("tls_version")?.setOnPreferenceChangeListener { _, _ ->
                restartStreamingServer()
                true
            }

            // Configure username (optional - defaults available)
            findPreference<EditTextPreference>("username")?.apply {
                // Load current value from secure storage
                val currentUsername =
                    secureStorage.getSecureString(SecureStorage.KEY_USERNAME, "") ?: ""
                text = currentUsername
                summary = if (currentUsername.isBlank()) "Not set" else currentUsername

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
                    summary = if (username.isBlank()) "Not set" else username
                    // Restart server when username changes
                    restartStreamingServer()
                    true
                }
            }

            // Configure password (optional - defaults available)
            findPreference<EditTextPreference>("password")?.apply {
                // Never reveal the stored password, only whether one is set.
                summary = if (
                    (secureStorage.getSecureString(SecureStorage.KEY_PASSWORD, "") ?: "").isEmpty()
                ) {
                    "Not set"
                } else {
                    "***"
                }

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
                    summary = "***"
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
                    if (isRtspEnabled() && port == configuredRtspPort()) {
                        Toast.makeText(requireContext(), "Port is already used by the RTSP server", Toast.LENGTH_LONG).show()
                        return@setOnPreferenceChangeListener false
                    }
                    if (isRtspEnabled() && port == configuredOnvifPort()) {
                        Toast.makeText(requireContext(), "Port is already used by the ONVIF server", Toast.LENGTH_LONG).show()
                        return@setOnPreferenceChangeListener false
                    }
                    summary = "Port $port"
                    restartStreamingServer()
                    true
                }
            }

            // Interface shared by the web, RTSP and ONVIF servers. The list is rebuilt from the
            // interfaces present when settings are opened, so it never offers a stale address.
            findPreference<androidx.preference.ListPreference>(ListenInterface.PREF)?.apply {
                val options = ListenInterface.options()
                entries = options.map { it.first }.toTypedArray()
                entryValues = options.map { it.second }.toTypedArray()
                // Migrates IP addresses saved by older versions to the matching interface name.
                value = ListenInterface.value(requireContext())
                summaryProvider = Preference.SummaryProvider<androidx.preference.ListPreference> {
                    it.entry ?: "All interfaces"
                }
                setOnPreferenceChangeListener { _, newValue ->
                    if (newValue.toString() !in entryValues) {
                        return@setOnPreferenceChangeListener false
                    }
                    restartStreamingServer()
                    true
                }
            }

            // Optional RTSP/ONVIF server: shares the camera, encoder and credentials with the HTTP
            // server. ONVIF is part of this switch.
            findPreference<androidx.preference.CheckBoxPreference>(RtspServer.PREF_ENABLED)?.apply {
                setRtspOptionsVisible(isChecked)
                setOnvifOptionsVisible(isChecked)
                summaryProvider = Preference.SummaryProvider<androidx.preference.CheckBoxPreference> {
                    "Serve the camera at ${if (TlsHelper.isEnabled(requireContext())) "rtsps" else "rtsp"}://[ip_address]:[port]/live (H.264 video, AAC audio) and enable ONVIF discovery/control for VLC, Frigate, NVRs and Home Assistant, using the same username and password"
                }

                setOnPreferenceChangeListener { _, newValue ->
                    val enabled = newValue as Boolean
                    if (enabled && configuredRtspPort() == configuredHttpPort()) {
                        Toast.makeText(requireContext(),
                            "RTSP port must differ from the streaming port",
                            Toast.LENGTH_LONG).show()
                        return@setOnPreferenceChangeListener false
                    }
                    if (enabled && configuredRtspPort() == configuredOnvifPort()) {
                        Toast.makeText(requireContext(),
                            "RTSP port must differ from the ONVIF port",
                            Toast.LENGTH_LONG).show()
                        return@setOnPreferenceChangeListener false
                    }
                    if (enabled && configuredOnvifPort() == configuredHttpPort()) {
                        Toast.makeText(requireContext(),
                            "ONVIF port must differ from the streaming port",
                            Toast.LENGTH_LONG).show()
                        return@setOnPreferenceChangeListener false
                    }
                    setRtspOptionsVisible(enabled)
                    setOnvifOptionsVisible(enabled)
                    restartStreamingServer()
                    true
                }
            }

            findPreference<EditTextPreference>(RtspServer.PREF_PORT)?.apply {
                setOnBindEditTextListener { editText ->
                    editText.inputType = android.text.InputType.TYPE_CLASS_NUMBER
                }

                setOnPreferenceChangeListener { _, newValue ->
                    val port = newValue.toString().toIntOrNull()
                    if (port == null || port !in 1..65535) {
                        Toast.makeText(requireContext(), "Port must be between 1 and 65535", Toast.LENGTH_LONG).show()
                        return@setOnPreferenceChangeListener false
                    }
                    if (port == configuredHttpPort()) {
                        Toast.makeText(requireContext(), "RTSP port must differ from the streaming port", Toast.LENGTH_LONG).show()
                        return@setOnPreferenceChangeListener false
                    }
                    if (isRtspEnabled() && port == configuredOnvifPort()) {
                        Toast.makeText(requireContext(), "RTSP port must differ from the ONVIF port", Toast.LENGTH_LONG).show()
                        return@setOnPreferenceChangeListener false
                    }
                    summary = "Port $port"
                    restartStreamingServer()
                    true
                }
            }

            // ONVIF follows the RTSP switch: it advertises the RTSP stream and motion events.
            findPreference<EditTextPreference>(PREF_ONVIF_NAME)?.apply {
                setOnPreferenceChangeListener { _, _ ->
                    restartStreamingServer()
                    true
                }
            }

            findPreference<EditTextPreference>(PREF_ONVIF_PORT)?.apply {
                setOnBindEditTextListener { editText ->
                    editText.inputType = android.text.InputType.TYPE_CLASS_NUMBER
                }
                setOnPreferenceChangeListener { _, newValue ->
                    val port = newValue.toString().toIntOrNull()
                    if (port == null || port !in 1..65535) {
                        Toast.makeText(requireContext(), "Port must be between 1 and 65535", Toast.LENGTH_LONG).show()
                        return@setOnPreferenceChangeListener false
                    }
                    if (port == configuredHttpPort()) {
                        Toast.makeText(requireContext(), "ONVIF port must differ from the streaming port", Toast.LENGTH_LONG).show()
                        return@setOnPreferenceChangeListener false
                    }
                    if (port == configuredRtspPort()) {
                        Toast.makeText(requireContext(), "ONVIF port must differ from the RTSP port", Toast.LENGTH_LONG).show()
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
                        "Recordings will be saved to ${RecordingsHelper.DEFAULT_RELATIVE_PATH}",
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
                        // Test the private copy that the servers actually load.
                        val privateFile = File(requireContext().filesDir, "certificate.p12")
                        if (privateFile.exists()) {
                            InputValidator.validateCertificateFile(privateFile, certPassword)
                        } else {
                            val certUri = android.net.Uri.parse(certificatePath)
                            InputValidator.validateCertificateUsability(requireContext(), certUri, certPassword)
                        }
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

            setupOpenTunnelPreferences()
        }

        override fun onResume() {
            super.onResume()
            startOpenTunnelPolling()
        }

        override fun onPause() {
            openTunnelPollJob?.cancel()
            openTunnelPollJob = null
            super.onPause()
        }

        private fun setupOpenTunnelPreferences() {
            findPreference<ReadOnlyEditTextPreference>(OpenTunnelConfig.PREF_API_URL)?.apply {
                summary = OpenTunnelConfig.apiUrl(requireContext())
                onReadOnlyClick = {
                    Toast.makeText(requireContext(), registeredServerMessage(), Toast.LENGTH_LONG).show()
                }
                setOnPreferenceChangeListener { _, newValue ->
                    val raw = newValue?.toString().orEmpty().trim()
                    val normalized = OpenTunnelConfig.normalizeApiUrl(raw)
                    val status = OpenTunnelController.status(requireContext())
                    if ((status.registered || status.hasFiles) &&
                        normalized != OpenTunnelConfig.apiUrl(requireContext())) {
                        Toast.makeText(
                            requireContext(),
                            registeredServerMessage(),
                            Toast.LENGTH_LONG
                        ).show()
                        return@setOnPreferenceChangeListener false
                    }
                    if (raw.isEmpty()) {
                        // An empty field means "use the default", not an invalid URL.
                        text = OpenTunnelConfig.DEFAULT_API_URL
                        summary = OpenTunnelConfig.DEFAULT_API_URL
                        return@setOnPreferenceChangeListener false
                    }
                    if (normalized == null) {
                        Toast.makeText(
                            requireContext(),
                            "Enter an http(s) server URL such as https://opentunnel.xyz",
                            Toast.LENGTH_LONG
                        ).show()
                        return@setOnPreferenceChangeListener false
                    }
                    summary = normalized
                    true
                }
            }

            findPreference<Preference>("opentunnel_status")?.apply {
                isSelectable = false
                setOnPreferenceClickListener {
                    val url = OpenTunnelController.status(requireContext()).url
                    if (url.isNullOrBlank()) return@setOnPreferenceClickListener false
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, url)
                    }
                    startActivity(Intent.createChooser(send, "Share tunnel URL"))
                    true
                }
            }

            findPreference<Preference>("opentunnel_action")?.apply {
                setOnPreferenceClickListener {
                    val problem = OpenTunnelConfig.credentialsProblem(requireContext())
                    if (problem != null) {
                        Toast.makeText(requireContext(), problem, Toast.LENGTH_LONG).show()
                        return@setOnPreferenceClickListener true
                    }
                    if (!OpenTunnelController.isAvailable) {
                        Toast.makeText(
                            requireContext(),
                            OpenTunnelController.unavailableReason ?: "OpenTunnel is unavailable on this build",
                            Toast.LENGTH_LONG
                        ).show()
                        return@setOnPreferenceClickListener true
                    }
                    val registered = OpenTunnelController.status(requireContext()).registered
                    val action = if (registered) {
                        StreamingService.ACTION_OPEN_TUNNEL_CONNECT
                    } else {
                        StreamingService.ACTION_OPEN_TUNNEL_REGISTER
                    }
                    requireContext().startService(
                        Intent(requireContext(), StreamingService::class.java).setAction(action)
                    )
                    Toast.makeText(
                        requireContext(),
                        if (registered) "Connecting OpenTunnel…" else "Registering OpenTunnel…",
                        Toast.LENGTH_SHORT
                    ).show()
                    true
                }
            }

            findPreference<Preference>("opentunnel_disconnect")?.apply {
                setOnPreferenceClickListener {
                    AlertDialog.Builder(requireContext())
                        .setTitle("Disconnect & disassociate?")
                        .setMessage(
                            "Once the tunnel is removed its public URL cannot be recovered."
                        )
                        .setPositiveButton("Disconnect & disassociate") { _, _ ->
                            requireContext().startService(
                                Intent(requireContext(), StreamingService::class.java).setAction(
                                    StreamingService.ACTION_OPEN_TUNNEL_FORGET
                                )
                            )
                            Toast.makeText(
                                requireContext(),
                                "Disconnecting and deleting OpenTunnel files…",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                    true
                }
            }

            refreshOpenTunnelPreferences()
        }

        private fun registeredServerMessage(): String =
            "Use Disconnect & disassociate before switching servers."

        private fun startOpenTunnelPolling() {
            openTunnelPollJob?.cancel()
            openTunnelPollJob = lifecycleScope.launch {
                while (isActive) {
                    refreshOpenTunnelPreferences()
                    delay(1_000)
                }
            }
        }

        private fun refreshOpenTunnelPreferences() {
            val context = context ?: return
            val status = OpenTunnelController.status(context)
            val registered = status.registered
            val connected = status.connected
            val unavailable = !OpenTunnelController.isAvailable

            findPreference<ReadOnlyEditTextPreference>(OpenTunnelConfig.PREF_API_URL)?.apply {
                summary = OpenTunnelConfig.apiUrl(context)
                readOnly = registered || status.hasFiles
            }

            findPreference<Preference>("opentunnel_status")?.apply {
                summary = when {
                    unavailable -> OpenTunnelController.unavailableReason ?: "Unavailable"
                    status.busy -> status.progressText()
                    connected -> status.url ?: status.hostname ?: "Connected"
                    status.state == OpenTunnelStatus.STATE_STOPPED -> status.progressText()
                    !registered && status.hasFiles -> "Registration incomplete · not connected"
                    !registered -> "Not registered"
                    else -> "Registered · not connected"
                }
                // A live public URL can be shared; every other state is informational only.
                isSelectable = connected && !status.url.isNullOrBlank()
            }

            findPreference<Preference>("opentunnel_action")?.apply {
                // Progress lives in the status row, so only offer the action when it can run.
                isVisible = !connected && !status.busy
                isEnabled = !unavailable
                if (registered) {
                    title = "Connect"
                    summary = "Connect this camera's registered tunnel"
                } else {
                    title = "Register"
                    summary = "Create a public tunnel and connect"
                }
            }

            findPreference<Preference>("opentunnel_disconnect")?.apply {
                // Registered identities can be removed whether connected or not. Incomplete
                // provisioning (e.g. a failed certificate request) can be cleared too.
                isVisible = registered || status.hasFiles
                isEnabled = !unavailable && status.state != OpenTunnelStatus.STATE_FORGETTING
            }
        }

        override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            if (requestCode == PICK_CERTIFICATE_FILE && resultCode == Activity.RESULT_OK) {
                data?.data?.let { uri ->
                    val certificatePath = uri.toString()

                    // Enhanced certificate validation
                    if (!InputValidator.isValidCertificatePath(requireContext(), certificatePath)) {
                        Toast.makeText(requireContext(),
                            "Invalid certificate file, must be a valid .p12 or .pfx file under 10MB",
                            Toast.LENGTH_LONG).show()
                        return@let
                    }

                    // Validate certificate can actually be loaded and used
                    val secureStorage = SecureStorage(requireContext())
                    val certPassword = secureStorage.getSecureString(SecureStorage.KEY_CERT_PASSWORD, "")
                    val certificateUri = Uri.parse(certificatePath)

                    // Keep a private copy so the certificate does not depend on URI permissions
                    // that may be lost when the app restarts.
                    val privateFile = File(requireContext().filesDir, "certificate.p12")
                    try {
                        requireContext().contentResolver.openInputStream(certificateUri)?.use { input ->
                            privateFile.outputStream().use { output -> input.copyTo(output) }
                        } ?: throw IllegalStateException("Unable to open the selected file")
                    } catch (e: Exception) {
                        Toast.makeText(
                            requireContext(),
                            "Could not store the certificate: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                        return@let
                    }

                    // Ask for persistable access when the provider supports it. The private copy
                    // above is what the servers actually use, so failure here is not fatal.
                    if (uri.scheme == "content") {
                        try {
                            requireContext().contentResolver.takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        } catch (_: Exception) {
                            // Provider does not offer persistable grants; the private copy covers it.
                        }
                    }

                    // Store the certificate path
                    preferenceManager.sharedPreferences?.edit()?.apply {
                        putString("certificate_path", certificatePath)
                        apply()
                    }
                    // Update the preference summary
                    findPreference<Preference>("certificate_path")?.summary = certificatePath

                    // A certificate generated on first launch has a random password that the user
                    // never sees. If the stored password does not unlock the new file, clear it so
                    // the user is prompted to enter the password for this certificate. Selection
                    // must not fail just because the password has not been set yet.
                    val unlocked = !certPassword.isNullOrEmpty() &&
                        InputValidator.validateCertificateFile(privateFile, certPassword)
                    if (!unlocked && !certPassword.isNullOrEmpty()) {
                        secureStorage.removeSecureString(SecureStorage.KEY_CERT_PASSWORD)
                    }

                    if (unlocked) {
                        Toast.makeText(
                            requireContext(),
                            "Certificate configured, restart the app for changes to take effect",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(
                            requireContext(),
                            "Certificate selected. Set its password in Certificate Password, then use Test Certificate Setup.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
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

        private fun setRtspOptionsVisible(visible: Boolean) {
            listOf(RtspServer.PREF_PORT)
                .forEach { findPreference<Preference>(it)?.isVisible = visible }
        }

        private fun setOnvifOptionsVisible(visible: Boolean) {
            listOf(PREF_ONVIF_NAME, PREF_ONVIF_PORT)
                .forEach { findPreference<Preference>(it)?.isVisible = visible }
        }

        private fun configuredHttpPort(): Int =
            preferenceManager.sharedPreferences?.getString("server_port", null)?.toIntOrNull() ?: 4444

        private fun configuredRtspPort(): Int =
            preferenceManager.sharedPreferences?.getString(RtspServer.PREF_PORT, null)?.toIntOrNull()
                ?: RtspServer.DEFAULT_PORT

        private fun configuredOnvifPort(): Int =
            preferenceManager.sharedPreferences?.getString(PREF_ONVIF_PORT, null)?.toIntOrNull() ?: 8000

        private fun isRtspEnabled(): Boolean =
            preferenceManager.sharedPreferences?.getBoolean(RtspServer.PREF_ENABLED, false) == true

        private fun restartStreamingServer() {
            val intent = Intent(requireContext(), StreamingService::class.java).apply {
                action = StreamingService.ACTION_RESTART_SERVER
            }
            requireContext().startService(intent)
            Toast.makeText(requireContext(), "Server restarting...", Toast.LENGTH_SHORT).show()
        }
    }
}
