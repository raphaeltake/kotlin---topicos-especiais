package br.com.fatec.syncro

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

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
            MaterialAlertDialogBuilder(this).setTitle("Perfil").setMessage("Conta de demonstração local")
                .setPositiveButton("Fechar", null).setNeutralButton("Sair") { _, _ -> Navigation.logout(this) }.show()
        }
        val first = findViewById<View>(R.id.teamItem1)
        val parent = first.parent as ViewGroup
        val index = parent.indexOfChild(first)
        listOf(R.id.teamItem1, R.id.teamItem2, R.id.teamItem3).forEach { parent.removeView(findViewById(it)) }
        Workspace.teams.forEachIndexed { offset, team ->
            val row = layoutInflater.inflate(R.layout.item_team, parent, false)
            row.findViewById<TextView>(R.id.tvTeamName).text = team.name
            row.findViewById<TextView>(R.id.tvTeamDescription).text = team.description
            row.findViewById<TextView>(R.id.tvMemberCount).text = "${team.members.size} membros"
            row.findViewById<TextView>(R.id.tvNewTaskCount).text = "${team.tasks.count { it.status != Workspace.Status.FINISHED }} tarefas ativas"
            row.isFocusable = true
            row.setOnClickListener { Navigation.open(this, "team", team.id) }
            parent.addView(row, index + offset)
        }
        if (Workspace.teams.isEmpty()) parent.addView(TextView(this).apply {
            text = "Você ainda não participa de nenhuma equipe."
            setPadding(16, 24, 16, 24)
        })
    }
}
