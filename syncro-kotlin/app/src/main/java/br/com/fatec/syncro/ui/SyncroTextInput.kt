package br.com.fatec.syncro.ui

import android.content.Context
import android.graphics.Typeface
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
        textSize = 16f
        setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        setHintTextColor(ContextCompat.getColor(context, R.color.gray))
    }

    init {
        boxBackgroundMode = BOX_BACKGROUND_OUTLINE
        boxBackgroundColor = ContextCompat.getColor(context, R.color.white)
        setBoxStrokeColorStateList(requireNotNull(
            ContextCompat.getColorStateList(context, R.color.input_stroke)
        ))
        hintTextColor = requireNotNull(
            ContextCompat.getColorStateList(context, R.color.input_hint)
        )

        context.obtainStyledAttributes(
            attrs,
            R.styleable.SyncroTextInput
        ).apply {
            input.inputType = getInt(R.styleable.SyncroTextInput_android_inputType, InputType.TYPE_CLASS_TEXT)
            // Campos de senha trocam a fonte do editor (e do rótulo flutuante)
            // para monoespaçada. Mantém a mesma fonte em todos os campos.
            input.typeface = Typeface.DEFAULT
            input.imeOptions = getInt(R.styleable.SyncroTextInput_android_imeOptions, EditorInfo.IME_ACTION_UNSPECIFIED)
            getString(R.styleable.SyncroTextInput_android_autofillHints)?.let { hints ->
                input.setAutofillHints(*hints.split(',').map(String::trim).toTypedArray())
            }
            recycle()
        }

        addView(input)
    }

}
