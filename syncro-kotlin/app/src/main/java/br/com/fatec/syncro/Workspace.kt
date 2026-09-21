package br.com.fatec.syncro

/** Local prototype state. No remote requests are made. */
object Workspace {
    enum class Status { PENDING, IN_PROGRESS, FINISHED }
    data class Comment(var text: String, val history: MutableList<String> = mutableListOf())
    data class Task(
        val id: String = java.util.UUID.randomUUID().toString(),
        var title: String = "Título tarefa",
        var description: String = "Descrição da tarefa",
        var responsibles: String = "Nome responsável",
        var date: String = "09/03/2026",
        var status: Status = Status.PENDING,
        val comments: MutableList<Comment> = mutableListOf(Comment("Comentário da tarefa"))
    ) {
        fun advance() {
            status = when (status) {
                Status.PENDING -> Status.IN_PROGRESS
                Status.IN_PROGRESS, Status.FINISHED -> Status.FINISHED
            }
        }
    }
    data class Member(var name: String, var role: String)
    data class Team(
        val id: String = java.util.UUID.randomUUID().toString(),
        var name: String = "Nome equipe",
        var description: String = "Descrição da equipe",
        var admin: Boolean = true,
        val code: String = id.take(10),
        val tasks: MutableList<Task> = mutableListOf(Task(), Task(status = Status.IN_PROGRESS), Task(status = Status.FINISHED)),
        val members: MutableList<Member> = mutableListOf(Member("Nome criador", "Criador(a)"), Member("Nome administrador", "Administrador(a)"), Member("Nome membro", "Membro")),
        var hasNewTasks: Boolean = false,
        var hasNewMembers: Boolean = false
    )
    val teams = mutableListOf(
        Team(id = "team-1", code = "12hg37f9d9", hasNewTasks = true, hasNewMembers = true),
        Team(id = "team-2", name = "Equipe de projetos", code = "syncro002"),
        Team(id = "team-3", name = "Equipe de estudos", admin = false, code = "syncro003")
    )
    fun team(id: String?) = teams.firstOrNull { it.id == id }
    var invitationPending = true
}
