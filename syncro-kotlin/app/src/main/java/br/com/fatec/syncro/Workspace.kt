package br.com.fatec.syncro

/**
 * Estado em memória lido pelas telas. Equipes, membros e convites são uma cópia
 * do Firestore mantida por FirebaseTeamRepository; tarefas e comentários ainda
 * existem só aqui.
 */
object Workspace {
    data class Profile(
        var name: String = "Você",
        var email: String = "",
        var password: String = "",
        var uid: String = ""
    )
    val profile = Profile()
    var hasUnreadNotifications = true
    enum class Status { PENDING, IN_PROGRESS, FINISHED }
    data class Comment(
        var text: String,
        val history: MutableList<String> = mutableListOf(),
        val author: String = profile.name
    )
    data class Task(
        val id: String = java.util.UUID.randomUUID().toString(),
        var title: String = "Título tarefa",
        var description: String = "Descrição da tarefa",
        var responsibles: String = "Nome responsável",
        var date: String = java.time.LocalDate.now().format(DueDates.formatter),
        var status: Status = Status.PENDING,
        val comments: MutableList<Comment> = mutableListOf(
            Comment("Comentário da tarefa", author = responsibles.substringBefore(" |"))
        )
    ) {
        fun advance() {
            status = when (status) {
                Status.PENDING -> Status.IN_PROGRESS
                Status.IN_PROGRESS, Status.FINISHED -> Status.FINISHED
            }
        }
    }
    data class Member(var name: String, var role: String, val uid: String = "")
    data class Team(
        val id: String = java.util.UUID.randomUUID().toString(),
        var name: String = "Nome equipe",
        var description: String = "Descrição da equipe",
        var admin: Boolean = true,
        val code: String = id.take(10),
        val tasks: MutableList<Task> = mutableListOf(Task(), Task(status = Status.IN_PROGRESS), Task(status = Status.FINISHED)),
        val members: MutableList<Member> = mutableListOf(Member("Nome criador", "Criador(a)"), Member("Nome administrador", "Administrador(a)"), Member("Nome membro", "Membro")),
        var hasNewTasks: Boolean = false,
        var hasNewMembers: Boolean = false,
        val createdBy: String = ""
    ) {
        val isCreator get() = createdBy.isNotEmpty() && createdBy == profile.uid
    }
    data class Invitation(val teamId: String, val teamName: String)

    /** Equipes do usuário, carregadas do Firestore por FirebaseTeamRepository. */
    val teams = mutableListOf<Team>()
    /** Convites pendentes para o e-mail do usuário. */
    val invitations = mutableListOf<Invitation>()
    /** Falso até a primeira carga terminar, para não exibir "nenhuma equipe" antes da hora. */
    var teamsLoaded = false

    fun team(id: String?) = teams.firstOrNull { it.id == id }

    fun clearTeams() {
        teams.clear()
        invitations.clear()
        teamsLoaded = false
    }
}
