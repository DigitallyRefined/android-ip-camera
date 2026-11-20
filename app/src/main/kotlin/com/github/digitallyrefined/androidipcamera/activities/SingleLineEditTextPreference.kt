package com.github.digitallyrefined.androidipcamera.activities

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.preference.EditTextPreference

/**
 * An [EditTextPreference] whose dialog input is always a single line.
 *
 * EditTextPreference does not forward android:singleLine from the preference XML to the
 * EditText shown in its dialog, so fields such as the username and password could wrap onto
 * several lines and Enter inserted a newline. This applies the single-line behaviour in the
 * dialog's bind listener and makes the keyboard's action key commit the dialog.
 */
open class SingleLineEditTextPreference(
    context: Context,
    attrs: AttributeSet?
) : EditTextPreference(context, attrs) {

    init {
        super.setOnBindEditTextListener { editText ->
            applySingleLine(editText, listener = null)
        }
    }

    /**
     * Wraps any listener set by the caller so the preference-specific setup (input type, hint,
     * and so on) still runs, while the field stays on a single line.
     */
    override fun setOnBindEditTextListener(
        listener: EditTextPreference.OnBindEditTextListener?
    ) {
        super.setOnBindEditTextListener { editText ->
            applySingleLine(editText, listener)
        }
    }

    private fun applySingleLine(
        editText: EditText,
        listener: EditTextPreference.OnBindEditTextListener?
    ) {
        // Apply this before the caller's listener. If a password input type were set first,
        // setSingleLine() would replace its password transformation with the single-line one
        // and reveal the text.
        editText.isSingleLine = true
        listener?.onBindEditText(editText)

        editText.imeOptions = EditorInfo.IME_ACTION_DONE
        // Let the keyboard's action key commit the dialog, like the Save button.
        editText.setOnEditorActionListener { view, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                view.rootView.findViewById<View>(android.R.id.button1)?.performClick()
                true
            } else {
                false
            }
        }
    }
}
