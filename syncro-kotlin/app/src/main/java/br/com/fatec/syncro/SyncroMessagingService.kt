package br.com.fatec.syncro

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/** Recebe as mensagens do FCM, inclusive com o app fechado (veja [PushNotifications]). */
class SyncroMessagingService : FirebaseMessagingService() {
    /** O FCM trocou o token deste aparelho (reinstalação, dados apagados etc.). */
    override fun onNewToken(token: String) {
        PushNotifications.saveToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        PushNotifications.show(this, message.data)
    }
}
