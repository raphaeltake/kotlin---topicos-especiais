package br.com.fatec.syncro

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.ListenerRegistration

/**
 * Mantém o [Workspace] sincronizado com o Firestore em tempo real enquanto
 * alguma tela do app está visível: equipes, convites, notificações e as
 * tarefas de cada equipe. Qualquer alteração, feita neste aparelho ou por
 * outro membro, chega em instantes e redesenha a tela aberta.
 *
 * As telas avisam quando ficam visíveis ([screenStarted]) e quando deixam de
 * ficar ([screenStopped]). Com o app em segundo plano as escutas param, e os
 * avisos chegam pelas notificações push ([PushNotifications]).
 */
object LiveUpdates {
    /** A tela em primeiro plano, redesenhada quando os dados mudam. */
    var onChange: (() -> Unit)? = null

    /** A tela em primeiro plano, avisada quando a escuta falha (ex.: sem permissão). */
    var onError: ((Exception) -> Unit)? = null

    private const val TAG = "SyncroLive"
    /** Junta alterações seguidas (ex.: as tarefas de várias equipes ao abrir) em um só redesenho. */
    private const val CHANGE_DELAY_MS = 100L

    private var visibleScreens = 0
    private var uid: String? = null
    private val session = mutableListOf<ListenerRegistration>()
    private val taskListeners = mutableMapOf<String, ListenerRegistration>()
    private val mainThread = Handler(Looper.getMainLooper())
    private val dispatch = Runnable { onChange?.invoke() }

    fun screenStarted() {
        visibleScreens++
        listen()
    }

    fun screenStopped() {
        visibleScreens = (visibleScreens - 1).coerceAtLeast(0)
        if (visibleScreens == 0) stop()
    }

    /** Começa a escutar a conta conectada; não faz nada se já estiver escutando. */
    fun listen() {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        if (visibleScreens == 0 || (uid == user.uid && session.isNotEmpty())) return
        stop()
        uid = user.uid
        session += FirebaseTeamRepository.listenTeams(user.uid, ::teamsChanged, ::failed)
        user.email?.lowercase()?.takeIf { it.isNotEmpty() }?.let { email ->
            session += FirebaseTeamRepository.listenInvitations(email, ::changed, ::failed)
        }
        session += FirebaseNotificationRepository.listen(user.uid, ::changed, ::failed)
    }

    /** Para todas as escutas (ao sair da conta ou com o app em segundo plano). */
    fun stop() {
        session.forEach { it.remove() }
        session.clear()
        taskListeners.values.forEach { it.remove() }
        taskListeners.clear()
        uid = null
        mainThread.removeCallbacks(dispatch)
    }

    /** Pede o redesenho da tela aberta. */
    fun changed() {
        mainThread.removeCallbacks(dispatch)
        mainThread.postDelayed(dispatch, CHANGE_DELAY_MS)
    }

    /** Acompanha as tarefas de cada equipe em que o usuário está, e só delas. */
    private fun teamsChanged() {
        val ids = Workspace.teams.map { it.id }.toSet()
        (taskListeners.keys - ids).forEach { taskListeners.remove(it)?.remove() }
        (ids - taskListeners.keys).forEach { teamId ->
            taskListeners[teamId] = FirebaseTaskRepository.listenTasks(teamId, ::changed) { error ->
                // Ao sair (ou ser removido) da equipe, a leitura deixa de ser permitida.
                Log.w(TAG, "Escuta das tarefas da equipe $teamId encerrada.", error)
                taskListeners.remove(teamId)?.remove()
            }
        }
        changed()
    }

    /** O Firestore encerra a escuta que falha: para as demais e avisa a tela. */
    private fun failed(error: Exception) {
        Log.w(TAG, "Escuta em tempo real interrompida.", error)
        stop()
        onError?.invoke(error)
    }
}
