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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_create_team)
        Navigation.toolbar(this)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.createTeamRoot)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val teamNameInputLayout = findViewById<SyncroTextInput>(R.id.teamNameInputLayout)
        val teamNameInput = teamNameInputLayout.input
        val createTeamButton = findViewById<Button>(R.id.btnCreateTeam)

        findViewById<android.view.View>(R.id.btnBack).setOnClickListener {
            finish()
        }

        teamNameInput.doAfterTextChanged {
            teamNameInputLayout.error = null
        }

        createTeamButton.setOnClickListener {
            val teamName = teamNameInput.text?.toString()?.trim().orEmpty()

            if (teamName.isEmpty()) {
                teamNameInputLayout.error = getString(R.string.team_name_required_error)
                teamNameInput.requestFocus()
                return@setOnClickListener
            }

            val team = Workspace.Team(name = teamName, tasks = mutableListOf(), members = mutableListOf(Workspace.Member("Você", "Criador(a)")))
            Workspace.teams.add(team)
            startActivity(Intent(this, InviteTeamActivity::class.java).putExtra(Navigation.TEAM, team.id))
            finish()
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
