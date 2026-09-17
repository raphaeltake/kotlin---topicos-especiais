package br.com.fatec.syncro.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.InputType
import android.util.AttributeSet
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.content.ContextCompat
import br.com.fatec.syncro.R
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class SyncroTextInput @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = R.attr.syncroTextInputStyle
) : TextInputLayout(context, attrs, defStyleAttr) {

    val input = TextInputEditText(this.context).apply {
        // The outline is installed by TextInputLayout only when the editor has
        // no background of its own. A programmatic editor inherits one from
        // the theme unless it is explicitly cleared before addView().
        background = null
        layoutParams = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        isSingleLine = true
        maxLines = 1
        setTextColor(ContextCompat.getColor(context, R.color.black))
        setHintTextColor(ContextCompat.getColor(context, R.color.gray))
    }

    init {
        boxBackgroundMode = BOX_BACKGROUND_OUTLINE
        boxBackgroundColor = Color.TRANSPARENT
        setBoxStrokeColorStateList(requireNotNull(
            ContextCompat.getColorStateList(context, R.color.input_stroke)
        ))
        hintTextColor = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.gray))

        context.obtainStyledAttributes(
            attrs,
            intArrayOf(
                android.R.attr.inputType,
                android.R.attr.imeOptions,
                android.R.attr.autofillHints
            )
        ).apply {
            input.inputType = getInt(0, InputType.TYPE_CLASS_TEXT)
            input.imeOptions = getInt(1, EditorInfo.IME_ACTION_UNSPECIFIED)
            getString(2)?.let { hints ->
                input.setAutofillHints(*hints.split(',').map(String::trim).toTypedArray())
            }
            recycle()
        }

        addView(input)
    }

}
