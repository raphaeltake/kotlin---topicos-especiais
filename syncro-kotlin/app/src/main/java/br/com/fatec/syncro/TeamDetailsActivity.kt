package br.com.fatec.syncro

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class TeamDetailsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.team_details)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.teamDetailsRoot)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        findViewById<View>(R.id.btnBack).setOnClickListener {
            finish()
        }

        findViewById<View>(R.id.membersTab).setOnClickListener {
            startActivity(Intent(this, InviteTeamActivity::class.java))
        }

        findViewById<View>(R.id.btnAddTask).setOnClickListener {
            openTaskDetails()
        }

        listOf(R.id.taskItem1, R.id.taskItem2, R.id.taskItem3).forEach { taskId ->
            findViewById<View>(taskId).setOnClickListener {
                openTaskDetails()
            }
        }

        findViewById<View>(R.id.btnLeaveTeam).setOnClickListener {
            finish()
        }
    }

    private fun openTaskDetails() {
        startActivity(Intent(this, TaskDetailsActivity::class.java))
    }
}
