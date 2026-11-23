package br.com.fatec.syncro

import android.content.Context
import androidx.annotation.StringRes
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Transaction
import java.security.SecureRandom

/**
 * Equipes no Cloud Firestore.
 *
 * - `teams/{teamId}`: um documento por equipe com nome, descrição, código,
 *   criador (`createdBy`), `memberIds`, `adminIds`, `memberNames` ({uid: nome}),
 *   `memberEmails` ({uid: e-mail}, para não convidar quem já participa) e
 *   `invitedEmails` (convites pendentes).
 * - `teamCodes/{code}`: aponta o código de convite para a equipe e guarda nome
 *   e descrição, exibidos antes de confirmar o ingresso. Como a equipe
 *   apontada nunca muda, dois times nunca têm o mesmo código.
 *
 * Cada operação altera só os campos que lhe dizem respeito, usando
 * arrayUnion/arrayRemove para não sobrescrever mudanças feitas ao mesmo tempo
 * por outro membro. As regras em `firestore.rules` validam cada uma delas.
 *
 * As equipes chegam em tempo real por [listenTeams] e [listenInvitations].
 * Depois de cada gravação bem-sucedida, a cópia em [Workspace] também é
 * atualizada, e é dela que as telas leem.
 */
object FirebaseTeamRepository {
    const val MAX_NAME_LENGTH = 100
    const val MAX_DESCRIPTION_LENGTH = 1000
    /** RNF12: cada usuário participa de até 50 equipes. */
    const val MAX_TEAMS = 50

    /** Erro previsto, com a mensagem já pronta para a tela. */
    class TeamException(@param:StringRes val messageRes: Int, vararg val args: Any) : Exception()

    /** Equipe encontrada pelo código, exibida para o usuário confirmar o ingresso (UC06). */
    data class TeamPreview(val teamId: String, val name: String, val description: String)

    private val auth: FirebaseAuth
        get() = FirebaseAuth.getInstance()

    private val firestore: FirebaseFirestore
        get() = FirebaseFirestore.getInstance()

    private val teams: CollectionReference
        get() = firestore.collection(TEAMS_COLLECTION)

    private val teamCodes: CollectionReference
        get() = firestore.collection(TEAM_CODES_COLLECTION)

    private val random = SecureRandom()

    /**
     * Escuta em tempo real as equipes do usuário (veja [LiveUpdates]). Cada
     * alteração, feita por ele ou por outro membro, substitui a cópia em
     * [Workspace.teams].
     */
    internal fun listenTeams(
        uid: String,
        onChange: () -> Unit,
        onError: (Exception) -> Unit
    ): ListenerRegistration =
        teams.whereArrayContains("memberIds", uid).addSnapshotListener { snapshot, error ->
            if (snapshot == null) return@addSnapshotListener onError(error ?: IllegalStateException())
            val loaded = snapshot.documents
                .sortedWith(compareBy(nullsLast()) { it.getTimestamp("createdAt", ESTIMATE) })
                .map { toTeam(it, uid) }
            Workspace.teams.clear()
            Workspace.teams.addAll(loaded)
            Workspace.teamsLoaded = true
            syncMemberEmail(uid)
            onChange()
        }

    /**
     * Um novo e-mail só vale depois de confirmado pelo link (veja
     * [FirebaseAccountRepository.updateAccount]). Na primeira carga das equipes
     * com o e-mail já trocado, corrige o registro do usuário em cada uma delas.
     */
    private fun syncMemberEmail(uid: String) {
        val user = auth.currentUser?.takeIf { it.uid == uid } ?: return
        val email = userEmail(user).ifEmpty { return }
        Workspace.teams.forEach { team ->
            val member = team.members.firstOrNull { it.uid == uid } ?: return@forEach
            if (member.email != email) {
                teams.document(team.id).update(FieldPath.of("memberEmails", uid), email)
            }
        }
    }

    /**
     * Escuta os convites pendentes para o e-mail do usuário e registra o aviso
     * de cada convite novo (quem convida conhece só o e-mail).
     */
    internal fun listenInvitations(
        email: String,
        onChange: () -> Unit,
        onError: (Exception) -> Unit
    ): ListenerRegistration =
        teams.whereArrayContains("invitedEmails", email).addSnapshotListener { snapshot, error ->
            if (snapshot == null) return@addSnapshotListener onError(error ?: IllegalStateException())
            val known = Workspace.invitations.map { it.teamId }.toSet()
            val invitations = snapshot.documents
                .filter { Workspace.team(it.id) == null }
                .map { Workspace.Invitation(it.id, it.getString("name").orEmpty()) }
            Workspace.invitations.clear()
            Workspace.invitations.addAll(invitations)
            FirebaseNotificationRepository.registerInvites(invitations.filter { it.teamId !in known })
            onChange()
        }

    fun createTeam(
        name: String,
        description: String = "",
        onComplete: (Result<Workspace.Team>) -> Unit
    ) {
        val user = auth.currentUser ?: return onComplete(Result.failure(notSignedIn()))
        if (Workspace.teams.size >= MAX_TEAMS) return onComplete(Result.failure(teamLimitReached()))
        val teamRef = teams.document()
        val userRef = firestore.collection(USERS_COLLECTION).document(user.uid)
        val email = userEmail(user)

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
                    "memberEmails" to mapOf(user.uid to email),
                    "invitedEmails" to emptyList<String>(),
                    "createdAt" to now,
                    "updatedAt" to now
                )
            )
            transaction.set(
                teamCodes.document(code),
                mapOf(
                    "teamId" to teamRef.id,
                    "name" to name,
                    "description" to description,
                    "createdAt" to now
                )
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
                    members = mutableListOf(Workspace.Member(memberName, CREATOR_LABEL, user.uid, email)),
                    createdBy = user.uid,
                    tasksLoaded = true
                ).also { created ->
                    // A escuta em tempo real pode ter entregado a equipe antes.
                    Workspace.teams.removeAll { it.id == created.id }
                    Workspace.teams.add(created)
                }
            }
        }
    }

    /** Só o criador edita (UC04). A prévia em teamCodes muda no mesmo lote. */
    fun updateTeam(
        team: Workspace.Team,
        name: String,
        description: String,
        onComplete: (Result<Unit>) -> Unit
    ) {
        firestore.batch()
            .update(
                teams.document(team.id),
                mapOf(
                    "name" to name,
                    "description" to description,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            .update(teamCodes.document(team.code), mapOf("name" to name, "description" to description))
            .commit()
            .addOnCompleteListener { task ->
            deliver(task, onComplete) {
                team.name = name
                team.description = description
            }
        }
    }

    /**
     * Apaga tarefas, comentários e histórico, depois a equipe e o código. Só o
     * criador tem permissão. A equipe vai por último porque as regras das
     * tarefas consultam o documento dela.
     */
    fun deleteTeam(team: Workspace.Team, onComplete: (Result<Unit>) -> Unit) {
        FirebaseTaskRepository.deleteAllTeamData(team.id).continueWithTask { tasksDeleted ->
            if (!tasksDeleted.isSuccessful) throw tasksDeleted.exception ?: IllegalStateException()
            val batch = firestore.batch().delete(teams.document(team.id))
            if (team.code.isNotBlank()) batch.delete(teamCodes.document(team.code))
            batch.commit()
        }.addOnCompleteListener { task ->
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
            .addOnCompleteListener { task ->
                deliver(task, onComplete) {
                    team.invitedEmails.addAll(normalized - team.invitedEmails.toSet())
                }
            }
    }

    /**
     * UC06, passo 4: valida o código e devolve a equipe para o usuário
     * confirmar. Quem já participa é avisado e nenhum vínculo novo é criado.
     */
    fun findTeamByCode(code: String, onComplete: (Result<TeamPreview>) -> Unit) {
        if (auth.currentUser == null) return onComplete(Result.failure(notSignedIn()))
        val normalized = code.trim().uppercase()
        if (!CODE_PATTERN.matches(normalized)) {
            return onComplete(Result.failure(TeamException(R.string.screen_team_not_found)))
        }

        teamCodes.document(normalized).get().continueWith { task ->
            val snapshot = task.result
            val teamId = snapshot.getString("teamId")
                ?: throw TeamException(R.string.screen_team_not_found)
            if (Workspace.team(teamId) != null) throw TeamException(R.string.team_already_member)
            TeamPreview(
                teamId,
                snapshot.getString("name").orEmpty(),
                snapshot.getString("description").orEmpty()
            )
        }.addOnCompleteListener { task -> deliver(task, onComplete) { it } }
    }

    /** UC06, passos 5 e 6: entra na equipe confirmada como membro comum. */
    fun joinTeam(preview: TeamPreview, onComplete: (Result<Workspace.Team>) -> Unit) {
        val user = auth.currentUser ?: return onComplete(Result.failure(notSignedIn()))
        Workspace.team(preview.teamId)?.let {
            return onComplete(Result.failure(TeamException(R.string.team_already_member)))
        }
        if (Workspace.teams.size >= MAX_TEAMS) return onComplete(Result.failure(teamLimitReached()))
        join(user, preview.teamId).addOnCompleteListener { task -> deliver(task, onComplete) { it } }
    }

    fun acceptInvitation(
        invitation: Workspace.Invitation,
        onComplete: (Result<Workspace.Team>) -> Unit
    ) {
        val user = auth.currentUser ?: return onComplete(Result.failure(notSignedIn()))
        if (Workspace.teams.size >= MAX_TEAMS) return onComplete(Result.failure(teamLimitReached()))
        join(user, invitation.teamId).addOnCompleteListener { task ->
            deliver(task, onComplete) { team ->
                Workspace.invitations.removeAll { it.teamId == invitation.teamId }
                FirebaseNotificationRepository.answerInvite(invitation.teamId, accepted = true)
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
            .update("invitedEmails", FieldValue.arrayRemove(userEmail(user)))
            .addOnCompleteListener { task ->
                deliver(task, onComplete) {
                    Workspace.invitations.removeAll { it.teamId == invitation.teamId }
                    FirebaseNotificationRepository.answerInvite(invitation.teamId, accepted = false)
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

    /** Atualiza o nome e o e-mail do usuário em todas as equipes dele, após editar o perfil. */
    fun syncMemberProfile(name: String) {
        val user = auth.currentUser ?: return
        val memberName = name.trim().take(MAX_NAME_LENGTH).ifEmpty { return }
        val email = userEmail(user)
        Workspace.teams.forEach { team ->
            val index = team.members.indexOfFirst { it.uid == user.uid }
            if (index < 0) return@forEach
            val member = team.members[index]
            if (member.name == memberName && member.email == email) return@forEach
            teams.document(team.id)
                .update(
                    FieldPath.of("memberNames", user.uid), memberName,
                    FieldPath.of("memberEmails", user.uid), email
                )
                .addOnSuccessListener { team.members[index] = member.copy(name = memberName, email = email) }
        }
    }

    fun errorMessage(context: Context, error: Throwable): String {
        val cause = unwrapFirebaseError(error)
        return when {
            cause is TeamException -> context.getString(cause.messageRes, *cause.args)
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
                FieldPath.of("memberEmails", user.uid), userEmail(user),
                FieldPath.of("invitedEmails"), FieldValue.arrayRemove(userEmail(user))
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

    /** Remove um membro: tira o uid das listas e o nome e o e-mail dos mapas, em uma só gravação. */
    private fun removeFromTeam(teamId: String, uid: String): Task<Void> =
        teams.document(teamId).update(
            FieldPath.of("memberIds"), FieldValue.arrayRemove(uid),
            FieldPath.of("adminIds"), FieldValue.arrayRemove(uid),
            FieldPath.of("memberNames", uid), FieldValue.delete(),
            FieldPath.of("memberEmails", uid), FieldValue.delete()
        )

    private fun toTeam(document: DocumentSnapshot, uid: String): Workspace.Team {
        val previous = Workspace.team(document.id)
        val createdBy = document.getString("createdBy").orEmpty()
        val memberIds = document.stringList("memberIds")
        val adminIds = document.stringList("adminIds").toSet()
        val names = document.get("memberNames") as? Map<*, *> ?: emptyMap<String, Any>()
        val emails = document.get("memberEmails") as? Map<*, *> ?: emptyMap<String, Any>()

        // O criador aparece primeiro; os demais, na ordem em que entraram.
        val members = memberIds.sortedBy { it != createdBy }.map { memberId ->
            Workspace.Member(
                name = (names[memberId] as? String)?.takeIf { it.isNotBlank() } ?: DEFAULT_MEMBER_NAME,
                role = when (memberId) {
                    createdBy -> CREATOR_LABEL
                    in adminIds -> ADMIN_LABEL
                    else -> MEMBER_LABEL
                },
                uid = memberId,
                email = emails[memberId] as? String ?: ""
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
            // As tarefas são buscadas à parte, por FirebaseTaskRepository.
            tasks = previous?.tasks ?: mutableListOf(),
            members = members.toMutableList(),
            hasNewTasks = previous?.hasNewTasks ?: false,
            hasNewMembers = previous?.hasNewMembers == true || someoneJoined,
            createdBy = createdBy,
            tasksLoaded = previous?.tasksLoaded ?: false,
            invitedEmails = document.stringList("invitedEmails").toMutableList()
        )
    }

    /** As regras comparam com o e-mail da conta em minúsculas. */
    private fun userEmail(user: FirebaseUser) = user.email?.lowercase().orEmpty()

    private fun teamLimitReached() = TeamException(R.string.team_limit_reached, MAX_TEAMS)

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
