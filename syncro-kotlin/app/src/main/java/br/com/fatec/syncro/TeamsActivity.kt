package br.com.fatec.syncro

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class TeamsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_teams)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.teamsRoot)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        findViewById<View>(R.id.btnCreateTeam).setOnClickListener {
            startActivity(Intent(this, InviteTeamActivity::class.java))
        }

        findViewById<View>(R.id.btnJoinTeam).setOnClickListener {
            openTeamDetails()
        }

        listOf(R.id.teamItem1, R.id.teamItem2, R.id.teamItem3).forEach { teamId ->
            findViewById<View>(teamId).setOnClickListener {
                openTeamDetails()
            }
        }

        findViewById<View>(R.id.btnLogout).setOnClickListener {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
            )
        }
    }

    private fun openTeamDetails() {
        startActivity(Intent(this, TeamDetailsActivity::class.java))
    }
}
