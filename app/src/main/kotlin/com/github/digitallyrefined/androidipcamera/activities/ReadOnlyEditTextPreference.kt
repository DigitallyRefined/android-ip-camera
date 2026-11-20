package com.github.digitallyrefined.androidipcamera.activities

import android.content.Context
import android.util.AttributeSet

/**
 * A [SingleLineEditTextPreference] that can refuse to open its edit dialog, for example while
 * its value is fixed by a registration. The row stays enabled and normal-looking; a tap while
 * [readOnly] runs [onReadOnlyClick] instead of showing the input.
 *
 * This cannot be done with [androidx.preference.Preference.setOnPreferenceClickListener]
 * because [androidx.preference.Preference.performClick] opens the dialog first.
 */
class ReadOnlyEditTextPreference(
    context: Context,
    attrs: AttributeSet?
) : SingleLineEditTextPreference(context, attrs) {
    /** Set while the value must not be edited. */
    var readOnly: Boolean = false

    /** Invoked when the row is tapped while [readOnly]. */
    var onReadOnlyClick: (() -> Unit)? = null

    override fun onClick() {
        if (readOnly) {
            onReadOnlyClick?.invoke()
            return
        }
        super.onClick()
    }
}
