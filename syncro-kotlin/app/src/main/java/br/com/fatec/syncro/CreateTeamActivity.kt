package br.com.fatec.syncro

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import br.com.fatec.syncro.ui.SyncroTextInput

class CreateTeamActivity : AppCompatActivity() {
    override fun onResume() {
        super.onResume()
        // Atualiza o sino de notificações (e o botão voltar) ao retornar à tela.
        Navigation.toolbar(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_create_team)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.createTeamRoot)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val teamNameInputLayout = findViewById<SyncroTextInput>(R.id.teamNameInputLayout)
        val teamNameInput = teamNameInputLayout.input
        val createTeamButton = findViewById<Button>(R.id.btnCreateTeam)
        // UC04: o formulário pede nome e descrição.
        val descriptionInputLayout = addDescriptionField(teamNameInputLayout, createTeamButton)
        val descriptionInput = descriptionInputLayout.input
        teamNameInput.imeOptions = EditorInfo.IME_ACTION_NEXT

        teamNameInput.doAfterTextChanged {
            teamNameInputLayout.error = null
        }
        descriptionInput.doAfterTextChanged {
            descriptionInputLayout.error = null
        }

        fun setCreating(creating: Boolean) {
            createTeamButton.isEnabled = !creating
            teamNameInput.isEnabled = !creating
            descriptionInput.isEnabled = !creating
            createTeamButton.text = getString(
                if (creating) R.string.creating_team else R.string.create_team_button
            )
        }

        createTeamButton.setOnClickListener {
            // performClick() (tecla Enter) ignora isEnabled, então é preciso conferir.
            if (!createTeamButton.isEnabled) return@setOnClickListener
            val teamName = teamNameInput.text?.toString()?.trim().orEmpty()
            val description = descriptionInput.text?.toString()?.trim().orEmpty()

            teamNameInputLayout.error = when {
                teamName.isEmpty() -> getString(R.string.team_name_required_error)
                teamName.length > FirebaseTeamRepository.MAX_NAME_LENGTH -> getString(
                    R.string.team_name_too_long_error,
                    FirebaseTeamRepository.MAX_NAME_LENGTH
                )
                else -> null
            }
            descriptionInputLayout.error =
                if (description.length > FirebaseTeamRepository.MAX_DESCRIPTION_LENGTH) getString(
                    R.string.team_description_too_long_error,
                    FirebaseTeamRepository.MAX_DESCRIPTION_LENGTH
                ) else null
            if (teamNameInputLayout.error != null) {
                teamNameInput.requestFocus()
                return@setOnClickListener
            }
            if (descriptionInputLayout.error != null) {
                descriptionInput.requestFocus()
                return@setOnClickListener
            }

            setCreating(true)
            FirebaseTeamRepository.createTeam(teamName, description) { result ->
                result.onSuccess { team ->
                    if (isDestroyed) return@onSuccess
                    startActivity(
                        Intent(this, InviteTeamActivity::class.java).putExtra(Navigation.TEAM, team.id)
                    )
                    finish()
                }.onFailure { error ->
                    setCreating(false)
                    teamNameInputLayout.error = FirebaseTeamRepository.errorMessage(this, error)
                }
            }
        }

        teamNameInput.setOnEditorActionListener { _, actionId, event ->
            val enterPressed = event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN

            if (actionId == EditorInfo.IME_ACTION_NEXT || enterPressed) {
                descriptionInput.requestFocus()
                true
            } else {
                false
            }
        }
    }

    /**
     * Campo de descrição logo abaixo do nome. É criado aqui para servir tanto
     * a um cartão em ConstraintLayout quanto em LinearLayout.
     */
    private fun addDescriptionField(nameField: SyncroTextInput, button: View): SyncroTextInput {
        val field = SyncroTextInput(this).apply {
            id = R.id.teamDescriptionInputLayout
            hint = getString(R.string.screen_team_description)
            input.id = R.id.etNewTeamDescription
            input.isSingleLine = false
            input.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            input.minLines = 3
            input.maxLines = 6
            input.gravity = Gravity.TOP or Gravity.START
        }
        val margin = (16 * resources.displayMetrics.density).toInt()
        when (val card = nameField.parent) {
            is ConstraintLayout -> {
                card.addView(
                    field,
                    ConstraintLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                        endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                        topToBottom = nameField.id
                        topMargin = margin
                    }
                )
                (button.layoutParams as ConstraintLayout.LayoutParams).topToBottom = field.id
                button.requestLayout()
            }
            is LinearLayout -> card.addView(
                field,
                card.indexOfChild(nameField) + 1,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = margin }
            )
        }
        return field
    }
}
