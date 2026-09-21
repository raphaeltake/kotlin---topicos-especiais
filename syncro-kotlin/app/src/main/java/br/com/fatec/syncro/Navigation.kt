package br.com.fatec.syncro

import android.content.Intent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object Navigation {
    const val SCREEN = "screen"
    const val TEAM = "team"
    const val TASK = "task"
    fun open(activity: AppCompatActivity, screen: String, team: String? = null, task: String? = null) {
        val target = when (screen) {
            "team" -> TeamDetailsActivity::class.java
            "task" -> TaskDetailsActivity::class.java
            else -> WorkspaceActivity::class.java
        }
        activity.startActivity(Intent(activity, target).putExtra(SCREEN, screen).putExtra(TEAM, team).putExtra(TASK, task))
    }
    fun home(activity: AppCompatActivity) {
        activity.startActivity(Intent(activity, TeamsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        activity.finish()
    }
    fun toolbar(activity: AppCompatActivity, team: String? = null) {
        activity.findViewById<View>(R.id.btnBack)?.setOnClickListener { activity.finish() }
        listOf(R.id.btnNotifications, R.id.btnNotificationsTop).forEach { id ->
            activity.findViewById<View>(id)?.setOnClickListener { open(activity, "notifications", team) }
        }
    }
    fun logout(activity: AppCompatActivity) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_logout, null)
        val dialog = MaterialAlertDialogBuilder(activity).setView(view).create()
        view.findViewById<View>(R.id.btnCancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<View>(R.id.btnConfirmLogout).setOnClickListener {
            dialog.dismiss()
            activity.startActivity(Intent(activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
        dialog.show()
    }
}
