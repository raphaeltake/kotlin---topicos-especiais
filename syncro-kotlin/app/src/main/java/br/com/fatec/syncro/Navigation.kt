package br.com.fatec.syncro

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object Navigation {
    const val SCREEN = "screen"
    const val TEAM = "team"
    const val TASK = "task"
    /** Quando verdadeiro, a tela volta para quem a abriu em vez de abrir a equipe novamente. */
    const val RETURN_TO_CALLER = "return_to_caller"

    fun open(
        activity: AppCompatActivity,
        screen: String,
        team: String? = null,
        task: String? = null,
        returnToCaller: Boolean = false
    ) {
        val target = when (screen) {
            "team" -> TeamDetailsActivity::class.java
            "task" -> TaskDetailsActivity::class.java
            else -> WorkspaceActivity::class.java
        }
        activity.startActivity(
            Intent(activity, target)
                .putExtra(SCREEN, screen)
                .putExtra(TEAM, team)
                .putExtra(TASK, task)
                .putExtra(RETURN_TO_CALLER, returnToCaller)
        )
    }
    fun home(activity: AppCompatActivity) {
        activity.startActivity(Intent(activity, TeamsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        activity.finish()
    }
    fun toolbar(activity: AppCompatActivity, team: String? = null) {
        activity.findViewById<View>(R.id.btnBack)?.setOnClickListener { activity.finish() }
        listOf(R.id.btnNotifications, R.id.btnNotificationsTop).forEach { id ->
            activity.findViewById<android.widget.ImageButton>(id)?.apply {
                imageTintList = null
                setImageResource(if (Workspace.hasUnreadNotifications) R.drawable.ic_notifications_unread else R.drawable.ic_notifications)
                contentDescription = if (Workspace.hasUnreadNotifications) "Notificações não lidas" else "Notificações"
                setOnClickListener { open(activity, "notifications", team) }
            }
        }
    }

    /**
     * Posição de rolagem da tela. As telas são redesenhadas a cada alteração
     * recebida em tempo real, e a posição é restaurada com [restoreScroll].
     */
    fun scrollPosition(activity: Activity): Int? =
        scrollView(activity.findViewById(android.R.id.content))?.scrollY

    fun restoreScroll(activity: Activity, position: Int?) {
        if (position == null || position == 0) return
        scrollView(activity.findViewById(android.R.id.content))?.let { view ->
            view.post { view.scrollTo(0, position) }
        }
    }

    private fun scrollView(view: View?): View? = when (view) {
        is ScrollView, is NestedScrollView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { scrollView(view.getChildAt(it)) }
        else -> null
    }

    /** Texto exibido para a descrição da equipe, com alternativa quando estiver vazia. */
    fun descriptionOrDefault(context: Context, description: String) =
        description.ifBlank { context.getString(R.string.screen_no_description) }

    fun membersCount(context: Context, count: Int) = quantity(
        count,
        context.getString(R.string.screen_member),
        context.getString(R.string.screen_members_plural)
    )

    fun activeTasksCount(context: Context, count: Int) = quantity(
        count,
        context.getString(R.string.screen_active_task),
        context.getString(R.string.screen_active_tasks)
    )

    private fun quantity(count: Int, singular: String, plural: String) =
        "$count ${if (count == 1) singular else plural}"

    /** Até duas iniciais do nome, exibidas nos avatares de usuários e equipes. */
    fun initials(name: String): String {
        val words = name.trim().split(Regex("\\s+")).filter { it.firstOrNull()?.isLetterOrDigit() == true }
        return when (words.size) {
            0 -> "?"
            1 -> words.first().take(1).uppercase()
            else -> (words.first().take(1) + words.last().take(1)).uppercase()
        }
    }

    fun teamDescription(activity: AppCompatActivity, label: TextView, description: String) {
        val text = descriptionOrDefault(activity, description)
        label.text = text
        label.maxLines = 5
        label.ellipsize = android.text.TextUtils.TruncateAt.END
        label.isFocusable = true
        label.setOnClickListener {
            val view = activity.layoutInflater.inflate(R.layout.dialog_team_description, null)
            val dialog = MaterialAlertDialogBuilder(activity).setView(view).create()
            view.findViewById<TextView>(R.id.tvDescription).text = text
            view.findViewById<View>(R.id.btnClose).setOnClickListener { dialog.dismiss() }
            dialog.show()
        }
    }
    fun logout(activity: AppCompatActivity) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_logout, null)
        val dialog = MaterialAlertDialogBuilder(activity).setView(view).create()
        view.findViewById<View>(R.id.btnCancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<View>(R.id.btnConfirmLogout).setOnClickListener { button ->
            button.isEnabled = false
            dialog.setCancelable(false)
            FirebaseAccountRepository.signOut {
                if (activity.isDestroyed) return@signOut
                dialog.dismiss()
                activity.startActivity(Intent(activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            }
        }
        dialog.show()
    }
}
