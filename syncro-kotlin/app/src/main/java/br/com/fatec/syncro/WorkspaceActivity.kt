package br.com.fatec.syncro

import android.content.Intent
import android.app.DatePickerDialog
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Navigation and local actions for the new mobile screens. */
open class WorkspaceActivity : AppCompatActivity() {
    protected open val initialScreen = "team"
    private val screen get() = intent.getStringExtra(Navigation.SCREEN) ?: initialScreen
    private val team get() = Workspace.team(intent.getStringExtra(Navigation.TEAM))
    private val task get() = team?.tasks?.firstOrNull { it.id == intent.getStringExtra(Navigation.TASK) }
    private var activeDialog: androidx.appcompat.app.AlertDialog? = null
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); enableEdgeToEdge() }
    override fun onResume() { super.onResume(); render() }

    private fun render() {
        val current = team
        if (screen in listOf("team", "task", "members", "history", "success") && current == null) { Navigation.home(this); return }
        if (screen == "task" && task == null) { finish(); return }
        val layout = when (screen) {
            "join" -> R.layout.activity_join_team
            "success" -> R.layout.activity_invite_success
            "notifications" -> R.layout.activity_notifications
            "members" -> R.layout.activity_manage_members
            "history" -> R.layout.activity_task_history
            "task" -> when (task!!.status) {
                Workspace.Status.FINISHED -> R.layout.task_details_finished
                Workspace.Status.IN_PROGRESS, Workspace.Status.PENDING ->
                    if (current!!.admin) R.layout.task_details else R.layout.task_details_pending_member
            }
            else -> if (current!!.admin) R.layout.team_details else R.layout.team_details_member
        }
        setContentView(layout)
        val root = findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        ViewCompat.requestApplyInsets(root)
        Navigation.toolbar(this, current?.id)
        when (screen) {
            "join" -> bindJoin()
            "success" -> click(R.id.btnContinue) { Navigation.open(this, "team", current!!.id); finish() }
            "notifications" -> bindNotifications()
            "members" -> bindMembers()
            "history" -> bindTasks(true)
            "task" -> bindTask()
            else -> bindTeam()
        }
    }
    private fun click(id: Int, action: () -> Unit) {
        findViewById<View>(id)?.apply { isClickable = true; isFocusable = true; setOnClickListener { action() } }
    }
    private fun text(id: Int, value: String) { findViewById<TextView>(id)?.text = value }
    private fun go(screen: String, taskId: String? = null) = Navigation.open(this, screen, team?.id, taskId)
    private fun message(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()
    private fun dialog(layout: Int, bind: (View, androidx.appcompat.app.AlertDialog) -> Unit) {
        activeDialog?.dismiss()
        val view = layoutInflater.inflate(layout, null)
        val dialog = MaterialAlertDialogBuilder(this).setView(view).create()
        activeDialog = dialog
        view.findViewById<View>(R.id.btnCancel)?.setOnClickListener { dialog.dismiss() }
        bind(view, dialog)
        dialog.show()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
    private fun confirm(layout: Int, button: Int, action: () -> Unit) = dialog(layout) { view, dialog ->
        view.findViewById<View>(button).setOnClickListener { dialog.dismiss(); action() }
    }
    private fun bindJoin() {
        val inputLayout = findViewById<br.com.fatec.syncro.ui.SyncroTextInput>(R.id.teamCodeInputLayout)
        val input = inputLayout.input
        input.id = R.id.etTeamCode
        click(R.id.btnJoinTeam) {
            val code = input.text.toString().trim()
            val match = Workspace.teams.firstOrNull { it.code.equals(code, true) }
            if (match == null) {
                input.error = if (code.isEmpty()) "Informe o código da equipe" else "Equipe não encontrada neste protótipo local"
                return@click
            }
            Navigation.open(this, "team", match.id); finish()
        }
        findViewById<EditText>(R.id.etTeamCode).setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { findViewById<View>(R.id.btnJoinTeam).performClick(); true } else false
        }
    }
    private fun bindNotifications() {
        val current = team ?: Workspace.teams.firstOrNull()
        (findViewById<View>(R.id.tvNotification5).parent as View).visibility = if (Workspace.invitationPending) View.VISIBLE else View.GONE
        listOf(R.id.tvNotification1, R.id.tvNotification2, R.id.tvNotification3, R.id.tvNotification4).forEachIndexed { index, id ->
            click(id) {
                if (current == null) { message("Nenhuma equipe disponível"); return@click }
                val firstTask = current.tasks.firstOrNull()
                if (index == 0 && firstTask != null) Navigation.open(this, "task", current.id, firstTask.id)
                else Navigation.open(this, if (current.admin && index in 1..2) "members" else "team", current.id)
            }
        }
        click(R.id.btnAcceptInvite) {
            Workspace.invitationPending = false
            val joined = Workspace.Team(name = "Equipe convidada", admin = false)
            Workspace.teams.add(joined); Navigation.open(this, "team", joined.id); finish()
        }
        click(R.id.btnDeclineInvite) {
            Workspace.invitationPending = false
            (findViewById<View>(R.id.tvNotification5).parent as View).visibility = View.GONE
            message("Convite recusado localmente")
        }
    }
    private fun bindTeam() {
        val current = team!!
        text(R.id.tvTeamName, current.name); text(R.id.tvTeamDescription, current.description)
        text(R.id.tvMemberCountBadge, "membros: ${current.members.size}"); text(R.id.tvMemberCount, "membros: ${current.members.size}")
        text(R.id.tvTasksTabCount, current.tasks.count { it.status != Workspace.Status.FINISHED }.toString())
        text(R.id.tvMembersTabCount, current.members.size.toString())
        updateTeamCounters()
        click(R.id.btnEditTeam) { editTeam() }
        click(R.id.membersTab) {
            current.hasNewMembers = false
            updateTeamCounters()
            showTeamPanel(showMembers = true)
        }
        click(R.id.tasksTab) {
            current.hasNewTasks = false
            updateTeamCounters()
            showTeamPanel(showMembers = false)
        }
        click(R.id.btnManageMembers) { go("members") }
        click(R.id.btnTaskHistory) { go("history") }
        click(R.id.btnAddTask) { editTask(null) }
        click(R.id.btnLeaveTeam) {
            confirm(R.layout.dialog_leave_team, R.id.btnConfirmLeaveTeam) { Workspace.teams.remove(team); Navigation.home(this) }
        }
        bindTasks(false)
        bindInlineMembers()
        showTeamPanel(showMembers = false)
        if (!current.admin) bindMemberRows(false)
    }

    private fun bindInlineMembers() {
        val list = findViewById<LinearLayout>(R.id.membersList) ?: return
        list.removeAllViews()
        team!!.members.forEach { member ->
            val card = layoutInflater.inflate(R.layout.item_member_card, list, false)
            card.findViewById<TextView>(R.id.tvMemberCardName).text = member.name
            card.findViewById<TextView>(R.id.tvMemberCardRole).text = "Papel: ${member.role}"
            list.addView(card)
        }
    }

    private fun showTeamPanel(showMembers: Boolean) {
        text(R.id.tvTeamSectionTitle, if (showMembers) "Membros" else "Tarefas")
        findViewById<View>(R.id.membersPanel)?.visibility = if (showMembers) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btnAddTask)?.visibility = if (showMembers) View.GONE else View.VISIBLE
        findViewById<View>(R.id.btnTaskHistory)?.visibility = if (showMembers) View.GONE else View.VISIBLE
        val taskList = findViewById<View>(R.id.btnAddTask)?.parent as? ViewGroup
        if (taskList != null) {
            for (index in 0 until taskList.childCount) {
                val child = taskList.getChildAt(index)
                if (child.tag == "task_item") child.visibility = if (showMembers) View.GONE else View.VISIBLE
            }
        }
        findViewById<TextView>(R.id.tvTasksTabLabel)?.setTextColor(androidx.core.content.ContextCompat.getColor(this, if (showMembers) R.color.black else R.color.blue))
        findViewById<TextView>(R.id.tvMembersTabLabel)?.setTextColor(androidx.core.content.ContextCompat.getColor(this, if (showMembers) R.color.blue else R.color.black))
        findViewById<TextView>(R.id.tvTasksTabCount)?.setTextColor(androidx.core.content.ContextCompat.getColor(this, if (showMembers) R.color.black else R.color.blue))
        findViewById<TextView>(R.id.tvMembersTabCount)?.setTextColor(androidx.core.content.ContextCompat.getColor(this, if (showMembers) R.color.blue else R.color.black))
        findViewById<View>(R.id.tasksTab)?.setBackgroundResource(if (showMembers) android.R.color.transparent else R.drawable.bg_card)
        findViewById<View>(R.id.membersTab)?.setBackgroundResource(if (showMembers) R.drawable.bg_card else android.R.color.transparent)
    }

    private fun updateTeamCounters() {
        val current = team ?: return
        updateCounterBadge(R.id.tvTasksTabCount, current.hasNewTasks)
        updateCounterBadge(R.id.tvMembersTabCount, current.hasNewMembers)
    }

    private fun updateCounterBadge(id: Int, hasNewContent: Boolean) {
        findViewById<TextView>(id)?.apply {
            setBackgroundResource(if (hasNewContent) R.drawable.bg_counter_new else android.R.color.transparent)
        }
    }
    private fun editTeam() = dialog(R.layout.dialog_edit_team) { view, dialog ->
        val current = team!!
        val name = view.findViewById<EditText>(R.id.etTeamName).apply { setText(current.name) }
        val description = view.findViewById<EditText>(R.id.etTeamDescription).apply { setText(current.description) }
        view.findViewById<View>(R.id.btnSaveTeam).setOnClickListener {
            if (name.text.isBlank()) { name.error = "Informe o nome da equipe"; return@setOnClickListener }
            current.name = name.text.toString().trim(); current.description = description.text.toString().trim()
            dialog.dismiss(); render()
        }
    }
    private fun bindTasks(history: Boolean) {
        val adminLayout = !history && team!!.admin
        val ids = if (adminLayout) listOf(R.id.taskItem1, R.id.taskItem2, R.id.taskItem3) else listOf(R.id.taskItem0, R.id.taskItem1, R.id.taskItem2)
        val template = findViewById<View>(ids.first())
        val parent = template.parent as ViewGroup
        val insertion = parent.indexOfChild(template)
        ids.forEach { id -> parent.removeView(findViewById(id)) }
        val tasks = team!!.tasks.filter { (it.status == Workspace.Status.FINISHED) == history }
        tasks.forEachIndexed { index, item ->
            val row = layoutInflater.inflate(R.layout.item_flow_task, parent, false)
            row.tag = "task_item"
            row.findViewById<TextView>(R.id.tvTaskTitle).text = item.title
            row.findViewById<TextView>(R.id.tvResponsibles).text = item.responsibles
            row.findViewById<TextView>(R.id.tvDueDate).text = item.date
            row.findViewById<TextView>(R.id.tvStatusBadge).text = when (item.status) {
                Workspace.Status.PENDING -> "Pendente"
                Workspace.Status.IN_PROGRESS -> "Em andamento"
                Workspace.Status.FINISHED -> "Finalizada"
            }
            row.setBackgroundResource(when (item.status) {
                Workspace.Status.PENDING -> R.drawable.bg_task_card_green
                Workspace.Status.IN_PROGRESS -> R.drawable.bg_task_card_yellow
                Workspace.Status.FINISHED -> R.drawable.bg_card
            })
            row.findViewById<TextView>(R.id.tvStatusBadge).backgroundTintList =
                androidx.core.content.ContextCompat.getColorStateList(this, when (item.status) {
                    Workspace.Status.PENDING -> R.color.status_green_bg
                    Workspace.Status.IN_PROGRESS -> R.color.status_yellow_bg
                    Workspace.Status.FINISHED -> R.color.status_blue_bg
                })
            row.findViewById<TextView>(R.id.tvStatusBadge).setTextColor(androidx.core.content.ContextCompat.getColor(this, when (item.status) {
                Workspace.Status.PENDING -> R.color.status_green
                Workspace.Status.IN_PROGRESS -> R.color.status_yellow
                Workspace.Status.FINISHED -> R.color.blue
            }))
            row.setOnClickListener { go("task", item.id) }; parent.addView(row, insertion + index)
        }
        if (tasks.isEmpty()) parent.addView(TextView(this).apply {
            text = if (history) "Nenhuma tarefa finalizada" else "Nenhuma tarefa ativa"
            setPadding(0, 24, 0, 24)
        }, insertion)
    }
    private fun bindTask() {
        val item = task!!
        val statusBadge = findViewById<TextView>(R.id.tvStatusBadge)
        statusBadge.text = getString(when (item.status) {
            Workspace.Status.PENDING -> R.string.screen_pending
            Workspace.Status.IN_PROGRESS -> R.string.screen_in_progress
            Workspace.Status.FINISHED -> R.string.screen_finished
        })
        statusBadge.setTextColor(androidx.core.content.ContextCompat.getColor(this, when (item.status) {
            Workspace.Status.PENDING -> R.color.status_green
            Workspace.Status.IN_PROGRESS -> R.color.status_yellow
            Workspace.Status.FINISHED -> R.color.blue
        }))
        statusBadge.backgroundTintList = androidx.core.content.ContextCompat.getColorStateList(this, when (item.status) {
            Workspace.Status.PENDING -> R.color.status_green_bg
            Workspace.Status.IN_PROGRESS -> R.color.status_yellow_bg
            Workspace.Status.FINISHED -> R.color.status_blue_bg
        })
        if (item.status != Workspace.Status.FINISHED) {
            val label = getString(if (item.status == Workspace.Status.PENDING) R.string.screen_start_task else R.string.screen_finish_task)
            val action = findViewById<View>(R.id.btnTaskAction)
            if (action is TextView) action.text = label else text(R.id.tvTaskActionLabel, label)
        }
        text(R.id.tvTaskTitle, item.title); text(R.id.tvDescription, item.description)
        text(R.id.tvResponsibles, item.responsibles); text(R.id.tvDueDate, item.date)
        click(R.id.btnTaskAction) { item.advance(); render() }
        click(R.id.btnDeleteTask) { confirm(R.layout.dialog_delete_task, R.id.btnConfirmDeleteTask) { team!!.tasks.remove(item); finish() } }
        click(R.id.btnEditTask) { editTask(item) }
        click(R.id.btnComment) { editComment(null) }
        bindComments()
    }
    private fun editTask(item: Workspace.Task?) = dialog(if (item == null) R.layout.dialog_add_task else R.layout.dialog_edit_task) { view, dialog ->
        val title = view.findViewById<EditText>(R.id.etTaskTitle).apply { setText(item?.title.orEmpty()) }
        val description = view.findViewById<EditText>(R.id.etDescription).apply { setText(item?.description.orEmpty()) }
        val responsible = view.findViewById<EditText>(R.id.etResponsibles).apply { setText(item?.responsibles.orEmpty()) }
        val date = view.findViewById<EditText>(R.id.etDueDate).apply {
            setText(item?.date.orEmpty())
            isFocusable = false
            isClickable = true
            setOnClickListener { showDueDatePicker(this) }
        }
        view.findViewById<View>(if (item == null) R.id.btnAddTask else R.id.btnSaveTask).setOnClickListener {
            if (title.text.isBlank()) { title.error = "Informe o título"; return@setOnClickListener }
            if (description.text.isBlank()) { description.error = "Informe a descrição"; return@setOnClickListener }
            val target = item ?: Workspace.Task(comments = mutableListOf())
            target.title = title.text.toString().trim(); target.description = description.text.toString().trim()
            target.responsibles = responsible.text.toString().trim(); target.date = date.text.toString().trim()
            if (item == null) {
                team!!.tasks.add(target)
                team!!.hasNewTasks = true
            }
            dialog.dismiss()
            if (item == null) go("task", target.id) else render()
        }
    }

    private fun showDueDatePicker(field: EditText) {
        val calendar = Calendar.getInstance()
        val formatter = SimpleDateFormat("dd/MM/yyyy", Locale.forLanguageTag("pt-BR"))
        runCatching { formatter.parse(field.text.toString()) }.getOrNull()?.let { calendar.time = it }
        DatePickerDialog(
            this,
            { _, year, month, day ->
                calendar.set(year, month, day)
                field.setText(formatter.format(calendar.time))
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }
    private fun bindComments() {
        val names = listOf(R.id.tvCommenterName, R.id.tvCommenterName1, R.id.tvCommenterName2)
        val blocks = names.mapNotNull { findViewById<View>(it)?.parent as? ViewGroup }
            .map { if (it is LinearLayout && it.orientation == LinearLayout.HORIZONTAL) it.parent as ViewGroup else it }
        val parent = blocks.first().parent as ViewGroup
        val insertion = parent.indexOfChild(blocks.first())
        blocks.forEach(parent::removeView)
        task!!.comments.forEachIndexed { index, comment ->
            val row = layoutInflater.inflate(R.layout.item_flow_comment, parent, false)
            row.findViewById<TextView>(R.id.tvCommentText1).text = comment.text
            val edited = row.findViewById<TextView>(R.id.btnCommentHistory)
            edited.visibility = if (comment.history.isEmpty()) View.GONE else View.VISIBLE
            edited.setOnClickListener { commentHistory(comment) }
            val edit = row.findViewById<View>(R.id.btnEditComment1)
            edit.visibility = if (task!!.status == Workspace.Status.FINISHED) View.GONE else View.VISIBLE
            edit.setOnClickListener {
                if (team!!.admin) {
                    PopupMenu(this, edit).apply {
                        menu.add("Editar comentário").setOnMenuItemClickListener { editComment(comment); true }
                        menu.add("Remover comentário").setOnMenuItemClickListener {
                            confirm(R.layout.dialog_remove_comment, R.id.btnConfirmRemoveComment) { task!!.comments.remove(comment); render() }
                            true
                        }
                        show()
                    }
                } else editComment(comment)
            }
            if (team!!.admin && task!!.status != Workspace.Status.FINISHED) row.setOnLongClickListener {
                confirm(R.layout.dialog_remove_comment, R.id.btnConfirmRemoveComment) { task!!.comments.remove(comment); render() }; true
            }
            parent.addView(row, insertion + index)
        }
    }
    private fun editComment(comment: Workspace.Comment?) = dialog(if (comment == null) R.layout.dialog_create_comment else R.layout.dialog_edit_comment) { view, dialog ->
        val input = view.findViewById<EditText>(R.id.etComment).apply { setText(comment?.text.orEmpty()) }
        view.findViewById<View>(if (comment == null) R.id.btnSubmitComment else R.id.btnSaveComment).setOnClickListener {
            if (input.text.isBlank()) { input.error = "Escreva um comentário"; return@setOnClickListener }
            if (comment == null) task!!.comments.add(Workspace.Comment(input.text.toString()))
            else if (comment.text != input.text.toString()) { comment.history.add(comment.text); comment.text = input.text.toString() }
            dialog.dismiss(); render()
        }
        view.findViewById<View>(R.id.btnDeleteComment)?.setOnClickListener {
            confirm(R.layout.dialog_delete_comment, R.id.btnConfirmDeleteComment) { task!!.comments.remove(comment); render() }
        }
    }
    private fun commentHistory(comment: Workspace.Comment) = dialog(R.layout.dialog_comment_history) { view, dialog ->
        view.findViewById<TextView>(R.id.tvPreviousComment).text = comment.history.joinToString("\n\n")
        view.findViewById<TextView>(R.id.tvCurrentComment).text = comment.text
        view.findViewById<View>(R.id.btnCloseHistory).setOnClickListener { dialog.dismiss() }
    }
    private fun bindMembers() {
        if (!team!!.admin) { finish(); return }
        click(R.id.btnAddMember) { startActivity(Intent(this, InviteTeamActivity::class.java).putExtra(Navigation.TEAM, team!!.id)) }
        click(R.id.btnDeleteTeam) { confirm(R.layout.dialog_delete_team, R.id.btnConfirmDeleteTeam) { Workspace.teams.remove(team); Navigation.home(this) } }
        bindMemberRows(true)
    }
    private fun bindMemberRows(manage: Boolean) {
        val ids = listOf(R.id.tvMemberName0, R.id.tvMemberName1, R.id.tvMemberName2)
        ids.forEachIndexed { index, id ->
            val label = findViewById<TextView>(id)
            val container = label.parent.parent as View
            val member = team!!.members.getOrNull(index)
            container.visibility = if (member == null) View.GONE else View.VISIBLE
            if (member == null) return@forEachIndexed
            label.text = member.name
            text(listOf(R.id.tvMemberRole0, R.id.tvMemberRole1, R.id.tvMemberRole2)[index], "Papel: ${member.role}")
            if (manage) {
                click(listOf(R.id.btnRemoveMember0, R.id.btnRemoveMember1, R.id.btnRemoveMember2)[index]) {
                    dialog(R.layout.dialog_remove_member) { view, dialog ->
                        view.findViewById<TextView>(R.id.tvMemberName).text = member.name
                        view.findViewById<View>(R.id.btnConfirmRemoveMember).setOnClickListener {
                            team!!.members.remove(member); dialog.dismiss(); render()
                            confirm(R.layout.dialog_member_removed, R.id.btnOk) { }
                        }
                    }
                }
                val spinnerId = listOf(0, R.id.spinnerMemberRole1, R.id.spinnerMemberRole2)[index]
                if (spinnerId != 0) {
                    val spinner = findViewById<Spinner>(spinnerId)
                    val options = (0 until spinner.count).map { spinner.getItemAtPosition(it).toString() }
                    spinner.setSelection(options.indexOf(member.role).coerceAtLeast(0))
                    spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { member.role = options[position] }
                    }
                }
            }
        }
    }
    override fun onDestroy() { activeDialog?.dismiss(); super.onDestroy() }
}
