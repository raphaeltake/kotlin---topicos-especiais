package br.com.fatec.syncro

/** Local prototype state. No remote requests are made. */
object Workspace {
    private fun dateIn(days: Long) = java.time.LocalDate.now().plusDays(days).format(DueDates.formatter)
    data class Profile(var name: String = "Você", var email: String = "", var password: String = "")
    val profile = Profile()
    var hasUnreadNotifications = true
    enum class Status { PENDING, IN_PROGRESS, FINISHED }
    data class Comment(var text: String, val history: MutableList<String> = mutableListOf())
    data class Task(
        val id: String = java.util.UUID.randomUUID().toString(),
        var title: String = "Título tarefa",
        var description: String = "Descrição da tarefa",
        var responsibles: String = "Nome responsável",
        var date: String = java.time.LocalDate.now().format(DueDates.formatter),
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
        Team(
            id = "team-1",
            name = "Produto Syncro",
            description = "Planejamento e desenvolvimento das melhorias do aplicativo Syncro.",
            code = "12hg37f9d9",
            tasks = mutableListOf(
                Task(title = "Planejar próxima sprint", description = "Definir prioridades e distribuir as histórias da próxima sprint.", responsibles = "Ana Souza", date = dateIn(2)),
                Task(title = "Integrar API de notificações", description = "Conectar o aplicativo ao serviço de notificações e validar os eventos.", responsibles = "Bruno Lima | Carla Dias", date = dateIn(6), status = Status.IN_PROGRESS),
                Task(title = "Atualizar documentação", description = "Documentar os novos fluxos de equipes, tarefas e comentários.", responsibles = "Diego Alves", date = dateIn(14)),
                Task(title = "Revisar acessibilidade", description = "Verificar contraste, descrições e áreas de toque nas telas principais.", responsibles = "Fernanda Rocha", date = dateIn(21), status = Status.IN_PROGRESS),
                Task(title = "Publicar versão inicial", description = "Gerar e validar o primeiro pacote demonstrável do aplicativo.", responsibles = "Ana Souza | Bruno Lima", date = dateIn(-3), status = Status.FINISHED)
            ),
            members = mutableListOf(
                Member("Ana Souza", "Criador(a)"),
                Member("Bruno Lima", "Administrador(a)"),
                Member("Carla Dias", "Membro"),
                Member("Diego Alves", "Membro"),
                Member("Fernanda Rocha", "Membro")
            ),
            hasNewTasks = true,
            hasNewMembers = true
        ),
        Team(
            id = "team-2",
            name = "Projeto interdisciplinar",
            description = "Equipe responsável pelo projeto acadêmico interdisciplinar do semestre.",
            code = "syncro002",
            tasks = mutableListOf(
                Task(title = "Finalizar protótipo", description = "Concluir as telas navegáveis para a apresentação.", responsibles = "Gabriel Martins | Helena Costa", date = dateIn(1), status = Status.IN_PROGRESS),
                Task(title = "Modelar banco de dados", description = "Revisar entidades, relacionamentos e regras de integridade.", responsibles = "Igor Santos", date = dateIn(9)),
                Task(title = "Criar testes de integração", description = "Cobrir os principais fluxos do sistema com testes automatizados.", responsibles = "Juliana Melo", date = dateIn(18)),
                Task(title = "Preparar apresentação", description = "Organizar roteiro, demonstração e divisão das falas.", responsibles = "Equipe completa", date = dateIn(27)),
                Task(title = "Levantar requisitos", description = "Consolidar requisitos funcionais e não funcionais.", responsibles = "Helena Costa", date = dateIn(-10), status = Status.FINISHED)
            ),
            members = mutableListOf(
                Member("Gabriel Martins", "Criador(a)"),
                Member("Helena Costa", "Administrador(a)"),
                Member("Igor Santos", "Membro"),
                Member("Juliana Melo", "Membro"),
                Member("Lucas Ribeiro", "Membro"),
                Member("Marina Freitas", "Membro")
            )
        ),
        Team(
            id = "team-3",
            name = "Grupo de estudos Kotlin",
            description = "Estudos semanais de Kotlin, Android e boas práticas de desenvolvimento.",
            admin = false,
            code = "syncro003",
            tasks = mutableListOf(
                Task(title = "Revisar corrotinas", description = "Revisar conceitos de suspensão, escopos e cancelamento.", responsibles = "Natália Gomes", date = dateIn(4)),
                Task(title = "Resolver exercícios de coleções", description = "Praticar transformações e operações com coleções Kotlin.", responsibles = "Otávio Nunes | Paula Reis", date = dateIn(11), status = Status.IN_PROGRESS),
                Task(title = "Criar resumo de arquitetura", description = "Comparar MVC, MVP e MVVM em aplicações Android.", responsibles = "Rafael Pinto", date = dateIn(25)),
                Task(title = "Simulado de fundamentos", description = "Responder e discutir o simulado introdutório.", responsibles = "Grupo completo", date = dateIn(-2), status = Status.FINISHED)
            ),
            members = mutableListOf(
                Member("Natália Gomes", "Criador(a)"),
                Member("Otávio Nunes", "Administrador(a)"),
                Member("Paula Reis", "Membro"),
                Member("Rafael Pinto", "Membro")
            )
        )
    )
    fun team(id: String?) = teams.firstOrNull { it.id == id }
    var invitationPending = true
}
