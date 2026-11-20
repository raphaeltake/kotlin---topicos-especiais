package br.com.fatec.syncro

import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class TeamsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
    }

    override fun onResume() {
        super.onResume()
        render()
        load()
    }

    /** Mostra o que já está em memória e atualiza com o Firestore em seguida. */
    private fun load() {
        FirebaseTeamRepository.loadTeams { result ->
            if (isDestroyed) return@loadTeams
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) render()
            // Em caso de falha, informa o erro e permite nova tentativa.
            result.onFailure { error ->
                com.google.android.material.snackbar.Snackbar
                    .make(
                        findViewById(android.R.id.content),
                        FirebaseTeamRepository.errorMessage(this, error),
                        com.google.android.material.snackbar.Snackbar.LENGTH_INDEFINITE
                    )
                    .setAction(R.string.retry) { load() }
                    .show()
            }
        }
    }

    private fun render() {
        setContentView(R.layout.activity_teams)
        val root = findViewById<View>(R.id.teamsRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        Navigation.toolbar(this)
        findViewById<View>(R.id.btnCreateTeam).setOnClickListener { startActivity(Intent(this, CreateTeamActivity::class.java)) }
        findViewById<View>(R.id.btnJoinTeam).setOnClickListener { Navigation.open(this, "join") }
        findViewById<View>(R.id.btnLogout).setOnClickListener { Navigation.logout(this) }
        findViewById<View>(R.id.btnProfile).setOnClickListener {
            Navigation.open(this, "profile")
        }
        val first = findViewById<View>(R.id.teamItem1)
        val parent = first.parent as ViewGroup
        val index = parent.indexOfChild(first)
        listOf(R.id.teamItem1, R.id.teamItem2, R.id.teamItem3).forEach { parent.removeView(findViewById(it)) }
        Workspace.teams.forEachIndexed { offset, team ->
            val row = layoutInflater.inflate(R.layout.item_team, parent, false)
            row.findViewById<TextView>(R.id.tvTeamName).text = team.name
            row.findViewById<TextView>(R.id.tvTeamDescription).apply {
                text = Navigation.descriptionOrDefault(this@TeamsActivity, team.description)
                maxLines = 5
                ellipsize = TextUtils.TruncateAt.END
                isClickable = false
                isFocusable = false
            }
            row.findViewById<TextView>(R.id.tvMemberCount).text =
                Navigation.membersCount(this, team.members.size)
            row.findViewById<TextView>(R.id.tvNewTaskCount).text =
                Navigation.activeTasksCount(this, team.tasks.count { it.status != Workspace.Status.FINISHED })
            row.setOnClickListener { Navigation.open(this, "team", team.id) }
            parent.addView(row, index + offset)
        }
        if (Workspace.teams.isEmpty() && Workspace.teamsLoaded) parent.addView(emptyState(this, getString(R.string.screen_no_teams)))
    }
}

/** Mensagem de lista vazia com o mesmo estilo de texto usado nas telas. */
internal fun emptyState(context: android.content.Context, message: String) = TextView(context).apply {
    val padding = (16 * context.resources.displayMetrics.density).toInt()
    text = message
    setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.gray))
    textSize = 14f
    setPadding(0, padding, 0, padding)
}
