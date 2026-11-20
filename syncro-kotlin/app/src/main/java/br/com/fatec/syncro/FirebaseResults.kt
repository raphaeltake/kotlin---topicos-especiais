package br.com.fatec.syncro

import com.google.android.gms.tasks.RuntimeExecutionException
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.firestore.DocumentSnapshot

/** Auxiliares compartilhados por FirebaseTeamRepository e FirebaseTaskRepository. */

/** Converte o resultado da tarefa, atualizando o cache (onSuccess) só em caso de sucesso. */
internal fun <T, R> deliver(task: Task<T>, onComplete: (Result<R>) -> Unit, onSuccess: (T) -> R) {
    if (task.isSuccessful) {
        onComplete(Result.success(onSuccess(task.result)))
    } else {
        onComplete(Result.failure(unwrapFirebaseError(task.exception ?: IllegalStateException())))
    }
}

/** Ler `task.result` de uma tarefa com falha embrulha o erro original. */
internal fun unwrapFirebaseError(error: Throwable): Throwable {
    var current = error
    while (current is RuntimeExecutionException && current.cause != null) {
        current = current.cause!!
    }
    return current
}

internal fun notSignedIn(): Throwable =
    FirebaseAuthInvalidUserException("NO_CURRENT_USER", "Usuário não autenticado.")

internal fun DocumentSnapshot.stringList(field: String): List<String> =
    (get(field) as? List<*>).orEmpty().filterIsInstance<String>()
