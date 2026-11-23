package fr.maison.assistant

import android.Manifest
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

/**
 * Souvenir proposé par la relecture mémoire du site : notification avec Valider / Refuser.
 * Les boutons arrivent ici (récepteur interne, non exporté) et envoient la décision au site avec le jeton de l'appli.
 */
class MemoryActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_ID, 0)
        if (id == 0) return
        val accept = intent.action == ACTION_ACCEPT
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val state = withTimeoutOrNull(10_000) { runCatching { Api(Prefs(context)).memoryDecide(id, accept) }.getOrNull() }
            val done = when (state) {
                "accepted" -> if (accept) "✅ Souvenir enregistré" else "Déjà validé sur le site"
                "refused" -> if (accept) "Déjà refusé sur le site" else "✖ Refusé"
                else -> null
            }
            Journal.log("souvenir $id : ${if (accept) "validé" else "refusé"} → ${state ?: "échec"}")
            if (done != null) {
                update(context, id, done, text)
                kotlinx.coroutines.delay(2500)
                NotificationManagerCompat.from(context).cancel(TAG, id)
            } else {
                show(context, id, 0, title, text, "⚠ Le site n'a pas répondu, réessaie")
            }
            pending.finish()
        }
    }

    companion object {
        const val ACTION_ACCEPT = "fr.maison.assistant.action.SOUVENIR_OUI"
        const val ACTION_REFUSE = "fr.maison.assistant.action.SOUVENIR_NON"
        private const val EXTRA_ID = "souvenir"
        private const val EXTRA_TITLE = "titre"
        private const val EXTRA_TEXT = "texte"
        private const val TAG = "souvenir"

        private fun allowed(context: Context) = android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        private fun button(context: Context, action: String, id: Int, title: String, text: String): PendingIntent {
            val i = Intent(context, MemoryActionReceiver::class.java).setAction(action)
                .putExtra(EXTRA_ID, id).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_TEXT, text)
            val code = id * 2 + if (action == ACTION_ACCEPT) 1 else 0
            return PendingIntent.getBroadcast(context, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        fun show(context: Context, id: Int, cid: Int, title: String, text: String, note: String? = null) {
            if (!allowed(context)) {
                Journal.log("souvenir non affiché : notifications refusées pour l'appli")
                return
            }
            AlertReceiver.channel(context)
            val open = Intent(context, MainActivity::class.java)
                .apply { if (cid > 0) putExtra(MainActivity.EXTRA_CONVERSATION, cid) }
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val tap = PendingIntent.getActivity(context, 100_000 + id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val body = if (note != null) "$text\n$note" else text
            val n = NotificationCompat.Builder(context, AlertReceiver.CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setColor(0xFF7C6CFF.toInt())
                .setContentTitle(title.ifBlank { "🧠 Mémoire" })
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(tap)
                .setAutoCancel(false)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .addAction(0, "Refuser", button(context, ACTION_REFUSE, id, title, text))
                .addAction(0, "Valider", button(context, ACTION_ACCEPT, id, title, text))
                .build()
            try {
                NotificationManagerCompat.from(context).notify(TAG, id, n)
            } catch (e: SecurityException) {
                Journal.log("souvenir non affiché : ${e.message}")
            }
        }

        private fun update(context: Context, id: Int, title: String, text: String) {
            if (!allowed(context)) return
            val n = NotificationCompat.Builder(context, AlertReceiver.CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setColor(0xFF7C6CFF.toInt())
                .setContentTitle(title)
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .build()
            try { NotificationManagerCompat.from(context).notify(TAG, id, n) } catch (_: SecurityException) {}
        }
    }
}
