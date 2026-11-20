package br.com.fatec.syncro

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot
import java.time.LocalDate

/**
 * Tarefas e comentários no Cloud Firestore.
 *
 * - `teams/{teamId}/tasks/{taskId}`: título, descrição, responsáveis, prazo
 *   (`dueDate`, no formato aaaa-mm-dd), status (`pending`, `in_progress` ou
 *   `finished`), autor e datas.
 * - `teams/{teamId}/tasks/{taskId}/comments/{commentId}`: texto, `history` com
 *   as versões anteriores, autor e datas.
 *
 * As tarefas são buscadas junto com a lista de equipes (para a contagem de
 * tarefas ativas) e de novo ao abrir a equipe; os comentários, ao abrir a
 * tarefa. Depois de cada gravação, a cópia em [Workspace] é atualizada.
 */
object FirebaseTaskRepository {
    const val MAX_TITLE_LENGTH = 100
    const val MAX_DESCRIPTION_LENGTH = 2000
    const val MAX_RESPONSIBLES_LENGTH = 200
    const val MAX_COMMENT_LENGTH = 1000

    private val auth: FirebaseAuth
        get() = FirebaseAuth.getInstance()

    private val firestore: FirebaseFirestore
        get() = FirebaseFirestore.getInstance()

    private fun tasks(teamId: String): CollectionReference =
        firestore.collection(TEAMS_COLLECTION).document(teamId).collection(TASKS_COLLECTION)

    private fun comments(teamId: String, taskId: String): CollectionReference =
        tasks(teamId).document(taskId).collection(COMMENTS_COLLECTION)

    fun loadTasks(team: Workspace.Team, onComplete: (Result<Unit>) -> Unit) {
        refreshTasks(team).addOnCompleteListener { task -> deliver(task, onComplete) {} }
    }

    /**
     * Substitui as tarefas da equipe em memória pelas do Firestore, mantendo os
     * comentários já carregados. Tarefas criadas por outra pessoa desde a última
     * busca acendem o indicador de novidades da aba de tarefas.
     */
    internal fun refreshTasks(team: Workspace.Team): Task<Unit> =
        tasks(team.id).get().continueWith { query ->
            val uid = auth.currentUser?.uid
            val documents = query.result.documents
            val previous = team.tasks.associateBy { it.id }
            if (team.tasksLoaded &&
                documents.any { it.id !in previous && it.getString("createdBy") != uid }
            ) {
                team.hasNewTasks = true
                Workspace.hasUnreadNotifications = true
            }
            val loaded = documents.map { toTask(it, previous[it.id]) }
            team.tasks.clear()
            team.tasks.addAll(loaded)
            team.tasksLoaded = true
        }

    fun createTask(
        team: Workspace.Team,
        title: String,
        description: String,
        responsibles: String,
        date: String,
        onComplete: (Result<Workspace.Task>) -> Unit
    ) {
        val uid = auth.currentUser?.uid ?: return onComplete(Result.failure(notSignedIn()))
        val ref = tasks(team.id).document()
        val now = FieldValue.serverTimestamp()
        ref.set(
            mapOf(
                "title" to title,
                "description" to description,
                "responsibles" to responsibles,
                "dueDate" to toIsoDate(date),
                "status" to STATUS_PENDING,
                "createdBy" to uid,
                "createdAt" to now,
                "updatedAt" to now
            )
        ).addOnCompleteListener { task ->
            deliver(task, onComplete) {
                Workspace.Task(
                    id = ref.id,
                    title = title,
                    description = description,
                    responsibles = responsibles,
                    date = date,
                    status = Workspace.Status.PENDING,
                    comments = mutableListOf(),
                    commentsLoaded = true
                ).also { team.tasks.add(it) }
            }
        }
    }

    fun updateTask(
        team: Workspace.Team,
        item: Workspace.Task,
        title: String,
        description: String,
        responsibles: String,
        date: String,
        onComplete: (Result<Unit>) -> Unit
    ) {
        tasks(team.id).document(item.id).update(
            mapOf(
                "title" to title,
                "description" to description,
                "responsibles" to responsibles,
                "dueDate" to toIsoDate(date),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).addOnCompleteListener { task ->
            deliver(task, onComplete) {
                item.title = title
                item.description = description
                item.responsibles = responsibles
                item.date = date
            }
        }
    }

    /** Pendente -> em andamento -> finalizada. Qualquer membro pode avançar. */
    fun advanceTask(team: Workspace.Team, item: Workspace.Task, onComplete: (Result<Unit>) -> Unit) {
        val next = item.nextStatus
        tasks(team.id).document(item.id).update(
            mapOf("status" to toStatusValue(next), "updatedAt" to FieldValue.serverTimestamp())
        ).addOnCompleteListener { task ->
            deliver(task, onComplete) { item.status = next }
        }
    }

    /** Apaga a tarefa e todos os comentários dela. */
    fun deleteTask(team: Workspace.Team, item: Workspace.Task, onComplete: (Result<Unit>) -> Unit) {
        val taskRef = tasks(team.id).document(item.id)
        comments(team.id, item.id).get().continueWithTask { query ->
            deleteInBatches(query.result.documents.map { it.reference } + taskRef)
        }.addOnCompleteListener { task ->
            deliver(task, onComplete) { team.tasks.remove(item) }
        }
    }

    /** Apaga todas as tarefas e comentários da equipe, antes de apagar a própria equipe. */
    internal fun deleteAllTasks(teamId: String): Task<Void> =
        tasks(teamId).get().continueWithTask { query ->
            val taskRefs = query.result.documents.map { it.reference }
            Tasks.whenAllSuccess<QuerySnapshot>(
                taskRefs.map { it.collection(COMMENTS_COLLECTION).get() }
            ).continueWithTask { commentQueries ->
                val commentRefs = commentQueries.result.flatMap { snapshot ->
                    snapshot.documents.map { it.reference }
                }
                deleteInBatches(commentRefs + taskRefs)
            }
        }

    fun loadComments(team: Workspace.Team, item: Workspace.Task, onComplete: (Result<Unit>) -> Unit) {
        comments(team.id, item.id).orderBy("createdAt").get().addOnCompleteListener { task ->
            deliver(task, onComplete) { snapshot ->
                val loaded = snapshot.documents.map(::toComment)
                item.comments.clear()
                item.comments.addAll(loaded)
                item.commentsLoaded = true
            }
        }
    }

    fun addComment(
        team: Workspace.Team,
        item: Workspace.Task,
        text: String,
        onComplete: (Result<Unit>) -> Unit
    ) {
        val uid = auth.currentUser?.uid ?: return onComplete(Result.failure(notSignedIn()))
        // Mesmo nome exibido na lista de membros da equipe.
        val authorName = team.members.firstOrNull { it.uid == uid }?.name
            ?.takeIf { it.isNotBlank() } ?: Workspace.profile.name
        val ref = comments(team.id, item.id).document()
        val now = FieldValue.serverTimestamp()
        ref.set(
            mapOf(
                "text" to text,
                "history" to emptyList<String>(),
                "authorId" to uid,
                "authorName" to authorName,
                "createdAt" to now,
                "updatedAt" to now
            )
        ).addOnCompleteListener { task ->
            deliver(task, onComplete) {
                item.comments.add(
                    Workspace.Comment(text, mutableListOf(), authorName, id = ref.id, authorId = uid)
                )
            }
        }
    }

    /**
     * Troca o texto e guarda o anterior no histórico. Usa uma transação para
     * partir da versão atual no servidor, caso outra pessoa tenha editado antes.
     */
    fun editComment(
        team: Workspace.Team,
        item: Workspace.Task,
        comment: Workspace.Comment,
        text: String,
        onComplete: (Result<Unit>) -> Unit
    ) {
        val ref = comments(team.id, item.id).document(comment.id)
        firestore.runTransaction { transaction ->
            val current = transaction.get(ref)
            val currentText = current.getString("text")
                ?: throw FirebaseTeamRepository.TeamException(R.string.comment_no_longer_exists)
            val history = current.stringList("history")
            if (currentText == text) return@runTransaction history
            val newHistory = history + currentText
            transaction.update(
                ref,
                mapOf(
                    "text" to text,
                    "history" to newHistory,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            newHistory
        }.addOnCompleteListener { task ->
            deliver(task, onComplete) { history ->
                comment.history.clear()
                comment.history.addAll(history)
                comment.text = text
            }
        }
    }

    fun deleteComment(
        team: Workspace.Team,
        item: Workspace.Task,
        comment: Workspace.Comment,
        onComplete: (Result<Unit>) -> Unit
    ) {
        comments(team.id, item.id).document(comment.id).delete().addOnCompleteListener { task ->
            deliver(task, onComplete) { item.comments.remove(comment) }
        }
    }

    private fun toTask(document: DocumentSnapshot, previous: Workspace.Task?) = Workspace.Task(
        id = document.id,
        title = document.getString("title").orEmpty(),
        description = document.getString("description").orEmpty(),
        responsibles = document.getString("responsibles").orEmpty(),
        date = fromIsoDate(document.getString("dueDate")),
        status = when (document.getString("status")) {
            STATUS_IN_PROGRESS -> Workspace.Status.IN_PROGRESS
            STATUS_FINISHED -> Workspace.Status.FINISHED
            else -> Workspace.Status.PENDING
        },
        comments = previous?.comments ?: mutableListOf(),
        commentsLoaded = previous?.commentsLoaded ?: false
    )

    private fun toComment(document: DocumentSnapshot) = Workspace.Comment(
        text = document.getString("text").orEmpty(),
        history = document.stringList("history").toMutableList(),
        author = document.getString("authorName").orEmpty(),
        id = document.id,
        authorId = document.getString("authorId").orEmpty()
    )

    private fun toStatusValue(status: Workspace.Status) = when (status) {
        Workspace.Status.PENDING -> STATUS_PENDING
        Workspace.Status.IN_PROGRESS -> STATUS_IN_PROGRESS
        Workspace.Status.FINISHED -> STATUS_FINISHED
    }

    /** As telas usam dd/mm/aaaa; o Firestore guarda aaaa-mm-dd, que ordena corretamente. */
    private fun toIsoDate(date: String): String =
        requireNotNull(DueDates.parse(date)) { "Data inválida: $date" }.toString()

    private fun fromIsoDate(value: String?): String =
        runCatching { LocalDate.parse(value).format(DueDates.formatter) }.getOrDefault("")

    /** Um lote do Firestore aceita até 500 operações. */
    private fun deleteInBatches(refs: List<DocumentReference>): Task<Void> =
        Tasks.whenAll(
            refs.chunked(MAX_BATCH_SIZE).map { chunk ->
                firestore.batch().apply { chunk.forEach { delete(it) } }.commit()
            }
        )

    private const val TEAMS_COLLECTION = "teams"
    private const val TASKS_COLLECTION = "tasks"
    private const val COMMENTS_COLLECTION = "comments"

    private const val STATUS_PENDING = "pending"
    private const val STATUS_IN_PROGRESS = "in_progress"
    private const val STATUS_FINISHED = "finished"

    private const val MAX_BATCH_SIZE = 450
}
