package com.github.digitallyrefined.androidipcamera

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat

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

            // Add listener for camera resolution changes
            findPreference<Preference>("camera_resolution")?.apply {
                setOnPreferenceChangeListener { preference, newValue ->
                    // Save the new value first
                    preferenceManager.sharedPreferences?.edit()?.apply {
                        putString("camera_resolution", newValue.toString())
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
        }

        override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            if (requestCode == PICK_CERTIFICATE_FILE && resultCode == Activity.RESULT_OK) {
                data?.data?.let { uri ->
                    // Store the certificate path
                    val certificatePath = uri.toString()
                    preferenceManager.sharedPreferences?.edit()?.apply {
                        putString("certificate_path", certificatePath)
                        apply()
                    }
                    // Update the preference summary
                    findPreference<Preference>("certificate_path")?.summary = certificatePath
                }
            }
            super.onActivityResult(requestCode, resultCode, data)
        }
    }
}
