package br.com.fatec.syncro

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query

/**
 * Notificações do usuário (RF26/UC17) em `users/{uid}/notifications`, com
 * conteúdo, `seen` (visto), data e o vínculo com o evento (equipe e tarefa).
 *
 * - Convites: quem convida conhece só o e-mail. O servidor de push
 *   (syncro-push) registra o aviso (`invite_{teamId}`) assim que o convite é
 *   feito, e o app do convidado também o registra ao encontrar o convite.
 *   Ao responder, o tipo passa a "aceito" ou "recusado" e o registro continua
 *   na lista.
 * - Tarefas: quem atribui, muda a situação ou comenta grava o aviso na lista
 *   de cada interessado (responsáveis e quem criou a tarefa).
 */
object FirebaseNotificationRepository {
    const val TYPE_INVITE = "team_invite"
    const val TYPE_INVITE_ACCEPTED = "team_invite_accepted"
    const val TYPE_INVITE_DECLINED = "team_invite_declined"
    const val TYPE_TASK_ASSIGNED = "task_assigned"
    const val TYPE_TASK_STATUS = "task_status"
    const val TYPE_TASK_COMMENT = "task_comment"

    private val auth: FirebaseAuth
        get() = FirebaseAuth.getInstance()

    private val firestore: FirebaseFirestore
        get() = FirebaseFirestore.getInstance()

    private fun notifications(uid: String): CollectionReference =
        firestore.collection(USERS_COLLECTION).document(uid).collection(NOTIFICATIONS_COLLECTION)

    private fun inviteId(teamId: String) = "invite_$teamId"

    /**
     * Escuta em tempo real as notificações mais recentes do usuário (veja
     * [LiveUpdates]), que acendem o sino e preenchem a lista.
     */
    internal fun listen(
        uid: String,
        onChange: () -> Unit,
        onError: (Exception) -> Unit
    ): ListenerRegistration =
        notifications(uid)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(MAX_LOADED.toLong())
            .addSnapshotListener { snapshot, error ->
                if (snapshot == null) return@addSnapshotListener onError(error ?: IllegalStateException())
                val loaded = snapshot.documents.map(::toNotification)
                Workspace.notifications.clear()
                Workspace.notifications.addAll(loaded)
                Workspace.notificationsLoaded = true
                Workspace.hasUnreadNotifications = loaded.any { !it.seen }
                onChange()
            }

    /** Registra o aviso de cada convite pendente encontrado para o e-mail do usuário. */
    internal fun registerInvites(invitations: List<Workspace.Invitation>) {
        val uid = auth.currentUser?.uid ?: return
        invitations.forEach { invitation ->
            registerInvite(uid, invitation)
                .addOnFailureListener { Log.w(TAG, "Não foi possível registrar o aviso do convite.", it) }
        }
    }

    /** Marca as notificações exibidas como lidas (UC17, passo 6). */
    fun markSeen(items: List<Workspace.Notification>) {
        val uid = auth.currentUser?.uid ?: return
        val unseen = items.filter { !it.seen }
        if (unseen.isEmpty()) return
        unseen.chunked(MAX_BATCH_SIZE).forEach { chunk ->
            firestore.batch().apply {
                chunk.forEach { update(notifications(uid).document(it.id), "seen", true) }
            }.commit().addOnSuccessListener {
                chunk.forEach { it.seen = true }
                Workspace.hasUnreadNotifications = Workspace.notifications.any { !it.seen }
            }
        }
    }

    /** Guarda a resposta ao convite no aviso, que continua visível (UC17 A2). */
    internal fun answerInvite(teamId: String, accepted: Boolean) {
        val uid = auth.currentUser?.uid ?: return
        val type = if (accepted) TYPE_INVITE_ACCEPTED else TYPE_INVITE_DECLINED
        // A tela é redesenhada logo após a resposta, antes desta gravação terminar.
        Workspace.notifications.firstOrNull { it.id == inviteId(teamId) }?.let {
            it.type = type
            it.seen = true
        }
        notifications(uid).document(inviteId(teamId))
            .update(mapOf("type" to type, "seen" to true))
            .addOnFailureListener { Log.w(TAG, "Não foi possível registrar a resposta ao convite.", it) }
    }

    /**
     * Avisa os interessados em uma tarefa. Cada aviso é uma gravação separada:
     * se um falhar (por exemplo, a pessoa acabou de sair da equipe), os outros
     * seguem, e a alteração da tarefa já foi salva.
     */
    internal fun notifyTask(
        team: Workspace.Team,
        task: Workspace.Task,
        type: String,
        recipients: Collection<String>,
        content: String
    ) {
        val uid = auth.currentUser?.uid ?: return
        val data = mapOf(
            "type" to type,
            "content" to content.take(MAX_CONTENT_LENGTH),
            "seen" to false,
            "createdAt" to FieldValue.serverTimestamp(),
            "teamId" to team.id,
            "teamName" to team.name,
            "taskId" to task.id,
            "actorId" to uid
        )
        recipients.toSet()
            .filter { it != uid && team.member(it) != null }
            .forEach { recipient ->
                notifications(recipient).document().set(data)
                    .addOnFailureListener { Log.w(TAG, "Não foi possível enviar a notificação.", it) }
            }
    }

    internal fun assignedContent(actor: String, task: Workspace.Task, team: Workspace.Team) =
        "$actor atribuiu a você a tarefa \"${task.title}\" na equipe ${team.name}."

    internal fun statusContent(actor: String, task: Workspace.Task) =
        "$actor alterou a situação da tarefa \"${task.title}\" para " +
            (if (task.status == Workspace.Status.FINISHED) "finalizada." else "em andamento.")

    internal fun commentContent(actor: String, task: Workspace.Task) =
        "$actor comentou na tarefa \"${task.title}\"."

    private fun registerInvite(uid: String, invitation: Workspace.Invitation): Task<Void> {
        val ref = notifications(uid).document(inviteId(invitation.teamId))
        return ref.get().continueWithTask { snapshot ->
            val existing = snapshot.result
            when {
                !existing.exists() -> ref.set(
                    mapOf(
                        "type" to TYPE_INVITE,
                        "content" to "Você foi convidado(a) para entrar na equipe ${invitation.teamName}."
                            .take(MAX_CONTENT_LENGTH),
                        "seen" to false,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "teamId" to invitation.teamId,
                        "teamName" to invitation.teamName,
                        "taskId" to "",
                        "actorId" to uid
                    )
                )
                // Já respondido antes: é um novo convite para a mesma equipe.
                existing.getString("type") != TYPE_INVITE -> ref.update(
                    mapOf(
                        "type" to TYPE_INVITE,
                        "seen" to false,
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                )
                else -> Tasks.forResult(null)
            }
        }
    }

    private fun toNotification(document: DocumentSnapshot) = Workspace.Notification(
        id = document.id,
        type = document.getString("type").orEmpty(),
        content = document.getString("content").orEmpty(),
        seen = document.getBoolean("seen") ?: false,
        createdAt = document.getTimestamp("createdAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)?.toDate(),
        teamId = document.getString("teamId").orEmpty(),
        teamName = document.getString("teamName").orEmpty(),
        taskId = document.getString("taskId").orEmpty()
    )

    private const val TAG = "SyncroNotifications"
    private const val USERS_COLLECTION = "users"
    private const val NOTIFICATIONS_COLLECTION = "notifications"
    private const val MAX_LOADED = 50
    private const val MAX_CONTENT_LENGTH = 500
    private const val MAX_BATCH_SIZE = 450
}
