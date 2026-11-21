package fr.maison.assistant

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Alertes du site (veille d'actualités…) relayées par l'appli Home Assistant : le site lui envoie une commande
 * « command_broadcast_intent » vers ce récepteur, ce qui marche même hors de la maison.
 * L'alerte est signée avec la clé du compte (reçue du site) : une autre appli du téléphone ne peut pas en inventer.
 * Toucher la notification ouvre la conversation concernée.
 */
class AlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val prefs = Prefs(context)
        val cid = intent.getIntExtra("conversation", 0)
        val title = intent.getStringExtra("titre").orEmpty()
        val text = intent.getStringExtra("texte").orEmpty()
        val signature = intent.getStringExtra("signature").orEmpty()
        if (prefs.alertKey.isEmpty() || cid == 0 || !valid(prefs.alertKey, cid, title, text, signature)) {
            Journal.log("alerte refusée (signature absente ou fausse)")
            return
        }
        show(context, cid, title, text)
        Journal.log("alerte reçue pour la conversation $cid")
        // Accusé de réception : le site arrête d'envoyer aussi la notification Home Assistant de secours.
        if (!prefs.alertRelayConfirmed) {
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                val ok = withTimeoutOrNull(8_000) { runCatching { Api(prefs).alertAck() }.isSuccess } == true
                if (ok) prefs.alertRelayConfirmed = true
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "fr.maison.assistant.action.ALERTE"
        const val CHANNEL = "veille"

        private fun valid(key: String, cid: Int, title: String, text: String, signature: String): Boolean {
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key.toByteArray(), "HmacSHA256")) }
            val expected = mac.doFinal("$cid|$title|$text".toByteArray()).joinToString("") { "%02x".format(it) }
            return MessageDigest.isEqual(expected.toByteArray(), signature.lowercase().toByteArray())
        }

        fun channel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Veille et alertes", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Infos importantes trouvées par la veille d'actualités du site"
                })
            }
        }

        fun show(context: Context, cid: Int, title: String, text: String) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                android.os.Build.VERSION.SDK_INT >= 33
            ) {
                Journal.log("alerte non affichée : notifications refusées pour l'appli")
                return
            }
            channel(context)
            val open = Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_CONVERSATION, cid)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val tap = PendingIntent.getActivity(context, cid, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setColor(0xFF7C6CFF.toInt())
                .setContentTitle(title.ifBlank { "Assistant" })
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            try {
                NotificationManagerCompat.from(context).notify("alerte", cid, n)  // une notification par conversation
            } catch (e: SecurityException) {
                Journal.log("alerte non affichée : ${e.message}")
            }
        }
    }
}
