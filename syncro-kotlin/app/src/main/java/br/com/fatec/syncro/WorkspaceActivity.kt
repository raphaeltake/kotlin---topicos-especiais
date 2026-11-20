package br.com.fatec.syncro

import android.content.Intent
import android.app.DatePickerDialog
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import java.time.LocalDate
import java.time.ZoneId

/** Navigation and local actions for the new mobile screens. */
open class WorkspaceActivity : AppCompatActivity() {
    protected open val initialScreen = "team"
    private val screen get() = intent.getStringExtra(Navigation.SCREEN) ?: initialScreen
    private val team get() = Workspace.team(intent.getStringExtra(Navigation.TEAM))
    private val task get() = team?.tasks?.firstOrNull { it.id == intent.getStringExtra(Navigation.TASK) }
    private val returnToCaller get() = intent.getBooleanExtra(Navigation.RETURN_TO_CALLER, false)
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
            "profile" -> R.layout.activity_profile
            "task" -> R.layout.task_details
            else -> R.layout.team_details
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
            "success" -> click(R.id.btnContinue) {
                // Convite feito a partir de "Gerenciar membros": volta para lá.
                if (!returnToCaller) Navigation.open(this, "team", current!!.id)
                finish()
            }
            "notifications" -> bindNotifications()
            "members" -> bindMembers()
            "history" -> bindTasks(true)
            "task" -> bindTask()
            "profile" -> bindProfile()
            else -> bindTeam()
        }
    }
    private fun click(id: Int, action: () -> Unit) {
        findViewById<View>(id)?.apply { isClickable = true; isFocusable = true; setOnClickListener { action() } }
    }
    private fun text(id: Int, value: String) { findViewById<TextView>(id)?.text = value }
    private fun go(screen: String, taskId: String? = null) = Navigation.open(this, screen, team?.id, taskId)
    private fun message(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()
    private fun color(id: Int) = ContextCompat.getColor(this, id)
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

    /**
     * Exibe o erro no TextInputLayout do campo (mesmo padrão das telas de login,
     * cadastro e perfil) em vez do balão padrão do EditText.
     */
    private fun EditText.showError(message: String?) {
        var parent = parent
        while (parent != null && parent !is TextInputLayout) parent = parent.parent
        val layout = parent as? TextInputLayout
        if (layout != null) layout.error = message else error = message
    }

    private fun EditText.clearErrorOnChange() = doAfterTextChanged { showError(null) }

    /** Cores, fundo do card e rótulo de status usados igualmente na lista e nos detalhes. */
    private data class StatusStyle(val label: String, val textColor: Int, val badgeColor: Int, val card: Int)

    private fun statusStyle(item: Workspace.Task): StatusStyle {
        val finished = item.status == Workspace.Status.FINISHED
        val overdue = !finished && DueDates.isOverdue(item.date)
        val urgent = overdue || (!finished && DueDates.isDueSoon(item.date))
        val label = getString(when {
            overdue -> R.string.screen_overdue
            item.status == Workspace.Status.PENDING -> R.string.screen_pending
            item.status == Workspace.Status.IN_PROGRESS -> R.string.screen_in_progress
            else -> R.string.screen_finished
        })
        return when {
            urgent -> StatusStyle(label, R.color.status_red, R.color.status_red_bg, R.drawable.bg_task_card_red)
            item.status == Workspace.Status.PENDING ->
                StatusStyle(label, R.color.status_green, R.color.status_green_bg, R.drawable.bg_task_card_green)
            item.status == Workspace.Status.IN_PROGRESS ->
                StatusStyle(label, R.color.status_yellow, R.color.status_yellow_bg, R.drawable.bg_task_card_yellow)
            else -> StatusStyle(label, R.color.blue, R.color.status_blue_bg, R.drawable.bg_task_card_blue)
        }
    }

    private fun applyStatus(badge: TextView, style: StatusStyle) {
        badge.text = style.label
        badge.setTextColor(color(style.textColor))
        badge.backgroundTintList = ContextCompat.getColorStateList(this, style.badgeColor)
    }

    private fun responsiblesText(item: Workspace.Task) =
        item.responsibles.ifBlank { getString(R.string.screen_no_responsibles) }

    private fun bindJoin() {
        val inputLayout = findViewById<br.com.fatec.syncro.ui.SyncroTextInput>(R.id.teamCodeInputLayout)
        val input = inputLayout.input
        input.id = R.id.etTeamCode
        input.doAfterTextChanged { inputLayout.error = null }
        click(R.id.btnJoinTeam) {
            val code = input.text.toString().trim()
            val match = Workspace.teams.firstOrNull { it.code.equals(code, true) }
            if (match == null) {
                inputLayout.error = getString(if (code.isEmpty()) R.string.screen_team_code_required else R.string.screen_team_not_found)
                input.requestFocus()
                return@click
            }
            Navigation.open(this, "team", match.id); finish()
        }
        findViewById<EditText>(R.id.etTeamCode).setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { findViewById<View>(R.id.btnJoinTeam).performClick(); true } else false
        }
    }
    private fun bindNotifications() {
        Workspace.hasUnreadNotifications = false
        Navigation.toolbar(this, team?.id)
        val current = team ?: Workspace.teams.firstOrNull()
        val teamNotifications = listOf(R.id.tvNotification1, R.id.tvNotification2, R.id.tvNotification3, R.id.tvNotification4)
        if (current == null) {
            teamNotifications.forEach { (findViewById<View>(it).parent as View).visibility = View.GONE }
        } else {
            val newestMember = current.members.lastOrNull()?.name ?: getString(R.string.screen_member_name)
            text(R.id.tvNotification1, getString(R.string.screen_notification_new_task, current.name))
            text(R.id.tvNotification2, getString(R.string.screen_notification_member_joined, newestMember, current.name))
            text(R.id.tvNotification3, getString(R.string.screen_notification_join_request, current.name))
            text(R.id.tvNotification4, getString(R.string.screen_notification_invite_accepted, current.name))
        }
        text(R.id.tvNotification5, getString(R.string.screen_notification_invited, getString(R.string.screen_invited_team_name)))
        (findViewById<View>(R.id.tvNotification5).parent as View).visibility = if (Workspace.invitationPending) View.VISIBLE else View.GONE
        teamNotifications.forEachIndexed { index, id ->
            click(id) {
                if (current == null) { message(getString(R.string.screen_no_team_available)); return@click }
                val firstTask = current.tasks.firstOrNull()
                if (index == 0 && firstTask != null) Navigation.open(this, "task", current.id, firstTask.id)
                else Navigation.open(this, if (current.admin && index in 1..2) "members" else "team", current.id)
            }
        }
        click(R.id.btnAcceptInvite) {
            Workspace.invitationPending = false
            val joined = Workspace.Team(
                name = getString(R.string.screen_invited_team_name),
                description = getString(R.string.screen_invited_team_description),
                admin = false,
                tasks = mutableListOf(),
                members = mutableListOf(Workspace.Member(Workspace.profile.name, "Membro"))
            )
            Workspace.teams.add(joined); Navigation.open(this, "team", joined.id); finish()
        }
        click(R.id.btnDeclineInvite) {
            Workspace.invitationPending = false
            (findViewById<View>(R.id.tvNotification5).parent as View).visibility = View.GONE
            message(getString(R.string.screen_invite_declined))
        }
    }
    private fun bindTeam() {
        val current = team!!
        text(R.id.tvTeamName, current.name)
        findViewById<TextView>(R.id.tvTeamDescription)?.let { Navigation.teamDescription(this, it, current.description) }
        text(R.id.tvMemberCountBadge, Navigation.membersCount(this, current.members.size))
        text(R.id.tvTasksTabCount, current.tasks.count { it.status != Workspace.Status.FINISHED }.toString())
        text(R.id.tvMembersTabCount, current.members.size.toString())
        updateTeamCounters()
        findViewById<View>(R.id.btnEditTeam)?.visibility = if (current.admin) View.VISIBLE else View.GONE
        if (current.admin) click(R.id.btnEditTeam) { editTeam() }
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
        findViewById<View>(R.id.btnManageMembers)?.visibility = if (current.admin) View.VISIBLE else View.GONE
        if (current.admin) click(R.id.btnManageMembers) { go("members") }
        click(R.id.btnTaskHistory) { go("history") }
        if (current.admin) click(R.id.btnAddTask) { editTask(null) }
        click(R.id.btnLeaveTeam) {
            confirm(R.layout.dialog_leave_team, R.id.btnConfirmLeaveTeam) { Workspace.teams.remove(team); Navigation.home(this) }
        }
        bindTasks(false)
        bindInlineMembers()
        showTeamPanel(showMembers = false)
    }

    private fun bindInlineMembers() {
        val list = findViewById<LinearLayout>(R.id.membersList) ?: return
        list.removeAllViews()
        team!!.members.forEach { member ->
            val card = layoutInflater.inflate(R.layout.item_member_card, list, false)
            card.findViewById<TextView>(R.id.tvMemberCardName).text = member.name
            card.findViewById<TextView>(R.id.tvMemberCardRole).text = getString(R.string.screen_role_format, member.role)
            list.addView(card)
        }
    }

    private fun showTeamPanel(showMembers: Boolean) {
        text(R.id.tvTeamSectionTitle, getString(if (showMembers) R.string.screen_members else R.string.screen_tasks))
        findViewById<View>(R.id.membersPanel)?.visibility = if (showMembers) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btnAddTask)?.visibility =
            if (showMembers || team?.admin != true) View.GONE else View.VISIBLE
        findViewById<View>(R.id.btnTaskHistory)?.visibility = if (showMembers) View.GONE else View.VISIBLE
        val taskList = findViewById<View>(R.id.btnAddTask)?.parent as? ViewGroup
        if (taskList != null) {
            for (index in 0 until taskList.childCount) {
                val child = taskList.getChildAt(index)
                if (child.tag == "task_item") child.visibility = if (showMembers) View.GONE else View.VISIBLE
            }
        }
        val selected = color(R.color.blue)
        val unselected = color(R.color.black)
        listOf(R.id.tvTasksTabLabel to !showMembers, R.id.tvMembersTabLabel to showMembers,
            R.id.tvTasksTabCount to !showMembers, R.id.tvMembersTabCount to showMembers).forEach { (id, active) ->
            findViewById<TextView>(id)?.apply {
                setTextColor(if (active) selected else unselected)
                setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
            }
        }
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
        val name = view.findViewById<EditText>(R.id.etTeamName).apply { setText(current.name); clearErrorOnChange() }
        val description = view.findViewById<EditText>(R.id.etTeamDescription).apply { setText(current.description) }
        view.findViewById<View>(R.id.btnSaveTeam).setOnClickListener {
            if (name.text.isBlank()) { name.showError(getString(R.string.team_name_required_error)); return@setOnClickListener }
            current.name = name.text.toString().trim(); current.description = description.text.toString().trim()
            dialog.dismiss(); render()
        }
    }
    private fun bindTasks(history: Boolean) {
        val ids = listOf(R.id.taskItem0, R.id.taskItem1, R.id.taskItem2, R.id.taskItem3)
            .filter { findViewById<View>(it) != null }
        val template = findViewById<View>(ids.first())
        val parent = template.parent as ViewGroup
        val insertion = parent.indexOfChild(template)
        ids.forEach { id -> parent.removeView(findViewById(id)) }
        val tasks = team!!.tasks
            .filter { (it.status == Workspace.Status.FINISHED) == history }
            .sortedBy { DueDates.parse(it.date) ?: LocalDate.MAX }
        tasks.forEachIndexed { index, item ->
            val style = statusStyle(item)
            val row = layoutInflater.inflate(R.layout.item_flow_task, parent, false)
            row.tag = "task_item"
            row.findViewById<TextView>(R.id.tvTaskTitle).text = item.title
            row.findViewById<TextView>(R.id.tvResponsibles).text = responsiblesText(item)
            row.findViewById<TextView>(R.id.tvDueDate).text = item.date
            row.setBackgroundResource(style.card)
            applyStatus(row.findViewById(R.id.tvStatusBadge), style)
            row.setOnClickListener { go("task", item.id) }; parent.addView(row, insertion + index)
        }
        if (tasks.isEmpty()) parent.addView(
            emptyState(this, getString(if (history) R.string.screen_no_finished_tasks else R.string.screen_no_active_tasks))
                .apply { tag = "task_item" },
            insertion
        )
    }
    private fun bindTask() {
        val item = task!!
        applyStatus(findViewById(R.id.tvStatusBadge), statusStyle(item))
        if (item.status != Workspace.Status.FINISHED) {
            val pending = item.status == Workspace.Status.PENDING
            text(R.id.tvTaskActionLabel, getString(if (pending) R.string.screen_start_task else R.string.screen_finish_task))
            findViewById<ImageView>(R.id.ivTaskActionIcon)?.setImageResource(if (pending) R.drawable.ic_play else R.drawable.ic_check_circle)
        }
        text(R.id.tvTaskTitle, item.title); text(R.id.tvDescription, item.description)
        text(R.id.tvResponsibles, responsiblesText(item)); text(R.id.tvDueDate, item.date)
        click(R.id.btnTaskAction) { item.advance(); render() }
        findViewById<View>(R.id.btnEditTask)?.visibility = if (team!!.admin) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btnDeleteTask)?.visibility = if (team!!.admin) View.VISIBLE else View.GONE
        if (team!!.admin) {
            click(R.id.btnDeleteTask) { confirm(R.layout.dialog_delete_task, R.id.btnConfirmDeleteTask) { team!!.tasks.remove(item); finish() } }
            click(R.id.btnEditTask) { editTask(item) }
        }
        click(R.id.btnComment) { editComment(null) }
        if (item.status == Workspace.Status.FINISHED) {
            listOf(R.id.btnTaskAction, R.id.btnEditTask, R.id.btnDeleteTask, R.id.btnComment, R.id.footerLayout)
                .forEach { findViewById<View>(it)?.visibility = View.GONE }
        }
        bindComments()
    }
    private fun editTask(item: Workspace.Task?) = dialog(if (item == null) R.layout.dialog_add_task else R.layout.dialog_edit_task) { view, dialog ->
        val title = view.findViewById<EditText>(R.id.etTaskTitle).apply { setText(item?.title.orEmpty()); clearErrorOnChange() }
        val description = view.findViewById<EditText>(R.id.etDescription).apply { setText(item?.description.orEmpty()); clearErrorOnChange() }
        val responsible = view.findViewById<EditText>(R.id.etResponsibles).apply { setText(item?.responsibles.orEmpty()) }
        val date = view.findViewById<EditText>(R.id.etDueDate).apply {
            setText(item?.date.orEmpty())
            isFocusable = false
            isClickable = true
            setOnClickListener { showDueDatePicker(this) }
        }
        view.findViewById<View>(if (item == null) R.id.btnAddTask else R.id.btnSaveTask).setOnClickListener {
            title.showError(if (title.text.isBlank()) getString(R.string.screen_task_title_required) else null)
            description.showError(if (description.text.isBlank()) getString(R.string.screen_description_required) else null)
            date.showError(if (DueDates.isValid(date.text.toString().trim())) null else getString(R.string.screen_due_date_invalid))
            if (title.text.isBlank() || description.text.isBlank() || !DueDates.isValid(date.text.toString().trim())) {
                return@setOnClickListener
            }
            val target = item ?: Workspace.Task(comments = mutableListOf())
            target.title = title.text.toString().trim(); target.description = description.text.toString().trim()
            target.responsibles = responsible.text.toString().trim(); target.date = date.text.toString().trim()
            if (item == null) {
                team!!.tasks.add(target)
                team!!.hasNewTasks = true
                Workspace.hasUnreadNotifications = true
            }
            dialog.dismiss()
            if (item == null) go("task", target.id) else render()
        }
    }

    private fun showDueDatePicker(field: EditText) {
        val today = LocalDate.now()
        val selected = DueDates.parse(field.text.toString())?.takeUnless { it.isBefore(today) } ?: today
        DatePickerDialog(
            this,
            { _, year, month, day ->
                field.setText(LocalDate.of(year, month + 1, day).format(DueDates.formatter))
                field.showError(null)
            },
            selected.year,
            selected.monthValue - 1,
            selected.dayOfMonth
        ).apply {
            datePicker.minDate = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.show()
    }
    private fun bindComments() {
        // O comentário de exemplo do layout marca onde a lista é inserida.
        val template = findViewById<View>(R.id.tvCommenterName).parent as View
        val parent = template.parent as ViewGroup
        val insertion = parent.indexOfChild(template)
        parent.removeView(template)
        val finished = task!!.status == Workspace.Status.FINISHED
        task!!.comments.forEachIndexed { index, comment ->
            val row = layoutInflater.inflate(R.layout.item_flow_comment, parent, false)
            row.findViewById<TextView>(R.id.tvCommentAuthor).text = comment.author
            row.findViewById<TextView>(R.id.tvCommentText1).text = comment.text
            row.setOnClickListener { commentHistory(comment) }
            val edited = row.findViewById<TextView>(R.id.btnCommentHistory)
            edited.visibility = if (comment.history.isEmpty()) View.GONE else View.VISIBLE
            val edit = row.findViewById<View>(R.id.btnEditComment1)
            edit.visibility = if (finished) View.GONE else View.VISIBLE
            edit.setOnClickListener {
                if (team!!.admin) {
                    PopupMenu(this, edit).apply {
                        menu.add(getString(R.string.screen_edit_comment)).setOnMenuItemClickListener { editComment(comment); true }
                        menu.add(getString(R.string.screen_delete_comment)).setOnMenuItemClickListener { removeComment(comment); true }
                        show()
                    }
                } else editComment(comment)
            }
            if (team!!.admin && !finished) row.setOnLongClickListener { removeComment(comment); true }
            parent.addView(row, insertion + index)
        }
        if (task!!.comments.isEmpty()) parent.addView(emptyState(this, getString(R.string.screen_no_comments)), insertion)
    }
    private fun removeComment(comment: Workspace.Comment) =
        confirm(R.layout.dialog_remove_comment, R.id.btnConfirmRemoveComment) { task!!.comments.remove(comment); render() }

    private fun editComment(comment: Workspace.Comment?) = dialog(if (comment == null) R.layout.dialog_create_comment else R.layout.dialog_edit_comment) { view, dialog ->
        val input = view.findViewById<EditText>(R.id.etComment).apply { setText(comment?.text.orEmpty()); clearErrorOnChange() }
        view.findViewById<View>(if (comment == null) R.id.btnSubmitComment else R.id.btnSaveComment).setOnClickListener {
            if (input.text.isBlank()) { input.showError(getString(R.string.screen_comment_required)); return@setOnClickListener }
            if (comment == null) task!!.comments.add(Workspace.Comment(input.text.toString(), author = Workspace.profile.name))
            else if (comment.text != input.text.toString()) { comment.history.add(comment.text); comment.text = input.text.toString() }
            dialog.dismiss(); render()
        }
        if (comment != null) view.findViewById<View>(R.id.btnDeleteComment)?.setOnClickListener { removeComment(comment) }
    }
    private fun commentHistory(comment: Workspace.Comment) = dialog(R.layout.dialog_comment_history) { view, dialog ->
        val versions = view.findViewById<LinearLayout>(R.id.commentHistoryVersions)
        (comment.history + comment.text).forEachIndexed { index, value ->
            if (index > 0) versions.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_arrow_back)
                rotation = 270f
                contentDescription = getString(R.string.screen_history_direction)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40))
                setPadding(dp(8), dp(8), dp(8), dp(8))
            })
            val card = layoutInflater.inflate(R.layout.item_comment_version, versions, false)
            card.findViewById<TextView>(R.id.tvVersionLabel).text =
                if (index == comment.history.size) "Versão atual" else "Versão ${index + 1}"
            card.findViewById<TextView>(R.id.tvVersionText).text = value
            versions.addView(card)
        }
        view.findViewById<View>(R.id.btnCloseHistory).setOnClickListener { dialog.dismiss() }
    }
    private fun bindMembers() {
        if (!team!!.admin) { finish(); return }
        click(R.id.btnAddMember) {
            startActivity(
                Intent(this, InviteTeamActivity::class.java)
                    .putExtra(Navigation.TEAM, team!!.id)
                    .putExtra(Navigation.RETURN_TO_CALLER, true)
            )
        }
        click(R.id.btnDeleteTeam) { confirm(R.layout.dialog_delete_team, R.id.btnConfirmDeleteTeam) { Workspace.teams.remove(team); Navigation.home(this) } }
        bindManagedMembers()
    }
    private fun bindManagedMembers() {
        val addButton = findViewById<View>(R.id.btnAddMember)
        val list = addButton.parent as LinearLayout
        val firstMemberIndex = list.indexOfChild(addButton) + 1
        if (list.childCount > firstMemberIndex) {
            list.removeViews(firstMemberIndex, list.childCount - firstMemberIndex)
        }
        team!!.members.forEach { member ->
            val card = layoutInflater.inflate(R.layout.item_manage_member, list, false)
            card.findViewById<TextView>(R.id.tvManagedMemberName).text = member.name
            val roleLabel = card.findViewById<TextView>(R.id.tvManagedMemberRole)
            val roleSpinner = card.findViewById<Spinner>(R.id.spinnerManagedMemberRole)
            val removeButton = card.findViewById<View>(R.id.btnRemoveManagedMember)
            val isCreator = member.role == "Criador(a)"
            roleLabel.visibility = if (isCreator) View.VISIBLE else View.GONE
            roleLabel.text = getString(R.string.screen_role_format, member.role)
            roleSpinner.visibility = if (isCreator) View.GONE else View.VISIBLE
            removeButton.visibility = if (isCreator) View.GONE else View.VISIBLE
            if (!isCreator) {
                val options = listOf("Administrador(a)", "Membro")
                roleSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, options).apply {
                    setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }
                roleSpinner.setSelection(options.indexOf(member.role).coerceAtLeast(0))
                roleSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        member.role = options[position]
                    }
                }
                removeButton.setOnClickListener {
                    dialog(R.layout.dialog_remove_member) { view, dialog ->
                        view.findViewById<TextView>(R.id.tvMemberName).text = member.name
                        view.findViewById<View>(R.id.btnConfirmRemoveMember).setOnClickListener {
                            team!!.members.remove(member)
                            dialog.dismiss()
                            render()
                            confirm(R.layout.dialog_member_removed, R.id.btnOk) { }
                        }
                    }
                }
            }
            list.addView(card)
        }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun bindProfile() {
        val name = findViewById<br.com.fatec.syncro.ui.SyncroTextInput>(R.id.profileNameInput)
        val email = findViewById<br.com.fatec.syncro.ui.SyncroTextInput>(R.id.profileEmailInput)
        val password = findViewById<br.com.fatec.syncro.ui.SyncroTextInput>(R.id.profilePasswordInput)
        val confirm = findViewById<br.com.fatec.syncro.ui.SyncroTextInput>(R.id.profileConfirmPasswordInput)
        name.input.setText(Workspace.profile.name)
        email.input.setText(Workspace.profile.email)
        password.input.setText("")
        confirm.input.setText("")
        listOf(name, email, confirm).forEach { field -> field.input.doAfterTextChanged { field.error = null } }
        password.input.doAfterTextChanged { password.error = null; confirm.error = null }
        click(R.id.btnSaveProfile) {
            val newName = name.input.text.toString().trim()
            val newEmail = email.input.text.toString().trim()
            val newPassword = password.input.text.toString()
            name.error = if (newName.isBlank()) getString(R.string.name_required_error) else null
            email.error = when {
                newEmail.isBlank() -> getString(R.string.email_required_error)
                !android.util.Patterns.EMAIL_ADDRESS.matcher(newEmail).matches() -> getString(R.string.email_invalid_error)
                else -> null
            }
            password.error = when {
                newPassword.isNotEmpty() && newPassword.isBlank() ->
                    getString(R.string.password_required_error)
                newPassword.isNotEmpty() && newPassword.length < 6 ->
                    getString(R.string.auth_weak_password)
                else -> null
            }
            confirm.error = if (newPassword != confirm.input.text.toString()) getString(R.string.passwords_do_not_match_error) else null
            if (listOf(name, email, password, confirm).any { it.error != null }) return@click

            val saveButton = findViewById<Button>(R.id.btnSaveProfile)
            saveButton.isEnabled = false
            saveButton.text = getString(R.string.saving_profile)
            FirebaseAccountRepository.updateAccount(newName, newEmail, newPassword) { error ->
                saveButton.isEnabled = true
                saveButton.text = getString(R.string.save_changes)
                if (error == null) {
                    message(getString(R.string.profile_updated))
                    finish()
                } else {
                    val errorMessage = FirebaseAccountRepository.errorMessage(this, error)
                    when (error) {
                        is com.google.firebase.auth.FirebaseAuthUserCollisionException ->
                            email.error = errorMessage
                        is com.google.firebase.auth.FirebaseAuthWeakPasswordException ->
                            password.error = errorMessage
                        else -> password.error = errorMessage
                    }
                }
            }
        }
    }
    override fun onDestroy() { activeDialog?.dismiss(); super.onDestroy() }
}
