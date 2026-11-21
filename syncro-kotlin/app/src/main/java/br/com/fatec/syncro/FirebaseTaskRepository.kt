package br.com.fatec.syncro

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import java.util.Date

/**
 * Tarefas, comentários e histórico no Cloud Firestore.
 *
 * - `teams/{teamId}/tasks/{taskId}`: título, descrição, responsáveis
 *   (`responsibleIds` e o nome de cada um em `responsibleNames`), prazo
 *   (`dueDate`, no formato aaaa-mm-dd), situação (`pending`, `in_progress` ou
 *   `finished`), autor e datas.
 * - `teams/{teamId}/tasks/{taskId}/comments/{commentId}`: texto, `history`
 *   com as versões anteriores (texto e data), autor e datas.
 * - `teams/{teamId}/history/{eventId}`: um registro por alteração (RF25),
 *   gravado no mesmo lote ou transação da própria alteração.
 *
 * Tarefas, comentários e histórico chegam em tempo real pelas funções
 * `listen*`. Depois de cada gravação, a cópia em [Workspace] também é
 * atualizada e os interessados recebem notificação
 * (FirebaseNotificationRepository).
 */
object FirebaseTaskRepository {
    const val MAX_TITLE_LENGTH = 100
    const val MAX_DESCRIPTION_LENGTH = 2000
    const val MAX_RESPONSIBLES = 20
    const val MAX_COMMENT_LENGTH = 1000
    /** RNF13: até 500 tarefas ativas por equipe. */
    const val MAX_ACTIVE_TASKS = 500

    const val EVENT_TASK_CREATED = "task_created"
    const val EVENT_TASK_UPDATED = "task_updated"
    const val EVENT_TASK_STATUS = "task_status"
    const val EVENT_TASK_DELETED = "task_deleted"
    const val EVENT_COMMENT_CREATED = "comment_created"
    const val EVENT_COMMENT_UPDATED = "comment_updated"
    const val EVENT_COMMENT_DELETED = "comment_deleted"

    const val FIELD_TITLE = "title"
    const val FIELD_DESCRIPTION = "description"
    const val FIELD_RESPONSIBLES = "responsibles"
    const val FIELD_DUE_DATE = "dueDate"
    const val FIELD_STATUS = "status"
    const val FIELD_TEXT = "text"

    const val STATUS_PENDING = "pending"
    const val STATUS_IN_PROGRESS = "in_progress"
    const val STATUS_FINISHED = "finished"

    private val auth: FirebaseAuth
        get() = FirebaseAuth.getInstance()

    private val firestore: FirebaseFirestore
        get() = FirebaseFirestore.getInstance()

    private fun tasks(teamId: String): CollectionReference =
        firestore.collection(TEAMS_COLLECTION).document(teamId).collection(TASKS_COLLECTION)

    private fun comments(teamId: String, taskId: String): CollectionReference =
        tasks(teamId).document(taskId).collection(COMMENTS_COLLECTION)

    private fun history(teamId: String): CollectionReference =
        firestore.collection(TEAMS_COLLECTION).document(teamId).collection(HISTORY_COLLECTION)

    /**
     * Escuta em tempo real as tarefas da equipe (veja [LiveUpdates]) e
     * substitui as que estão em memória, mantendo os comentários e o histórico
     * já carregados. Tarefas criadas por outra pessoa acendem o indicador de
     * novidades da aba de tarefas.
     */
    internal fun listenTasks(
        teamId: String,
        onChange: () -> Unit,
        onError: (Exception) -> Unit
    ): ListenerRegistration =
        tasks(teamId).addSnapshotListener { snapshot, error ->
            if (snapshot == null) return@addSnapshotListener onError(error ?: IllegalStateException())
            val team = Workspace.team(teamId) ?: return@addSnapshotListener
            val uid = auth.currentUser?.uid
            val documents = snapshot.documents
            val previous = team.tasks.associateBy { it.id }
            if (team.tasksLoaded &&
                documents.any { it.id !in previous && it.getString("createdBy") != uid }
            ) {
                team.hasNewTasks = true
            }
            val loaded = documents.map { toTask(it, previous[it.id]) }
            team.tasks.clear()
            team.tasks.addAll(loaded)
            team.tasksLoaded = true
            onChange()
        }

    /** UC12/UC14: cria a tarefa já com os responsáveis escolhidos entre os membros. */
    fun createTask(
        team: Workspace.Team,
        title: String,
        description: String,
        responsibleIds: List<String>,
        date: String,
        onComplete: (Result<Workspace.Task>) -> Unit
    ) {
        val uid = auth.currentUser?.uid ?: return onComplete(Result.failure(notSignedIn()))
        if (team.tasks.count { it.status != Workspace.Status.FINISHED } >= MAX_ACTIVE_TASKS) {
            return onComplete(Result.failure(FirebaseTeamRepository.TeamException(R.string.task_limit_reached)))
        }
        val names = responsibleNames(team, responsibleIds)
        val ref = tasks(team.id).document()
        val now = FieldValue.serverTimestamp()
        firestore.batch()
            .set(
                ref,
                mapOf(
                    "title" to title,
                    "description" to description,
                    "responsibleIds" to responsibleIds,
                    "responsibleNames" to names,
                    "dueDate" to DueDates.toIso(date),
                    "status" to STATUS_PENDING,
                    "createdBy" to uid,
                    "createdAt" to now,
                    "updatedAt" to now
                )
            )
            .set(history(team.id).document(), event(team, EVENT_TASK_CREATED, ref.id, title))
            .commit()
            .addOnCompleteListener { task ->
                deliver(task, onComplete) {
                    Workspace.Task(
                        id = ref.id,
                        title = title,
                        description = description,
                        responsibleIds = responsibleIds,
                        responsibleNames = names,
                        date = date,
                        status = Workspace.Status.PENDING,
                        comments = mutableListOf(),
                        commentsLoaded = true,
                        createdBy = uid
                    ).also { created ->
                        // A escuta em tempo real já pode ter trazido a tarefa.
                        if (team.tasks.none { it.id == created.id }) team.tasks.add(created)
                        FirebaseNotificationRepository.notifyTask(
                            team, created, FirebaseNotificationRepository.TYPE_TASK_ASSIGNED, responsibleIds,
                            FirebaseNotificationRepository.assignedContent(actorName(team), created, team)
                        )
                    }
                }
            }
    }

    /**
     * Grava os novos dados e um registro de histórico por campo alterado,
     * comparando com a versão atual no servidor. Quem passou a ser responsável
     * recebe notificação.
     */
    fun updateTask(
        team: Workspace.Team,
        item: Workspace.Task,
        title: String,
        description: String,
        responsibleIds: List<String>,
        date: String,
        onComplete: (Result<Unit>) -> Unit
    ) {
        val ref = tasks(team.id).document(item.id)
        val names = responsibleNames(team, responsibleIds)
        val dueDate = DueDates.toIso(date)
        firestore.runTransaction { transaction ->
            val current = transaction.get(ref)
            if (!current.exists()) throw FirebaseTeamRepository.TeamException(R.string.task_no_longer_exists)
            if (current.getString("status") == STATUS_FINISHED) {
                throw FirebaseTeamRepository.TeamException(R.string.task_already_finished)
            }
            val previousIds = current.stringList("responsibleIds")
            val previousNames = current.get("responsibleNames") as? Map<*, *> ?: emptyMap<String, String>()
            val changes = buildList {
                fun compare(field: String, old: String, new: String) {
                    if (old != new) add(Triple(field, old, new))
                }
                compare(FIELD_TITLE, current.getString("title").orEmpty(), title)
                compare(FIELD_DESCRIPTION, current.getString("description").orEmpty(), description)
                compare(FIELD_DUE_DATE, current.getString("dueDate").orEmpty(), dueDate)
                if (previousIds.toSet() != responsibleIds.toSet()) {
                    add(
                        Triple(
                            FIELD_RESPONSIBLES,
                            previousIds.joinToString(", ") { team.member(it)?.name ?: previousNames[it] as? String ?: it },
                            responsibleIds.joinToString(", ") { names.getValue(it) }
                        )
                    )
                }
            }
            if (changes.isNotEmpty()) {
                transaction.update(
                    ref,
                    mapOf(
                        "title" to title,
                        "description" to description,
                        "responsibleIds" to responsibleIds,
                        "responsibleNames" to names,
                        "dueDate" to dueDate,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                changes.forEach { (field, old, new) ->
                    transaction.set(
                        history(team.id).document(),
                        event(team, EVENT_TASK_UPDATED, item.id, title, field = field, oldValue = old, newValue = new)
                    )
                }
            }
            previousIds
        }.addOnCompleteListener { task ->
            deliver(task, onComplete) { previousIds ->
                item.title = title
                item.description = description
                item.responsibleIds = responsibleIds
                item.responsibleNames = names
                item.date = date
                FirebaseNotificationRepository.notifyTask(
                    team, item, FirebaseNotificationRepository.TYPE_TASK_ASSIGNED, responsibleIds - previousIds.toSet(),
                    FirebaseNotificationRepository.assignedContent(actorName(team), item, team)
                )
            }
        }
    }

    /**
     * UC15: pendente -> em andamento -> finalizada, registrando no histórico.
     * Se a situação mudou no servidor desde que a tela foi aberta, nada é
     * alterado e o usuário é avisado.
     */
    fun advanceTask(team: Workspace.Team, item: Workspace.Task, onComplete: (Result<Unit>) -> Unit) {
        val ref = tasks(team.id).document(item.id)
        val from = toStatusValue(item.status)
        val next = item.nextStatus
        firestore.runTransaction { transaction ->
            val current = transaction.get(ref)
            if (!current.exists()) throw FirebaseTeamRepository.TeamException(R.string.task_no_longer_exists)
            if (current.getString("status") != from || item.status == Workspace.Status.FINISHED) {
                throw FirebaseTeamRepository.TeamException(R.string.task_status_changed)
            }
            transaction.update(
                ref,
                mapOf("status" to toStatusValue(next), "updatedAt" to FieldValue.serverTimestamp())
            )
            transaction.set(
                history(team.id).document(),
                event(
                    team, EVENT_TASK_STATUS, item.id, current.getString("title") ?: item.title,
                    field = FIELD_STATUS, oldValue = from, newValue = toStatusValue(next)
                )
            )
        }.addOnCompleteListener { task ->
            deliver(task, onComplete) {
                item.status = next
                FirebaseNotificationRepository.notifyTask(
                    team, item, FirebaseNotificationRepository.TYPE_TASK_STATUS, interested(item),
                    FirebaseNotificationRepository.statusContent(actorName(team), item)
                )
            }
        }
    }

    /** Apaga os comentários e depois a tarefa, registrando a exclusão no histórico. */
    fun deleteTask(team: Workspace.Team, item: Workspace.Task, onComplete: (Result<Unit>) -> Unit) {
        val taskRef = tasks(team.id).document(item.id)
        comments(team.id, item.id).get().continueWithTask { query ->
            deleteInBatches(query.result.documents.map { it.reference })
        }.continueWithTask { deleted ->
            if (!deleted.isSuccessful) throw deleted.exception ?: IllegalStateException()
            firestore.batch()
                .delete(taskRef)
                .set(history(team.id).document(), event(team, EVENT_TASK_DELETED, item.id, item.title))
                .commit()
        }.addOnCompleteListener { task ->
            deliver(task, onComplete) { team.tasks.remove(item) }
        }
    }

    /** Apaga tarefas, comentários e histórico da equipe, antes de apagar a própria equipe. */
    internal fun deleteAllTeamData(teamId: String): Task<Void> {
        val tasksQuery = tasks(teamId).get()
        val historyQuery = history(teamId).get()
        return Tasks.whenAllSuccess<QuerySnapshot>(tasksQuery, historyQuery).continueWithTask {
            val taskRefs = tasksQuery.result.documents.map { it.reference }
            val historyRefs = historyQuery.result.documents.map { it.reference }
            Tasks.whenAllSuccess<QuerySnapshot>(
                taskRefs.map { it.collection(COMMENTS_COLLECTION).get() }
            ).continueWithTask { commentQueries ->
                val commentRefs = commentQueries.result.flatMap { snapshot ->
                    snapshot.documents.map { it.reference }
                }
                deleteInBatches(commentRefs + taskRefs + historyRefs)
            }
        }
    }

    /** Escuta em tempo real os comentários da tarefa, do mais antigo para o mais recente. */
    fun listenComments(
        teamId: String,
        taskId: String,
        onChange: () -> Unit,
        onError: (Exception) -> Unit
    ): ListenerRegistration =
        comments(teamId, taskId).orderBy("createdAt").addSnapshotListener { snapshot, error ->
            if (snapshot == null) return@addSnapshotListener onError(error ?: IllegalStateException())
            val item = Workspace.team(teamId)?.tasks?.firstOrNull { it.id == taskId }
                ?: return@addSnapshotListener
            item.comments.clear()
            item.comments.addAll(snapshot.documents.map(::toComment))
            item.commentsLoaded = true
            onChange()
        }

    /** UC16: publica o comentário (autor e data) e avisa os interessados na tarefa. */
    fun addComment(
        team: Workspace.Team,
        item: Workspace.Task,
        text: String,
        onComplete: (Result<Unit>) -> Unit
    ) {
        val uid = auth.currentUser?.uid ?: return onComplete(Result.failure(notSignedIn()))
        // Mesmo nome exibido na lista de membros da equipe.
        val authorName = actorName(team)
        val ref = comments(team.id, item.id).document()
        val now = FieldValue.serverTimestamp()
        firestore.batch()
            .set(
                ref,
                mapOf(
                    "text" to text,
                    "history" to emptyList<Any>(),
                    "authorId" to uid,
                    "authorName" to authorName,
                    "createdAt" to now,
                    "updatedAt" to now
                )
            )
            .set(
                history(team.id).document(),
                event(team, EVENT_COMMENT_CREATED, item.id, item.title, commentId = ref.id, newValue = text)
            )
            .commit()
            .addOnCompleteListener { task ->
                deliver(task, onComplete) {
                    val date = Date()
                    // A escuta em tempo real já pode ter trazido o comentário.
                    if (item.comments.none { it.id == ref.id }) item.comments.add(
                        Workspace.Comment(
                            text, mutableListOf(), authorName, id = ref.id, authorId = uid,
                            createdAt = date, updatedAt = date
                        )
                    )
                    FirebaseNotificationRepository.notifyTask(
                        team, item, FirebaseNotificationRepository.TYPE_TASK_COMMENT, interested(item),
                        FirebaseNotificationRepository.commentContent(authorName, item)
                    )
                }
            }
    }

    /**
     * Troca o texto e guarda a versão anterior (texto e data) no comentário e
     * no histórico. Usa uma transação para partir da versão atual no servidor,
     * caso outra pessoa tenha editado antes.
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
            val versions = (current.get("history") as? List<*>).orEmpty()
            if (currentText == text) return@runTransaction versions
            val newVersions = versions + mapOf(
                "text" to currentText,
                "editedAt" to current.getTimestamp("updatedAt")
            )
            transaction.update(
                ref,
                mapOf(
                    "text" to text,
                    "history" to newVersions,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            transaction.set(
                history(team.id).document(),
                event(
                    team, EVENT_COMMENT_UPDATED, item.id, item.title, commentId = comment.id,
                    field = FIELD_TEXT, oldValue = currentText, newValue = text
                )
            )
            newVersions
        }.addOnCompleteListener { task ->
            deliver(task, onComplete) { versions ->
                comment.history.clear()
                comment.historyDates.clear()
                versions.forEach { version ->
                    comment.history.add(versionText(version))
                    comment.historyDates.add(versionDate(version))
                }
                comment.text = text
                comment.updatedAt = Date()
            }
        }
    }

    /** Apaga o comentário, guardando no histórico o texto que foi removido. */
    fun deleteComment(
        team: Workspace.Team,
        item: Workspace.Task,
        comment: Workspace.Comment,
        onComplete: (Result<Unit>) -> Unit
    ) {
        val ref = comments(team.id, item.id).document(comment.id)
        firestore.runTransaction { transaction ->
            val currentText = transaction.get(ref).getString("text")
                ?: throw FirebaseTeamRepository.TeamException(R.string.comment_no_longer_exists)
            transaction.delete(ref)
            transaction.set(
                history(team.id).document(),
                event(
                    team, EVENT_COMMENT_DELETED, item.id, item.title, commentId = comment.id,
                    oldValue = currentText
                )
            )
        }.addOnCompleteListener { task ->
            deliver(task, onComplete) { item.comments.remove(comment) }
        }
    }

    /**
     * Escuta em tempo real o histórico da equipe (os registros mais recentes)
     * ou de uma tarefa ([taskId]), do mais recente para o mais antigo.
     */
    fun listenHistory(
        teamId: String,
        taskId: String?,
        onChange: () -> Unit,
        onError: (Exception) -> Unit
    ): ListenerRegistration {
        val query = if (taskId == null) {
            history(teamId).orderBy("createdAt", Query.Direction.DESCENDING).limit(MAX_HISTORY_LOADED)
        } else {
            // Filtro simples, sem ordenação no servidor, para dispensar índice composto.
            history(teamId).whereEqualTo("taskId", taskId)
        }
        return query.addSnapshotListener { snapshot, error ->
            if (snapshot == null) return@addSnapshotListener onError(error ?: IllegalStateException())
            val team = Workspace.team(teamId) ?: return@addSnapshotListener
            val item = taskId?.let { id -> team.tasks.firstOrNull { it.id == id } ?: return@addSnapshotListener }
            val entries = snapshot.documents.map(::toHistoryEntry)
                .sortedByDescending { it.createdAt ?: Date(0) }
            val target = item?.history ?: team.history
            target.clear()
            target.addAll(entries)
            if (item != null) item.historyLoaded = true else team.historyLoaded = true
            onChange()
        }
    }

    /** Responsáveis e quem criou a tarefa: avisados sobre situação e comentários. */
    private fun interested(item: Workspace.Task) = item.responsibleIds + item.createdBy

    /** Nome com que o usuário aparece na equipe (o mesmo da lista de membros). */
    private fun actorName(team: Workspace.Team): String {
        val uid = auth.currentUser?.uid
        return listOf(team.members.firstOrNull { it.uid == uid }?.name, Workspace.profile.name)
            .firstOrNull { !it.isNullOrBlank() }
            ?.trim()?.take(FirebaseTeamRepository.MAX_NAME_LENGTH)
            ?: DEFAULT_USER_NAME
    }

    private fun responsibleNames(team: Workspace.Team, ids: List<String>): Map<String, String> =
        ids.associateWith { uid -> team.member(uid)?.name?.takeIf { it.isNotBlank() } ?: DEFAULT_USER_NAME }

    private fun event(
        team: Workspace.Team,
        type: String,
        taskId: String,
        taskTitle: String,
        commentId: String = "",
        field: String = "",
        oldValue: String = "",
        newValue: String = ""
    ): Map<String, Any> = mapOf(
        "type" to type,
        "taskId" to taskId,
        "taskTitle" to taskTitle.take(MAX_TITLE_LENGTH),
        "commentId" to commentId,
        "field" to field,
        "oldValue" to oldValue.take(MAX_HISTORY_VALUE_LENGTH),
        "newValue" to newValue.take(MAX_HISTORY_VALUE_LENGTH),
        "userId" to auth.currentUser?.uid.orEmpty(),
        "userName" to actorName(team),
        "createdAt" to FieldValue.serverTimestamp()
    )

    private fun toTask(document: DocumentSnapshot, previous: Workspace.Task?) = Workspace.Task(
        id = document.id,
        title = document.getString("title").orEmpty(),
        description = document.getString("description").orEmpty(),
        responsibleIds = document.stringList("responsibleIds"),
        responsibleNames = (document.get("responsibleNames") as? Map<*, *>).orEmpty()
            .mapNotNull { (key, value) -> (key as? String)?.let { k -> (value as? String)?.let { k to it } } }
            .toMap(),
        date = DueDates.fromIso(document.getString("dueDate")),
        status = when (document.getString("status")) {
            STATUS_IN_PROGRESS -> Workspace.Status.IN_PROGRESS
            STATUS_FINISHED -> Workspace.Status.FINISHED
            else -> Workspace.Status.PENDING
        },
        comments = previous?.comments ?: mutableListOf(),
        commentsLoaded = previous?.commentsLoaded ?: false,
        createdBy = document.getString("createdBy").orEmpty(),
        history = previous?.history ?: mutableListOf(),
        historyLoaded = previous?.historyLoaded ?: false
    )

    private fun toComment(document: DocumentSnapshot): Workspace.Comment {
        val versions = (document.get("history") as? List<*>).orEmpty()
        return Workspace.Comment(
            text = document.getString("text").orEmpty(),
            history = versions.map(::versionText).toMutableList(),
            author = document.getString("authorName").orEmpty(),
            id = document.id,
            authorId = document.getString("authorId").orEmpty(),
            historyDates = versions.map(::versionDate).toMutableList(),
            createdAt = document.getTimestamp("createdAt", ESTIMATE)?.toDate(),
            updatedAt = document.getTimestamp("updatedAt", ESTIMATE)?.toDate()
        )
    }

    /** Cada versão anterior é um mapa {text, editedAt}. */
    private fun versionText(version: Any?): String = when (version) {
        is Map<*, *> -> version["text"] as? String ?: ""
        else -> version as? String ?: ""
    }

    private fun versionDate(version: Any?): Date? =
        ((version as? Map<*, *>)?.get("editedAt") as? com.google.firebase.Timestamp)?.toDate()

    private fun toHistoryEntry(document: DocumentSnapshot) = Workspace.HistoryEntry(
        id = document.id,
        type = document.getString("type").orEmpty(),
        taskId = document.getString("taskId").orEmpty(),
        taskTitle = document.getString("taskTitle").orEmpty(),
        commentId = document.getString("commentId").orEmpty(),
        field = document.getString("field").orEmpty(),
        oldValue = document.getString("oldValue").orEmpty(),
        newValue = document.getString("newValue").orEmpty(),
        userName = document.getString("userName").orEmpty(),
        createdAt = document.getTimestamp("createdAt", ESTIMATE)?.toDate()
    )

    private fun toStatusValue(status: Workspace.Status) = when (status) {
        Workspace.Status.PENDING -> STATUS_PENDING
        Workspace.Status.IN_PROGRESS -> STATUS_IN_PROGRESS
        Workspace.Status.FINISHED -> STATUS_FINISHED
    }

    /**
     * As regras consultam o documento da equipe para cada exclusão, e um lote
     * aceita no máximo 20 dessas consultas. Por isso os lotes são pequenos.
     */
    private fun deleteInBatches(refs: List<DocumentReference>): Task<Void> =
        Tasks.whenAll(
            refs.chunked(MAX_DELETE_BATCH_SIZE).map { chunk ->
                firestore.batch().apply { chunk.forEach { delete(it) } }.commit()
            }
        )

    private const val TEAMS_COLLECTION = "teams"
    private const val TASKS_COLLECTION = "tasks"
    private const val COMMENTS_COLLECTION = "comments"
    private const val HISTORY_COLLECTION = "history"

    private const val DEFAULT_USER_NAME = "Usuário"
    private const val MAX_HISTORY_VALUE_LENGTH = 2000
    private const val MAX_HISTORY_LOADED = 100L
    private const val MAX_DELETE_BATCH_SIZE = 10
}
