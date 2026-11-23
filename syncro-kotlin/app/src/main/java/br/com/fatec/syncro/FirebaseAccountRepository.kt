package br.com.fatec.syncro

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.tasks.RuntimeExecutionException
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions

object FirebaseAccountRepository {
    private val auth: FirebaseAuth
        get() = FirebaseAuth.getInstance()

    private val firestore: FirebaseFirestore
        get() = FirebaseFirestore.getInstance()

    fun restoreSession(): Boolean {
        val user = auth.currentUser ?: return false
        syncLocalProfile(user)
        loadRemoteProfile(user)
        PushNotifications.register()
        return true
    }

    fun signIn(email: String, password: String, onComplete: (Throwable?) -> Unit) {
        auth.signInWithEmailAndPassword(email, password).addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                onComplete(task.exception ?: IllegalStateException("Falha ao autenticar."))
                return@addOnCompleteListener
            }

            val user = task.result.user
            if (user == null) {
                onComplete(IllegalStateException("Firebase não retornou o usuário autenticado."))
                return@addOnCompleteListener
            }
            syncLocalProfile(user)
            ensureUserDocument(user)
            loadRemoteProfile(user)
            PushNotifications.register()
            onComplete(null)
        }
    }

    fun createAccount(
        name: String,
        email: String,
        password: String,
        onComplete: (Throwable?) -> Unit
    ) {
        // A criação de conta deve começar sem uma sessão anterior. Caso contrário,
        // uma falha como EMAIL_ALREADY_IN_USE mantém o usuário antigo autenticado.
        auth.signOut()
        auth.createUserWithEmailAndPassword(email, password).addOnCompleteListener { authTask ->
            if (!authTask.isSuccessful) {
                onComplete(authTask.exception ?: IllegalStateException("Falha ao criar a conta."))
                return@addOnCompleteListener
            }

            val user = authTask.result.user
            if (user == null) {
                onComplete(IllegalStateException("Firebase não retornou o usuário criado."))
                return@addOnCompleteListener
            }
            val profile = UserProfileChangeRequest.Builder().setDisplayName(name).build()
            user.updateProfile(profile).continueWithTask { profileTask ->
                if (!profileTask.isSuccessful) {
                    throw profileTask.exception
                        ?: IllegalStateException("Falha ao atualizar o perfil.")
                }

                firestore.collection(USERS_COLLECTION).document(user.uid).set(
                    mapOf(
                        "name" to name,
                        "email" to email,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }.addOnCompleteListener { profileTask ->
                if (profileTask.isSuccessful) {
                    syncLocalProfile(user)
                    Workspace.profile.name = name
                    Workspace.profile.email = email
                    Workspace.profile.password = ""
                    PushNotifications.register()
                    onComplete(null)
                } else {
                    val error = profileTask.exception
                        ?: IllegalStateException("Falha ao salvar o perfil.")
                    // Evita deixar uma conta sem o documento de perfil no Firestore.
                    user.delete().addOnCompleteListener {
                        auth.signOut()
                        onComplete(error)
                    }
                }
            }
        }
    }

    /** Encerra a sessão depois de desvincular o aparelho das notificações push. */
    fun signOut(onComplete: () -> Unit) {
        PushNotifications.unregister {
            LiveUpdates.stop()
            auth.signOut()
            Workspace.profile.name = "Você"
            Workspace.profile.email = ""
            Workspace.profile.password = ""
            Workspace.profile.uid = ""
            // Evita que a próxima conta veja as equipes da anterior.
            Workspace.clearTeams()
            onComplete()
        }
    }

    /** A senha atual informada para confirmar a identidade está errada. */
    class WrongPasswordException : Exception("Senha atual incorreta.")

    /** Trocar e-mail ou senha exige confirmar a senha atual (veja [updateAccount]). */
    fun needsCurrentPassword(email: String, newPassword: String): Boolean =
        newPassword.isNotEmpty() || !auth.currentUser?.email.equals(email, ignoreCase = true)

    /**
     * Atualiza nome, e-mail e senha do perfil. O Firebase só troca e-mail ou senha
     * de quem entrou há pouco tempo, então a identidade é confirmada antes com a
     * senha atual. O novo e-mail só passa a valer depois que o usuário abre o link
     * enviado para ele (verifyBeforeUpdateEmail); até lá a conta e o perfil mantêm
     * o e-mail atual. [onComplete] recebe true quando esse link foi enviado.
     */
    fun updateAccount(
        name: String,
        email: String,
        newPassword: String,
        currentPassword: String,
        onComplete: (Result<Boolean>) -> Unit
    ) {
        val user = auth.currentUser
        val currentEmail = user?.email
        if (user == null || currentEmail == null) {
            onComplete(Result.failure(FirebaseAuthInvalidUserException("NO_CURRENT_USER", "Usuário não autenticado.")))
            return
        }
        val changeEmail = !currentEmail.equals(email, ignoreCase = true)
        val changePassword = newPassword.isNotEmpty()

        var chain: Task<Void> = Tasks.forResult(null)
        if (changeEmail || changePassword) {
            chain = user.reauthenticate(EmailAuthProvider.getCredential(currentEmail, currentPassword))
                .continueWithTask { task ->
                    when (val error = task.exception?.let(::unwrapTaskError)) {
                        null -> task
                        is FirebaseAuthInvalidCredentialsException -> throw WrongPasswordException()
                        is Exception -> throw error
                        else -> throw IllegalStateException(error)
                    }
                }
        }
        if (changePassword) chain = chain.onSuccessTask { user.updatePassword(newPassword) }
        if (changeEmail) chain = chain.onSuccessTask { user.verifyBeforeUpdateEmail(email) }
        chain.onSuccessTask {
            user.updateProfile(UserProfileChangeRequest.Builder().setDisplayName(name).build())
        }.onSuccessTask {
            firestore.collection(USERS_COLLECTION).document(user.uid).set(
                mapOf(
                    "name" to name,
                    // Acompanha o e-mail da conta, que só muda após a confirmação.
                    "email" to currentEmail,
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            )
        }.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                syncLocalProfile(user)
                Workspace.profile.name = name
                FirebaseTeamRepository.syncMemberProfile(name)
                onComplete(Result.success(changeEmail))
            } else {
                val error = task.exception?.let(::unwrapTaskError)
                    ?: IllegalStateException("Falha ao atualizar a conta.")
                Log.w(TAG, "Falha ao atualizar a conta", error)
                onComplete(Result.failure(error))
            }
        }
    }

    private fun syncLocalProfile(user: FirebaseUser) {
        if (Workspace.profile.uid != user.uid) Workspace.clearTeams()
        Workspace.profile.uid = user.uid
        Workspace.profile.name = user.displayName?.takeIf { it.isNotBlank() } ?: "Você"
        Workspace.profile.email = user.email.orEmpty()
        Workspace.profile.password = ""
    }

    private fun ensureUserDocument(user: FirebaseUser) {
        val data = mutableMapOf<String, Any>(
            "email" to user.email.orEmpty(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        user.displayName?.takeIf { it.isNotBlank() }?.let { data["name"] = it }
        firestore.collection(USERS_COLLECTION).document(user.uid)
            .set(data, SetOptions.merge())
    }

    private fun loadRemoteProfile(user: FirebaseUser) {
        firestore.collection(USERS_COLLECTION).document(user.uid).get()
            .addOnSuccessListener { document ->
                document.getString("name")?.takeIf { it.isNotBlank() }?.let {
                    Workspace.profile.name = it
                }
                // O e-mail vem sempre da conta: o do documento pode estar
                // desatualizado logo depois de uma troca confirmada pelo link.
            }
    }

    fun errorMessage(context: Context, error: Throwable): String {
        val cause = unwrapTaskError(error)
        return context.getString(
        when (cause) {
            is WrongPasswordException -> R.string.auth_wrong_current_password
            is FirebaseAuthUserCollisionException -> R.string.auth_email_already_in_use
            is FirebaseAuthWeakPasswordException -> R.string.auth_weak_password
            is FirebaseAuthInvalidUserException,
            is FirebaseAuthInvalidCredentialsException -> R.string.auth_invalid_credentials
            is FirebaseNetworkException -> R.string.auth_network_error
            is FirebaseTooManyRequestsException -> R.string.auth_too_many_requests
            is FirebaseAuthRecentLoginRequiredException -> R.string.auth_recent_login_required
            is FirebaseFirestoreException -> when (cause.code) {
                FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                    R.string.firestore_permission_denied
                FirebaseFirestoreException.Code.UNAVAILABLE -> R.string.auth_network_error
                else -> R.string.auth_unknown_error
            }
            else -> R.string.auth_unknown_error
        }
        )
    }

    private fun unwrapTaskError(error: Throwable): Throwable {
        var current = error
        while (current is RuntimeExecutionException && current.cause != null) {
            current = current.cause!!
        }
        return current
    }

    private const val USERS_COLLECTION = "users"
    private const val TAG = "FirebaseAccount"
}
