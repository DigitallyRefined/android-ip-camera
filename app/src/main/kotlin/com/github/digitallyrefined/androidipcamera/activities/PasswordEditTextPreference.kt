package com.github.digitallyrefined.androidipcamera.activities

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import androidx.preference.EditTextPreference
import com.github.digitallyrefined.androidipcamera.R

/**
 * A single-line [EditTextPreference] with a Material password field and visibility toggle.
 *
 * The dialog uses a [com.google.android.material.textfield.TextInputLayout] with
 * app:endIconMode="password_toggle", which is Material's standard show/hide password control.
 */
class PasswordEditTextPreference(
    context: Context,
    attrs: AttributeSet?
) : SingleLineEditTextPreference(context, attrs) {

    init {
        dialogLayoutResource = R.layout.dialog_password_preference
    }

    override fun setOnBindEditTextListener(
        listener: EditTextPreference.OnBindEditTextListener?
    ) {
        super.setOnBindEditTextListener { editText ->
            listener?.onBindEditText(editText)

            // SingleLineEditTextPreference applies its transformation before this listener,
            // so restore password masking for the Material toggle to work from.
            editText.inputType =
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
    }
}
