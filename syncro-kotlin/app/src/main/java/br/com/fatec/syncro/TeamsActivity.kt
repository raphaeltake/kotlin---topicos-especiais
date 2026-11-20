package br.com.fatec.syncro

import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class TeamsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
    }

    override fun onResume() {
        super.onResume()
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
        findViewById<TextView>(R.id.tvGreeting).text =
            getString(R.string.home_greeting, Workspace.profile.name.trim().substringBefore(" "))
        findViewById<TextView>(R.id.tvTeamsCount).text = Workspace.teams.size.toString()
        val first = findViewById<View>(R.id.teamItem1)
        val parent = first.parent as ViewGroup
        val index = parent.indexOfChild(first)
        listOf(R.id.teamItem1, R.id.teamItem2, R.id.teamItem3).forEach { parent.removeView(findViewById(it)) }
        Workspace.teams.forEachIndexed { offset, team ->
            val row = layoutInflater.inflate(R.layout.item_team, parent, false)
            row.findViewById<TextView>(R.id.tvTeamName).text = team.name
            row.findViewById<TextView>(R.id.tvTeamInitial).text = Navigation.initials(team.name)
            row.findViewById<TextView>(R.id.tvTeamRole).apply {
                text = getString(if (team.admin) R.string.role_admin_short else R.string.role_member_short)
                setBackgroundResource(if (team.admin) R.drawable.bg_chip_blue else R.drawable.bg_chip)
                setTextColor(ContextCompat.getColor(context, if (team.admin) R.color.blue_dark else R.color.gray))
            }
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
        if (Workspace.teams.isEmpty()) parent.addView(emptyState(this, getString(R.string.screen_no_teams)))
    }
}

/** Estado de lista vazia (ícone e mensagem) com o mesmo estilo em todas as telas. */
internal fun emptyState(context: android.content.Context, message: String): View =
    android.view.LayoutInflater.from(context).inflate(R.layout.item_empty_state, null, false).apply {
        findViewById<TextView>(R.id.tvEmptyMessage).text = message
    }
