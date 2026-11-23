package br.com.fatec.syncro

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Notificações push pelo Firebase Cloud Messaging (FCM).
 *
 * Cada aparelho conectado guarda o próprio token em
 * `users/{uid}/fcmTokens/{token}`. Quando uma notificação é registrada em
 * `users/{uid}/notifications`, o servidor de push (pasta `syncro-push`, na
 * raiz do repositório) envia
 * uma mensagem a esses tokens, que [SyncroMessagingService] recebe e
 * [show] exibe na barra do sistema, mesmo com o app fechado.
 */
object PushNotifications {
    /** Extra de [MainActivity] para abrir a tela de notificações. */
    const val EXTRA_OPEN_NOTIFICATIONS = "open_notifications"

    private const val CHANNEL_ID = "syncro_notifications"
    private const val TOKENS_COLLECTION = "fcmTokens"
    private const val USERS_COLLECTION = "users"
    private const val UNREGISTER_TIMEOUT_MS = 3_000L
    private const val TAG = "SyncroPush"

    private val auth: FirebaseAuth
        get() = FirebaseAuth.getInstance()

    private val mainThread = Handler(Looper.getMainLooper())

    private fun tokens(uid: String): CollectionReference =
        FirebaseFirestore.getInstance()
            .collection(USERS_COLLECTION).document(uid).collection(TOKENS_COLLECTION)

    /** Vincula este aparelho à conta conectada (ao entrar e ao restaurar a sessão). */
    fun register() {
        if (auth.currentUser == null) return
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener(::saveToken)
            .addOnFailureListener { Log.w(TAG, "Não foi possível obter o token do FCM.", it) }
    }

    /** Grava o token na conta conectada; sem sessão, fica para o próximo login. */
    internal fun saveToken(token: String) {
        val uid = auth.currentUser?.uid ?: return
        tokens(uid).document(token)
            .set(mapOf("updatedAt" to FieldValue.serverTimestamp()))
            .addOnFailureListener { Log.w(TAG, "Não foi possível registrar o token do FCM.", it) }
    }

    /**
     * Desvincula o aparelho antes do logout: apaga o token da conta (o que
     * exige a sessão ainda ativa) e depois o invalida no FCM, para que a
     * próxima conta conectada aqui receba um token novo. Sem rede, segue com
     * o logout após [UNREGISTER_TIMEOUT_MS]; o token invalidado é descartado
     * pelo servidor de push no próximo envio.
     */
    fun unregister(onComplete: () -> Unit) {
        val uid = auth.currentUser?.uid
        val messaging = FirebaseMessaging.getInstance()
        val finished = AtomicBoolean(false)
        val finish = Runnable {
            if (finished.compareAndSet(false, true)) {
                messaging.deleteToken()
                    .addOnFailureListener { Log.w(TAG, "Não foi possível invalidar o token do FCM.", it) }
                onComplete()
            }
        }
        mainThread.postDelayed(finish, UNREGISTER_TIMEOUT_MS)
        messaging.token.continueWithTask { token ->
            if (uid == null || !token.isSuccessful) Tasks.forResult(null)
            else tokens(uid).document(token.result).delete()
        }.addOnCompleteListener { task: Task<Void?> ->
            if (!task.isSuccessful) Log.w(TAG, "Não foi possível remover o token do FCM.", task.exception)
            mainThread.removeCallbacks(finish)
            finish.run()
        }
    }

    /** Android 13 ou mais recente pede autorização para exibir notificações. */
    fun needsPermission(context: Context) =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED

    /**
     * Exibe a notificação recebida pelo FCM. Os dados vêm do servidor de push:
     * `uid` (destinatário), `notificationId`, `title` e `body`.
     */
    internal fun show(context: Context, data: Map<String, String>) {
        // O aparelho pode ter trocado de conta depois que o token foi registrado.
        val uid = auth.currentUser?.uid ?: return
        if (data["uid"] != uid) return
        val id = data["notificationId"]?.takeIf { it.isNotEmpty() } ?: return
        // Com o app aberto, a tela já é atualizada pelo Firestore (LiveUpdates).
        if (needsPermission(context)) return
        createChannel(context)
        val body = data["body"].orEmpty()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_syncro)
            .setColor(ContextCompat.getColor(context, R.color.blue))
            .setContentTitle(data["title"]?.takeIf { it.isNotBlank() } ?: context.getString(R.string.app_name))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setAutoCancel(true)
            .setContentIntent(openNotifications(context, id))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, 0, notification)
        } catch (error: SecurityException) {
            // A autorização foi revogada entre a verificação e o envio.
            Log.w(TAG, "Sem autorização para exibir notificações.", error)
        }
    }

    /** Abre o app pela tela inicial, que restaura a sessão e segue para as notificações. */
    private fun openNotifications(context: Context, id: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_OPEN_NOTIFICATIONS, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return PendingIntent.getActivity(
            context,
            id.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.push_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply { description = context.getString(R.string.push_channel_description) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
