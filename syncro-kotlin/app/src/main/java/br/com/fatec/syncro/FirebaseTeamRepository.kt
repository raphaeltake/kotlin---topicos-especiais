package br.com.fatec.syncro

import android.content.Context
import androidx.annotation.StringRes
import com.google.android.gms.tasks.RuntimeExecutionException
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Transaction
import java.security.SecureRandom

/**
 * Equipes no Cloud Firestore.
 *
 * - `teams/{teamId}`: um documento por equipe com nome, descrição, código,
 *   criador (`createdBy`), `memberIds`, `adminIds`, `memberNames` ({uid: nome})
 *   e `invitedEmails` (convites pendentes).
 * - `teamCodes/{code}`: aponta o código de convite para a equipe. Como o
 *   documento só pode ser criado uma vez, dois times nunca têm o mesmo código.
 *
 * Cada operação altera só os campos que lhe dizem respeito, usando
 * arrayUnion/arrayRemove para não sobrescrever mudanças feitas ao mesmo tempo
 * por outro membro. As regras em `firestore.rules` validam cada uma delas.
 *
 * Depois de cada gravação bem-sucedida, a cópia em [Workspace] é atualizada,
 * e é dela que as telas leem.
 */
object FirebaseTeamRepository {
    const val MAX_NAME_LENGTH = 100
    const val MAX_DESCRIPTION_LENGTH = 1000

    /** Erro previsto, com a mensagem já pronta para a tela. */
    class TeamException(@param:StringRes val messageRes: Int) : Exception()

    private val auth: FirebaseAuth
        get() = FirebaseAuth.getInstance()

    private val firestore: FirebaseFirestore
        get() = FirebaseFirestore.getInstance()

    private val teams: CollectionReference
        get() = firestore.collection(TEAMS_COLLECTION)

    private val teamCodes: CollectionReference
        get() = firestore.collection(TEAM_CODES_COLLECTION)

    private val random = SecureRandom()

    /** Busca as equipes do usuário e os convites pendentes para o e-mail dele. */
    fun loadTeams(onComplete: (Result<Unit>) -> Unit) {
        val user = auth.currentUser ?: return onComplete(Result.failure(notSignedIn()))
        val email = user.email?.lowercase().orEmpty()

        val teamsTask = teams.whereArrayContains("memberIds", user.uid).get()
        val invitesTask = if (email.isEmpty()) null
            else teams.whereArrayContains("invitedEmails", email).get()

        Tasks.whenAllComplete(listOfNotNull(teamsTask, invitesTask)).addOnCompleteListener {
            if (!teamsTask.isSuccessful) {
                onComplete(Result.failure(teamsTask.exception ?: IllegalStateException()))
                return@addOnCompleteListener
            }

            val loaded = teamsTask.result.documents
                .sortedWith(compareBy(nullsLast()) { it.getTimestamp("createdAt") })
                .map { toTeam(it, user.uid) }
            Workspace.teams.clear()
            Workspace.teams.addAll(loaded)
            Workspace.teamsLoaded = true

            if (invitesTask?.isSuccessful == true) {
                val memberOf = loaded.map { it.id }.toSet()
                val invitations = invitesTask.result.documents
                    .filter { it.id !in memberOf }
                    .map { Workspace.Invitation(it.id, it.getString("name").orEmpty()) }
                if (invitations.any { new -> Workspace.invitations.none { it.teamId == new.teamId } }) {
                    Workspace.hasUnreadNotifications = true
                }
                Workspace.invitations.clear()
                Workspace.invitations.addAll(invitations)
            }
            onComplete(Result.success(Unit))
        }
    }

    fun createTeam(
        name: String,
        description: String = "",
        onComplete: (Result<Workspace.Team>) -> Unit
    ) {
        val user = auth.currentUser ?: return onComplete(Result.failure(notSignedIn()))
        val teamRef = teams.document()
        val userRef = firestore.collection(USERS_COLLECTION).document(user.uid)

        firestore.runTransaction { transaction ->
            // Em uma transação, todas as leituras precisam vir antes das escritas.
            val code = findUnusedCode(transaction)
            val memberName = memberName(user, transaction.get(userRef))
            val now = FieldValue.serverTimestamp()

            transaction.set(
                teamRef,
                mapOf(
                    "name" to name,
                    "description" to description,
                    "code" to code,
                    "createdBy" to user.uid,
                    "memberIds" to listOf(user.uid),
                    "adminIds" to emptyList<String>(),
                    "memberNames" to mapOf(user.uid to memberName),
                    "invitedEmails" to emptyList<String>(),
                    "createdAt" to now,
                    "updatedAt" to now
                )
            )
            transaction.set(
                teamCodes.document(code),
                mapOf("teamId" to teamRef.id, "createdAt" to now)
            )
            code to memberName
        }.addOnCompleteListener { task ->
            deliver(task, onComplete) { (code, memberName) ->
                Workspace.Team(
                    id = teamRef.id,
                    name = name,
                    description = description,
                    admin = true,
                    code = code,
                    tasks = mutableListOf(),
                    members = mutableListOf(Workspace.Member(memberName, CREATOR_LABEL, user.uid)),
                    createdBy = user.uid
                ).also { Workspace.teams.add(it) }
            }
        }
    }

    fun updateTeam(
        team: Workspace.Team,
        name: String,
        description: String,
        onComplete: (Result<Unit>) -> Unit
    ) {
        teams.document(team.id).update(
            mapOf(
                "name" to name,
                "description" to description,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).addOnCompleteListener { task ->
            deliver(task, onComplete) {
                team.name = name
                team.description = description
            }
        }
    }

    /** Apaga a equipe e libera o código. Só o criador tem permissão. */
    fun deleteTeam(team: Workspace.Team, onComplete: (Result<Unit>) -> Unit) {
        val batch = firestore.batch().delete(teams.document(team.id))
        if (team.code.isNotBlank()) batch.delete(teamCodes.document(team.code))
        batch.commit()
            .addOnCompleteListener { task ->
                deliver(task, onComplete) { Workspace.teams.remove(team) }
            }
    }

    fun inviteMembers(
        team: Workspace.Team,
        emails: List<String>,
        onComplete: (Result<Unit>) -> Unit
    ) {
        val normalized = emails.map { it.trim().lowercase() }.distinct()
        teams.document(team.id)
            .update("invitedEmails", FieldValue.arrayUnion(*normalized.toTypedArray()))
            .addOnCompleteListener { task -> deliver(task, onComplete) {} }
    }

    fun joinByCode(code: String, onComplete: (Result<Workspace.Team>) -> Unit) {
        val user = auth.currentUser ?: return onComplete(Result.failure(notSignedIn()))
        val normalized = code.trim().uppercase()
        if (!CODE_PATTERN.matches(normalized)) {
            return onComplete(Result.failure(TeamException(R.string.screen_team_not_found)))
        }

        teamCodes.document(normalized).get().continueWithTask { task ->
            val teamId = task.result.getString("teamId")
                ?: throw TeamException(R.string.screen_team_not_found)
            Workspace.team(teamId)?.let { Tasks.forResult(it) } ?: join(user, teamId)
        }.addOnCompleteListener { task -> deliver(task, onComplete) { it } }
    }

    fun acceptInvitation(
        invitation: Workspace.Invitation,
        onComplete: (Result<Workspace.Team>) -> Unit
    ) {
        val user = auth.currentUser ?: return onComplete(Result.failure(notSignedIn()))
        join(user, invitation.teamId).addOnCompleteListener { task ->
            deliver(task, onComplete) { team ->
                Workspace.invitations.removeAll { it.teamId == invitation.teamId }
                team
            }
        }
    }

    fun declineInvitation(
        invitation: Workspace.Invitation,
        onComplete: (Result<Unit>) -> Unit
    ) {
        val user = auth.currentUser ?: return onComplete(Result.failure(notSignedIn()))
        teams.document(invitation.teamId)
            .update("invitedEmails", FieldValue.arrayRemove(user.email?.lowercase().orEmpty()))
            .addOnCompleteListener { task ->
                deliver(task, onComplete) {
                    Workspace.invitations.removeAll { it.teamId == invitation.teamId }
                }
            }
    }

    fun leaveTeam(team: Workspace.Team, onComplete: (Result<Unit>) -> Unit) {
        val user = auth.currentUser ?: return onComplete(Result.failure(notSignedIn()))
        if (team.createdBy == user.uid) {
            return onComplete(Result.failure(TeamException(R.string.team_creator_cannot_leave)))
        }
        removeFromTeam(team.id, user.uid).addOnCompleteListener { task ->
            deliver(task, onComplete) { Workspace.teams.remove(team) }
        }
    }

    fun removeMember(
        team: Workspace.Team,
        member: Workspace.Member,
        onComplete: (Result<Unit>) -> Unit
    ) {
        removeFromTeam(team.id, member.uid).addOnCompleteListener { task ->
            deliver(task, onComplete) { team.members.remove(member) }
        }
    }

    fun setAdmin(
        team: Workspace.Team,
        member: Workspace.Member,
        admin: Boolean,
        onComplete: (Result<Unit>) -> Unit
    ) {
        val change = if (admin) FieldValue.arrayUnion(member.uid) else FieldValue.arrayRemove(member.uid)
        teams.document(team.id).update("adminIds", change).addOnCompleteListener { task ->
            deliver(task, onComplete) { member.role = if (admin) ADMIN_LABEL else MEMBER_LABEL }
        }
    }

    /** Atualiza o nome exibido do usuário em todas as equipes dele, após editar o perfil. */
    fun syncMemberName(name: String) {
        val uid = auth.currentUser?.uid ?: return
        val memberName = name.trim().take(MAX_NAME_LENGTH).ifEmpty { return }
        Workspace.teams.forEach { team ->
            val member = team.members.firstOrNull { it.uid == uid } ?: return@forEach
            if (member.name == memberName) return@forEach
            teams.document(team.id)
                .update(FieldPath.of("memberNames", uid), memberName)
                .addOnSuccessListener { member.name = memberName }
        }
    }

    fun errorMessage(context: Context, error: Throwable): String {
        val cause = unwrap(error)
        return when {
            cause is TeamException -> context.getString(cause.messageRes)
            cause is FirebaseFirestoreException &&
                cause.code == FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                context.getString(R.string.team_permission_denied)
            cause is FirebaseFirestoreException &&
                cause.code == FirebaseFirestoreException.Code.NOT_FOUND ->
                context.getString(R.string.team_no_longer_exists)
            else -> FirebaseAccountRepository.errorMessage(context, cause)
        }
    }

    /** Entra como membro comum, retirando o próprio e-mail dos convites pendentes. */
    private fun join(user: FirebaseUser, teamId: String): Task<Workspace.Team> {
        val teamRef = teams.document(teamId)
        val profileRef = firestore.collection(USERS_COLLECTION).document(user.uid)
        return profileRef.get().continueWithTask { profileTask ->
            teamRef.update(
                FieldPath.of("memberIds"), FieldValue.arrayUnion(user.uid),
                FieldPath.of("memberNames", user.uid),
                memberName(user, if (profileTask.isSuccessful) profileTask.result else null),
                FieldPath.of("invitedEmails"), FieldValue.arrayRemove(user.email?.lowercase().orEmpty())
            )
        }.continueWithTask { updateTask ->
            if (!updateTask.isSuccessful) throw updateTask.exception ?: IllegalStateException()
            teamRef.get()
        }.continueWith { task ->
            val team = toTeam(task.result, user.uid)
            Workspace.teams.removeAll { it.id == team.id }
            Workspace.teams.add(team)
            team
        }
    }

    /** Remove um membro: tira o uid das listas e o nome do mapa, em uma só gravação. */
    private fun removeFromTeam(teamId: String, uid: String): Task<Void> =
        teams.document(teamId).update(
            FieldPath.of("memberIds"), FieldValue.arrayRemove(uid),
            FieldPath.of("adminIds"), FieldValue.arrayRemove(uid),
            FieldPath.of("memberNames", uid), FieldValue.delete()
        )

    private fun toTeam(document: DocumentSnapshot, uid: String): Workspace.Team {
        val previous = Workspace.team(document.id)
        val createdBy = document.getString("createdBy").orEmpty()
        val memberIds = document.stringList("memberIds")
        val adminIds = document.stringList("adminIds").toSet()
        val names = document.get("memberNames") as? Map<*, *> ?: emptyMap<String, Any>()

        // O criador aparece primeiro; os demais, na ordem em que entraram.
        val members = memberIds.sortedBy { it != createdBy }.map { memberId ->
            Workspace.Member(
                name = (names[memberId] as? String)?.takeIf { it.isNotBlank() } ?: DEFAULT_MEMBER_NAME,
                role = when (memberId) {
                    createdBy -> CREATOR_LABEL
                    in adminIds -> ADMIN_LABEL
                    else -> MEMBER_LABEL
                },
                uid = memberId
            )
        }
        val previousIds = previous?.members?.map { it.uid }?.toSet()
        val someoneJoined = previousIds != null && memberIds.any { it != uid && it !in previousIds }

        return Workspace.Team(
            id = document.id,
            name = document.getString("name").orEmpty(),
            description = document.getString("description").orEmpty(),
            admin = uid == createdBy || uid in adminIds,
            code = document.getString("code").orEmpty(),
            // Tarefas ainda não estão no Firestore: preserva as criadas nesta sessão.
            tasks = previous?.tasks ?: mutableListOf(),
            members = members.toMutableList(),
            hasNewTasks = previous?.hasNewTasks ?: false,
            hasNewMembers = previous?.hasNewMembers == true || someoneJoined,
            createdBy = createdBy
        )
    }

    private fun DocumentSnapshot.stringList(field: String): List<String> =
        (get(field) as? List<*>).orEmpty().filterIsInstance<String>()

    private fun memberName(user: FirebaseUser, profile: DocumentSnapshot?): String =
        listOf(profile?.getString("name"), user.displayName, user.email)
            .firstOrNull { !it.isNullOrBlank() }
            ?.trim()?.take(MAX_NAME_LENGTH)
            ?: DEFAULT_MEMBER_NAME

    /**
     * Sorteia códigos até achar um que ainda não exista. Se outro usuário
     * reservar o mesmo código antes do commit, o Firestore repete a transação.
     */
    private fun findUnusedCode(transaction: Transaction): String {
        repeat(MAX_CODE_ATTEMPTS) {
            val code = randomCode()
            if (!transaction.get(teamCodes.document(code)).exists()) return code
        }
        throw IllegalStateException("Não foi possível gerar um código único para a equipe.")
    }

    /** Sem 0/O e 1/I, que se confundem ao digitar o código. */
    private fun randomCode() = buildString(CODE_LENGTH) {
        repeat(CODE_LENGTH) { append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]) }
    }

    /** Converte o resultado da tarefa, atualizando o cache só em caso de sucesso. */
    private fun <T, R> deliver(task: Task<T>, onComplete: (Result<R>) -> Unit, onSuccess: (T) -> R) {
        if (task.isSuccessful) {
            onComplete(Result.success(onSuccess(task.result)))
        } else {
            onComplete(Result.failure(unwrap(task.exception ?: IllegalStateException())))
        }
    }

    private fun unwrap(error: Throwable): Throwable {
        var current = error
        while (current is RuntimeExecutionException && current.cause != null) {
            current = current.cause!!
        }
        return current
    }

    private fun notSignedIn() =
        FirebaseAuthInvalidUserException("NO_CURRENT_USER", "Usuário não autenticado.")

    private const val TEAMS_COLLECTION = "teams"
    private const val TEAM_CODES_COLLECTION = "teamCodes"
    private const val USERS_COLLECTION = "users"

    private const val CREATOR_LABEL = "Criador(a)"
    private const val ADMIN_LABEL = "Administrador(a)"
    private const val MEMBER_LABEL = "Membro"
    private const val DEFAULT_MEMBER_NAME = "Usuário"

    private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private const val CODE_LENGTH = 8
    private const val MAX_CODE_ATTEMPTS = 5
    private val CODE_PATTERN = Regex("[A-Z0-9]{8}")
}
