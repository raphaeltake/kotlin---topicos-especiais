package br.com.fatec.syncro

import java.util.Date

/**
 * Estado em memória lido pelas telas: uma cópia do Firestore mantida por
 * FirebaseTeamRepository (equipes, membros e convites), FirebaseTaskRepository
 * (tarefas, comentários e histórico) e FirebaseNotificationRepository
 * (notificações).
 */
object Workspace {
    data class Profile(
        var name: String = "Você",
        var email: String = "",
        var password: String = "",
        var uid: String = ""
    )
    val profile = Profile()
    /** Há notificações ainda não lidas (acende o sino). */
    var hasUnreadNotifications = false
    enum class Status { PENDING, IN_PROGRESS, FINISHED }
    data class Comment(
        var text: String,
        /** Textos das versões anteriores, da mais antiga para a mais recente. */
        val history: MutableList<String> = mutableListOf(),
        val author: String = profile.name,
        val id: String = java.util.UUID.randomUUID().toString(),
        val authorId: String = "",
        /** Data de publicação de cada versão anterior (mesma ordem de [history]). */
        val historyDates: MutableList<Date?> = mutableListOf(),
        val createdAt: Date? = null,
        /** Data da versão atual. */
        var updatedAt: Date? = null
    )
    data class Task(
        val id: String = java.util.UUID.randomUUID().toString(),
        var title: String = "Título tarefa",
        var description: String = "Descrição da tarefa",
        /** Uids dos membros responsáveis pela tarefa. */
        var responsibleIds: List<String> = emptyList(),
        /** Nome de cada responsável quando foi atribuído, para quem já saiu da equipe. */
        var responsibleNames: Map<String, String> = emptyMap(),
        var date: String = java.time.LocalDate.now().format(DueDates.formatter),
        var status: Status = Status.PENDING,
        val comments: MutableList<Comment> = mutableListOf(),
        /** Falso até os comentários serem buscados no Firestore. */
        var commentsLoaded: Boolean = false,
        val createdBy: String = "",
        /** Alterações desta tarefa, da mais recente para a mais antiga. */
        val history: MutableList<HistoryEntry> = mutableListOf(),
        var historyLoaded: Boolean = false
    ) {
        val nextStatus: Status
            get() = when (status) {
                Status.PENDING -> Status.IN_PROGRESS
                Status.IN_PROGRESS, Status.FINISHED -> Status.FINISHED
            }
    }
    data class Member(var name: String, var role: String, val uid: String = "", val email: String = "")
    data class Team(
        val id: String = java.util.UUID.randomUUID().toString(),
        var name: String = "Nome equipe",
        var description: String = "Descrição da equipe",
        var admin: Boolean = true,
        val code: String = id.take(10),
        val tasks: MutableList<Task> = mutableListOf(),
        val members: MutableList<Member> = mutableListOf(),
        var hasNewTasks: Boolean = false,
        var hasNewMembers: Boolean = false,
        val createdBy: String = "",
        /** Falso até as tarefas serem buscadas no Firestore pela primeira vez. */
        var tasksLoaded: Boolean = false,
        /** E-mails convidados que ainda não responderam. */
        val invitedEmails: MutableList<String> = mutableListOf(),
        /** Alterações nas tarefas da equipe, da mais recente para a mais antiga. */
        val history: MutableList<HistoryEntry> = mutableListOf(),
        var historyLoaded: Boolean = false
    ) {
        val isCreator get() = createdBy.isNotEmpty() && createdBy == profile.uid

        fun member(uid: String) = members.firstOrNull { it.uid == uid }

        /** Nomes dos responsáveis, com o nome atual de quem ainda é membro. */
        fun responsibleNames(task: Task): List<String> = task.responsibleIds.map { uid ->
            member(uid)?.name ?: task.responsibleNames[uid] ?: uid
        }

        /** Responsáveis e administradores mudam a situação da tarefa (UC15). */
        fun canChangeStatus(task: Task) = admin || profile.uid in task.responsibleIds
    }
    data class Invitation(val teamId: String, val teamName: String)

    /** Registro do histórico (RF25): quem alterou o quê, de qual valor para qual, e quando. */
    data class HistoryEntry(
        val id: String,
        val type: String,
        val taskId: String,
        val taskTitle: String,
        val commentId: String,
        val field: String,
        val oldValue: String,
        val newValue: String,
        val userName: String,
        val createdAt: Date?
    )

    /** Notificação do usuário (RF26/UC17). */
    data class Notification(
        val id: String,
        var type: String,
        val content: String,
        var seen: Boolean,
        val createdAt: Date?,
        val teamId: String,
        val teamName: String,
        val taskId: String
    )

    /** Equipes do usuário, carregadas do Firestore por FirebaseTeamRepository. */
    val teams = mutableListOf<Team>()
    /** Convites pendentes para o e-mail do usuário. */
    val invitations = mutableListOf<Invitation>()
    /** Falso até a primeira carga terminar, para não exibir "nenhuma equipe" antes da hora. */
    var teamsLoaded = false
    /** Notificações mais recentes do usuário. */
    val notifications = mutableListOf<Notification>()
    var notificationsLoaded = false

    fun team(id: String?) = teams.firstOrNull { it.id == id }

    fun clearTeams() {
        teams.clear()
        invitations.clear()
        teamsLoaded = false
        notifications.clear()
        notificationsLoaded = false
        hasUnreadNotifications = false
    }
}
