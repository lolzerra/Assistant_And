package fr.maison.assistant

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Accès aux notifications (autorisé par l'utilisateur dans les réglages Android) : l'assistant peut les lire,
 * répondre via le bouton « Répondre », piloter la musique et le mode Ne pas déranger.
 */
class NotifService : NotificationListenerService() {
    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    companion object {
        @Volatile var instance: NotifService? = null
            private set

        /** Identifiants courts (n1, n2…) donnés au modèle → clé Android de la notification. */
        private val ids = mutableMapOf<String, String>()

        fun enabled(context: Context) = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

        fun component(context: Context) = ComponentName(context, NotifService::class.java)

        private fun plain(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

        private fun usable(context: Context, sbn: StatusBarNotification): Boolean {
            val n = sbn.notification
            return sbn.packageName != context.packageName &&
                n.flags and Notification.FLAG_ONGOING_EVENT == 0 &&
                n.category != Notification.CATEGORY_TRANSPORT &&
                (n.flags and Notification.FLAG_GROUP_SUMMARY == 0 ||
                    NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) != null)
        }

        private fun appName(context: Context, pkg: String) = runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)

        private fun title(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()

        private fun body(n: Notification): String {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)?.let { style ->
                val lines = style.messages.takeLast(5).map { m -> "${m.person?.name ?: "moi"} : ${m.text}" }
                if (lines.isNotEmpty()) return lines.joinToString("\n")
            }
            val e = n.extras
            return (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        }

        fun replyAction(n: Notification): Notification.Action? =
            n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }

        /** Texte donné au modèle : une notification par bloc, les plus récentes d'abord. */
        fun list(context: Context, filter: String, max: Int): String {
            val service = instance ?: return "Erreur : accès aux notifications non autorisé (⚙ Réglages → Autorisations)."
            val wanted = plain(filter.trim())
            val time = SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE)
            val found = service.activeNotifications.orEmpty()
                .filter { usable(context, it) }
                .sortedByDescending { it.postTime }
                .filter { sbn ->
                    wanted.isEmpty() || wanted in plain("${appName(context, sbn.packageName)} ${title(sbn.notification)} ${body(sbn.notification)}")
                }
                .take(max.coerceIn(1, 30))
            synchronized(ids) { ids.clear() }
            if (found.isEmpty()) return if (wanted.isEmpty()) "Aucune notification." else "Aucune notification pour « $filter »."
            return found.mapIndexed { i, sbn ->
                val id = "n${i + 1}"
                synchronized(ids) { ids[id] = sbn.key }
                val n = sbn.notification
                val reply = if (replyAction(n) != null) " [réponse possible]" else ""
                "$id · ${appName(context, sbn.packageName)} · ${time.format(Date(sbn.postTime))}$reply\n${title(n)}\n${body(n).take(600)}"
            }.joinToString("\n\n")
        }

        /** Notification visée par un identifiant (n2) ou un nom d'expéditeur, parmi celles qui ont un bouton Répondre. */
        fun findReplyable(context: Context, target: String): StatusBarNotification? {
            val service = instance ?: return null
            val active = service.activeNotifications.orEmpty().filter { replyAction(it.notification) != null }
            synchronized(ids) { ids[target.trim()] }?.let { key -> active.firstOrNull { it.key == key }?.let { return it } }
            val wanted = plain(target.trim())
            return active.sortedByDescending { it.postTime }.firstOrNull { wanted in plain(title(it.notification)) }
        }

        fun describe(context: Context, sbn: StatusBarNotification) = "${title(sbn.notification)} (${appName(context, sbn.packageName)})"
    }
}
