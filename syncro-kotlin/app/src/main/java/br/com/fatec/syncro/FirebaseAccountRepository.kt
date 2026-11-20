package br.com.fatec.syncro

import android.content.Context
import com.google.android.gms.tasks.RuntimeExecutionException
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
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
                    Workspace.profile.name = name
                    Workspace.profile.email = email
                    Workspace.profile.password = ""
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

    fun signOut() {
        auth.signOut()
        Workspace.profile.name = "Você"
        Workspace.profile.email = ""
        Workspace.profile.password = ""
    }

    fun updateAccount(
        name: String,
        email: String,
        newPassword: String,
        onComplete: (Throwable?) -> Unit
    ) {
        val user = auth.currentUser
        if (user == null) {
            onComplete(FirebaseAuthInvalidUserException("NO_CURRENT_USER", "Usuário não autenticado."))
            return
        }

        val profile = UserProfileChangeRequest.Builder().setDisplayName(name).build()
        var updateTask = user.updateProfile(profile)

        if (!user.email.equals(email, ignoreCase = true)) {
            @Suppress("DEPRECATION")
            updateTask = updateTask.continueWithTask { task ->
                if (!task.isSuccessful) {
                    throw task.exception ?: IllegalStateException("Falha ao atualizar o nome.")
                }
                user.updateEmail(email)
            }
        }

        if (newPassword.isNotEmpty()) {
            updateTask = updateTask.continueWithTask { task ->
                if (!task.isSuccessful) {
                    throw task.exception ?: IllegalStateException("Falha ao atualizar o perfil.")
                }
                user.updatePassword(newPassword)
            }
        }

        updateTask.continueWithTask { task ->
            if (!task.isSuccessful) {
                throw task.exception ?: IllegalStateException("Falha ao atualizar a conta.")
            }
            firestore.collection(USERS_COLLECTION).document(user.uid).set(
                mapOf(
                    "name" to name,
                    "email" to email,
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            )
        }.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                syncLocalProfile(user)
                Workspace.profile.name = name
                Workspace.profile.email = email
                Workspace.profile.password = ""
                onComplete(null)
            } else {
                onComplete(
                    task.exception?.let(::unwrapTaskError)
                        ?: IllegalStateException("Falha ao atualizar a conta.")
                )
            }
        }
    }

    private fun syncLocalProfile(user: FirebaseUser) {
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
                document.getString("email")?.takeIf { it.isNotBlank() }?.let {
                    Workspace.profile.email = it
                }
            }
    }

    fun errorMessage(context: Context, error: Throwable): String {
        val cause = unwrapTaskError(error)
        return context.getString(
        when (cause) {
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
}
