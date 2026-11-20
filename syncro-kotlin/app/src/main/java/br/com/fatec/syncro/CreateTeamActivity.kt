package br.com.fatec.syncro

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.Button
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
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

        teamNameInput.doAfterTextChanged {
            teamNameInputLayout.error = null
        }

        fun setCreating(creating: Boolean) {
            createTeamButton.isEnabled = !creating
            teamNameInput.isEnabled = !creating
            createTeamButton.text = getString(
                if (creating) R.string.creating_team else R.string.create_team_button
            )
        }

        createTeamButton.setOnClickListener {
            // performClick() (tecla Enter) ignora isEnabled, então é preciso conferir.
            if (!createTeamButton.isEnabled) return@setOnClickListener
            val teamName = teamNameInput.text?.toString()?.trim().orEmpty()

            teamNameInputLayout.error = when {
                teamName.isEmpty() -> getString(R.string.team_name_required_error)
                teamName.length > FirebaseTeamRepository.MAX_NAME_LENGTH -> getString(
                    R.string.team_name_too_long_error,
                    FirebaseTeamRepository.MAX_NAME_LENGTH
                )
                else -> null
            }
            if (teamNameInputLayout.error != null) {
                teamNameInput.requestFocus()
                return@setOnClickListener
            }

            setCreating(true)
            FirebaseTeamRepository.createTeam(teamName) { result ->
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

            if (actionId == EditorInfo.IME_ACTION_DONE || enterPressed) {
                createTeamButton.performClick()
                true
            } else {
                false
            }
        }
    }
}
