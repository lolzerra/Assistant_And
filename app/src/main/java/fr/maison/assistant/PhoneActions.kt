package fr.maison.assistant

import android.Manifest
import android.app.NotificationManager
import android.app.RemoteInput
import android.content.ActivityNotFoundException
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.telephony.SmsManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.text.Normalizer
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Calendar

/** Une action demandée par l'assistant sur le téléphone (réveil, appel, SMS…). */
class PhoneAction(val name: String, val args: JSONObject) {
    /** Résultat affiché sous la réponse : « ✓ Réveil mis à 06:30 », « ⚠ … ». */
    var status by mutableStateOf<String?>(null)
    val needsConfirm get() = name == "telephone_appeler" || name == "telephone_sms" || name == "telephone_repondre"
    fun arg(key: String) = args.optString(key).trim()
    /** Musique demandée pendant une réponse lue à voix haute : lancée après la voix, pour ne pas la couper. */
    var later = false
    /** Aléatoire demandé avec la musique : true, false, ou null si l'utilisateur n'a rien précisé. */
    val shuffle get() = args.takeIf { it.has("aleatoire") }?.optBoolean("aleatoire")
    /** Itinéraire ou appli de navigation : la localisation doit être active. */
    val needsLocation get() = name == "telephone_itineraire" ||
        (name == "telephone_ouvrir_appli" && Location.isNavigationApp(arg("nom")))
}

data class Contact(val name: String, val number: String, val kind: String)

object PhoneActions {
    private val DAYS = mapOf(
        "lun" to Calendar.MONDAY, "mar" to Calendar.TUESDAY, "mer" to Calendar.WEDNESDAY, "jeu" to Calendar.THURSDAY,
        "ven" to Calendar.FRIDAY, "sam" to Calendar.SATURDAY, "dim" to Calendar.SUNDAY,
    )

    /** Actions sans confirmation : exécutées dès la fin de la réponse. */
    fun run(context: Context, a: PhoneAction) {
        a.status = try {
            when (a.name) {
                "telephone_reveil" -> alarm(context, a)
                "telephone_minuteur" -> timer(context, a)
                "telephone_ouvrir_appli" -> openApp(context, a.arg("nom"))
                "telephone_itineraire" -> navigate(context, a.arg("destination"), a.arg("appli"))
                "telephone_agenda" -> calendar(context, a)
                "telephone_partager" -> share(context, a.arg("texte"), a.arg("appli"))
                else -> "⚠ Action inconnue : ${a.name}"
            }
        } catch (e: ActivityNotFoundException) {
            "⚠ Aucune appli du téléphone ne sait faire ça"
        } catch (e: Exception) {
            "⚠ ${e.message ?: e.javaClass.simpleName}"
        }
        Journal.log("action ${a.name} : ${a.status}")
    }

    private fun start(context: Context, intent: Intent) {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun alarm(context: Context, a: PhoneAction): String {
        val (h, m) = a.arg("heure").split(":").map { it.toInt() }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h)
            .putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        a.arg("libelle").takeIf { it.isNotEmpty() }?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        val days = a.args.optJSONArray("jours")?.let { arr -> (0 until arr.length()).mapNotNull { DAYS[arr.optString(it)] } }.orEmpty()
        if (days.isNotEmpty()) intent.putExtra(AlarmClock.EXTRA_DAYS, ArrayList(days))
        start(context, intent)
        return "⏰ Réveil mis à %02d:%02d".format(h, m) + (if (days.isNotEmpty()) " (répété)" else "") + " ✓"
    }

    private fun timer(context: Context, a: PhoneAction): String {
        val secs = a.args.optInt("secondes")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, secs)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        a.arg("libelle").takeIf { it.isNotEmpty() }?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        start(context, intent)
        val label = if (secs >= 60) "${secs / 60} min" + (if (secs % 60 > 0) " ${secs % 60} s" else "") else "$secs s"
        return "⏱ Minuteur de $label lancé ✓"
    }

    private fun openApp(context: Context, name: String): String {
        val wanted = plain(name)
        if (listOf("photo", "camera", "appareil").any { it in wanted }) {
            start(context, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
            return "📷 Appareil photo ouvert ✓"
        }
        val pm = context.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        val best = apps.firstOrNull { plain(it.first) == wanted }
            ?: apps.firstOrNull { plain(it.first).startsWith(wanted) }
            ?: apps.firstOrNull { wanted in plain(it.first) }
            ?: apps.firstOrNull { plain(it.first) in wanted && plain(it.first).length >= 3 }
            ?: return "⚠ Pas d'appli « $name » sur le téléphone"
        val intent = pm.getLaunchIntentForPackage(best.second) ?: return "⚠ « ${best.first} » ne peut pas s'ouvrir"
        start(context, intent)
        return "📱 ${best.first} ouvert ✓"
    }

    private fun navigate(context: Context, destination: String, app: String): String {
        val q = Uri.encode(destination)
        if (app == "waze") {
            start(context, Intent(Intent.ACTION_VIEW, Uri.parse("https://waze.com/ul?q=$q&navigate=yes")))
            return "🧭 Waze vers « $destination » ✓"
        }
        try {
            start(context, Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$q")).setPackage("com.google.android.apps.maps"))
        } catch (e: ActivityNotFoundException) {
            start(context, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$q")))
        }
        return "🧭 Itinéraire vers « $destination » ✓"
    }

    private fun calendar(context: Context, a: PhoneAction): String {
        val zone = ZoneId.systemDefault()
        val begin = a.arg("debut")
        val allDay = !begin.contains("T")
        val start = if (allDay) LocalDate.parse(begin).atStartOfDay(zone) else LocalDateTime.parse(begin).atZone(zone)
        val end = a.arg("fin").takeIf { it.isNotEmpty() }?.let {
            if (it.contains("T")) LocalDateTime.parse(it).atZone(zone) else LocalDate.parse(it).atStartOfDay(zone)
        } ?: if (allDay) start.plusDays(1) else start.plusHours(1)
        val intent = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, a.arg("titre"))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start.toInstant().toEpochMilli())
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end.toInstant().toEpochMilli())
            .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, allDay)
        a.arg("lieu").takeIf { it.isNotEmpty() }?.let { intent.putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
        start(context, intent)
        return "📅 Agenda ouvert avec « ${a.arg("titre")} » : enregistre-le"
    }

    private fun share(context: Context, text: String, app: String): String {
        if (app == "mail") {
            start(context, Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).putExtra(Intent.EXTRA_TEXT, text))
            return "✉ Mail préparé ✓"
        }
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        if (app == "whatsapp") {
            try {
                start(context, Intent(send).setPackage("com.whatsapp"))
                return "💬 WhatsApp ouvert ✓"
            } catch (_: ActivityNotFoundException) { /* pas de WhatsApp : choix de l'appli */ }
        }
        start(context, Intent.createChooser(send, "Partager"))
        return "↗ Partage ouvert ✓"
    }

    // ---------- Actions dont l'assistant attend le résultat ----------

    /** Exécute l'action et renvoie (texte pour le modèle, statut affiché). */
    fun query(context: Context, a: PhoneAction): Pair<String, String> = try {
        when (a.name) {
            "telephone_notifications" -> {
                val text = NotifService.list(context, a.arg("appli"), a.args.optInt("nombre", 10).takeIf { it > 0 } ?: 10)
                text to if (text.startsWith("Erreur")) "⚠ Autorise l'accès aux notifications : ⚙ Réglages → Autorisations"
                else "🔔 Notifications lues"
            }
            "telephone_media" -> media(context, a.arg("action"), a.args.optInt("volume", -1)).let { it to "🎵 $it" }
            "telephone_ne_pas_deranger" -> dnd(context, a.args.optBoolean("actif")).let { it to it }
            "telephone_reglages" -> settings(context, a).let { it to it }
            "telephone_musique" -> Music.play(context, a.arg("recherche"), a.arg("type"), a.shuffle)
            "telephone_playlists" -> Music.playlists(context)
            "telephone_contact" -> contactCard(context, a.arg("nom"))
            "telephone_agenda_lire" -> agendaSearch(context, a.arg("date"), a.arg("fin"), a.arg("recherche"))
            "telephone_aleatoire" -> Music.shuffle(context, a.args.takeIf { it.has("actif") }?.optBoolean("actif"))
            else -> "Erreur : action inconnue" to "⚠ Action inconnue"
        }
    } catch (e: Exception) {
        "Erreur : ${e.message}" to "⚠ ${e.message}"
    }.also { Journal.log("action ${a.name} : ${it.second}") }

    private fun media(context: Context, action: String, volume: Int): String {
        val am = context.getSystemService(AudioManager::class.java)
        val controller = if (NotifService.enabled(context)) runCatching {
            context.getSystemService(MediaSessionManager::class.java).getActiveSessions(NotifService.component(context)).firstOrNull()
        }.getOrNull() else null
        fun key(code: Int) {
            val t = SystemClock.uptimeMillis()
            am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
            am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
        }
        val tc = controller?.transportControls
        when (action) {
            "lecture" -> tc?.play() ?: key(KeyEvent.KEYCODE_MEDIA_PLAY)
            "pause" -> tc?.pause() ?: key(KeyEvent.KEYCODE_MEDIA_PAUSE)
            "suivant" -> tc?.skipToNext() ?: key(KeyEvent.KEYCODE_MEDIA_NEXT)
            "precedent" -> tc?.skipToPrevious() ?: key(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            "stop" -> tc?.stop() ?: key(KeyEvent.KEYCODE_MEDIA_STOP)
            "volume" -> {
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, (volume.coerceIn(0, 100) * max + 50) / 100, AudioManager.FLAG_SHOW_UI)
            }
        }
        if (action in setOf("lecture", "suivant", "precedent")) Thread.sleep(800)  // le temps que le lecteur change de morceau
        val pct = am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val meta = controller?.metadata
        val what = meta?.let {
            listOfNotNull(it.getString(android.media.MediaMetadata.METADATA_KEY_TITLE), it.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST))
                .joinToString(" — ")
        }.orEmpty()
        val playing = when (controller?.playbackState?.state) {
            PlaybackState.STATE_PLAYING -> "en lecture"
            PlaybackState.STATE_PAUSED -> "en pause"
            null -> if (am.isMusicActive) "en lecture" else "rien ne joue"
            else -> "arrêté"
        }
        val app = controller?.packageName?.let { pkg ->
            runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        }
        val done = if (action == "etat") "" else "Fait (${action}). "
        return done + listOfNotNull(app, what.ifEmpty { null }, playing).joinToString(" · ") + " · volume $pct %"
    }

    private fun dnd(context: Context, on: Boolean): String {
        val filter = if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL
        val nm = context.getSystemService(NotificationManager::class.java)
        when {
            nm.isNotificationPolicyAccessGranted -> nm.setInterruptionFilter(filter)
            NotifService.instance != null -> NotifService.instance!!.requestInterruptionFilter(filter)
            else -> return "⚠ Autorise l'accès aux notifications : ⚙ Réglages → Autorisations"
        }
        return if (on) "🔕 Ne pas déranger activé ✓" else "🔔 Ne pas déranger coupé ✓"
    }

    /**
     * Position du curseur de luminosité d'Android (0–100 %) → valeur réelle (1–255). Le curseur suit une courbe
     * « gamma » (BrightnessUtils.convertGammaToLinear d'Android) : 50 % du curseur ≈ 8 % de la valeur brute.
     */
    private fun sliderToBrightness(pct: Int): Int {
        val s = pct.coerceIn(0, 100) / 100.0
        val ratio = if (s <= 0.5) (s / 0.5) * (s / 0.5) else Math.exp((s - 0.55991073) / 0.17883277) + 0.28466892
        return Math.round(1 + (ratio / 12.0).coerceIn(0.0, 1.0) * 254).toInt().coerceIn(1, 255)
    }

    private fun settings(context: Context, a: PhoneAction): String {
        val done = mutableListOf<String>()
        if (a.args.has("luminosite")) {
            val pct = a.args.optInt("luminosite").coerceIn(0, 100)
            if (!Settings.System.canWrite(context)) {
                done += "⚠ Luminosité : autorise « Modifier les paramètres système » (⚙ Réglages → Autorisations)"
            } else {
                val cr = context.contentResolver
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, sliderToBrightness(pct))
                done += "☀ Luminosité à $pct % ✓"
            }
        }
        if (a.args.optBoolean("luminosite_auto") && !a.args.has("luminosite")) {
            done += if (!Settings.System.canWrite(context)) {
                "⚠ Luminosité : autorise « Modifier les paramètres système » (⚙ Réglages → Autorisations)"
            } else {
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
                "☀ Luminosité automatique ✓"
            }
        }
        if (a.args.has("sonnerie")) {
            val pct = a.args.optInt("sonnerie").coerceIn(0, 100)
            val am = context.getSystemService(AudioManager::class.java)
            done += try {
                val max = am.getStreamMaxVolume(AudioManager.STREAM_RING)
                am.setStreamVolume(AudioManager.STREAM_RING, (pct * max + 50) / 100, AudioManager.FLAG_SHOW_UI)
                "🔔 Sonnerie à $pct % ✓"
            } catch (e: SecurityException) {
                "⚠ Sonnerie : refusé par Android (mode Ne pas déranger actif ?)"
            }
        }
        return done.joinToString("\n")
    }

    // ---------- Répondre à une notification ----------

    fun reply(context: Context, a: PhoneAction): String {
        val sbn = NotifService.findReplyable(context, a.arg("notification"))
            ?: return if (NotifService.instance == null) "⚠ Autorise l'accès aux notifications : ⚙ Réglages → Autorisations"
            else "⚠ La notification n'est plus là : réponds depuis l'appli"
        val action = NotifService.replyAction(sbn.notification) ?: return "⚠ Pas de bouton Répondre"
        val intent = Intent()
        val results = Bundle()
        action.remoteInputs.filter { it.allowFreeFormInput }.forEach { results.putCharSequence(it.resultKey, a.arg("message")) }
        RemoteInput.addResultsToIntent(action.remoteInputs, intent, results)
        action.actionIntent.send(context, 0, intent)
        return "💬 Réponse envoyée à ${NotifService.describe(context, sbn)} ✓"
    }

    fun replyTarget(context: Context, a: PhoneAction): String? =
        NotifService.findReplyable(context, a.arg("notification"))?.let { NotifService.describe(context, it) }

    // ---------- Contacts, appels, SMS ----------

    fun has(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun plain(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").trim()

    private fun digits(s: String) = s.filter { it.isDigit() || it == '+' }

    /** Un numéro dicté (« 06 12 34 56 78 ») plutôt qu'un nom. */
    fun isNumber(s: String) = digits(s).count { it.isDigit() } >= 6 && s.none { it.isLetter() }

    /** Contacts dont le nom contient tous les mots demandés ; les noms exacts d'abord. Permission vérifiée par l'appelant. */
    fun findContacts(context: Context, query: String): List<Contact> {
        if (isNumber(query)) return listOf(Contact(query, digits(query), ""))
        val words = plain(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
        val found = mutableListOf<Contact>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE,
                ContactsContract.CommonDataKinds.Phone.LABEL,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val number = c.getString(1) ?: continue
                if (words.all { it in plain(name) }) {
                    val kind = ContactsContract.CommonDataKinds.Phone.getTypeLabel(context.resources, c.getInt(2), c.getString(3)).toString()
                    found += Contact(name, number, kind)
                }
            }
        }
        return found.distinctBy { digits(it.number).takeLast(9) }
            .sortedWith(compareBy({ plain(it.name) != plain(query) }, { it.name.length }))
            .take(5)
    }

    /**
     * Événements de l'agenda : d'un jour ou d'une période, et/ou dont le titre, le lieu ou la description contient les mots
     * cherchés. Sans date : aujourd'hui ; avec une recherche sans date : de -1 an à +1 an. 40 événements au plus.
     */
    private fun agendaSearch(context: Context, date: String, end: String, query: String): Pair<String, String> {
        if (!has(context, Manifest.permission.READ_CALENDAR))
            return "Erreur : autorisation Agenda manquante (⚙ Réglages → Autorisations)." to "⚠ Autorise l'agenda : ⚙ Réglages → Autorisations"
        val today = LocalDate.now()
        val words = plain(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
        val from = date.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it.take(10)) }
            ?: if (words.isEmpty()) today else today.minusYears(1)
        var to = end.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it.take(10)) }
            ?: if (date.isNotBlank() || words.isEmpty()) from else today.plusYears(1)
        if (to.isBefore(from)) to = from
        if (to.isAfter(from.plusDays(800))) to = from.plusDays(800)
        val zone = ZoneId.systemDefault()
        val begin = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val finish = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().let {
            android.content.ContentUris.appendId(it, begin); android.content.ContentUris.appendId(it, finish); it.build()
        }
        data class Ev(val title: String, val start: Long, val stop: Long, val allDay: Boolean, val place: String, val note: String, val cal: String)
        val events = mutableListOf<Ev>()
        context.contentResolver.query(uri, arrayOf(
            CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
        ), null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) {
                val e = Ev(c.getString(0).orEmpty(), c.getLong(1), c.getLong(2), c.getInt(3) == 1, c.getString(4).orEmpty(),
                    c.getString(5).orEmpty(), c.getString(6).orEmpty())
                if (words.isEmpty() || words.all { it in plain("${e.title} ${e.place} ${e.note}") }) events += e
            }
        }
        val days = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM yyyy", java.util.Locale.FRANCE)
        val hours = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
        fun line(e: Ev): String {
            // Les événements « toute la journée » sont en UTC dans l'agenda d'Android.
            val z = if (e.allDay) java.time.ZoneOffset.UTC else zone
            val s0 = java.time.Instant.ofEpochMilli(e.start).atZone(z)
            val s1 = java.time.Instant.ofEpochMilli(e.stop).atZone(z)
            val whenText = if (e.allDay) {
                val last = s1.minusDays(1).toLocalDate()
                days.format(s0) + (if (last.isAfter(s0.toLocalDate())) " → ${days.format(last)}" else "") + ", toute la journée"
            } else if (s1.toLocalDate() == s0.toLocalDate()) "${days.format(s0)}, ${hours.format(s0)}–${hours.format(s1)}"
            else "${days.format(s0)} ${hours.format(s0)} → ${days.format(s1)} ${hours.format(s1)}"
            return "- $whenText : ${e.title.ifBlank { "(sans titre)" }}" +
                (if (e.place.isNotBlank()) " · lieu : ${e.place}" else "") +
                (if (e.note.isNotBlank()) " · note : ${e.note.replace(Regex("\\s+"), " ").take(200)}" else "") +
                (if (e.cal.isNotBlank()) " · agenda : ${e.cal}" else "")
        }
        val period = if (from == to) days.format(from) else "du ${days.format(from)} au ${days.format(to)}"
        val what = if (words.isEmpty()) "" else " contenant « $query »"
        if (events.isEmpty()) return "Aucun événement $period$what." to "📅 Aucun événement"
        val shown = events.take(40)
        val more = if (events.size > shown.size) "\n(${events.size - shown.size} autres événements : précise la période.)" else ""
        return "Événements $period$what :\n" + shown.joinToString("\n") { line(it) } + more to
            "📅 ${events.size} événement${if (events.size > 1) "s" else ""}"
    }

    /** Fiche des contacts dont le nom contient tous les mots demandés (5 au plus) : numéros, e-mails, adresses… */
    private fun contactCard(context: Context, query: String): Pair<String, String> {
        if (!has(context, Manifest.permission.READ_CONTACTS))
            return "Erreur : autorisation Contacts manquante." to "⚠ Autorise l'accès aux contacts : ⚙ Réglages → Autorisations"
        val words = plain(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return "Erreur : nom vide." to "⚠ Nom vide"
        val found = mutableListOf<Pair<Long, String>>()
        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY), null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                if (words.all { it in plain(name) }) found += c.getLong(0) to name
            }
        }
        if (found.isEmpty()) return "Aucun contact ne correspond à « $query »." to "👤 Aucun contact « $query »"
        val picked = found.sortedWith(compareBy({ plain(it.second) != plain(query) }, { it.second.length })).take(5)
        val res = context.resources
        val cards = picked.map { (id, name) ->
            val lines = linkedSetOf<String>()
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1, ContactsContract.Data.DATA2,
                    ContactsContract.Data.DATA3, ContactsContract.Data.DATA4),
                "${ContactsContract.Data.CONTACT_ID} = ?", arrayOf(id.toString()), null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val value = c.getString(1)?.replace(Regex("\\s*\\n\\s*"), ", ")?.trim().orEmpty()
                    if (value.isEmpty()) continue
                    val type = c.getInt(2)
                    val label = c.getString(3)
                    when (c.getString(0)) {
                        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE ->
                            lines += "Téléphone (${ContactsContract.CommonDataKinds.Phone.getTypeLabel(res, type, label)}) : $value"
                        ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE ->
                            lines += "E-mail (${ContactsContract.CommonDataKinds.Email.getTypeLabel(res, type, label)}) : $value"
                        ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE ->
                            lines += "Adresse (${ContactsContract.CommonDataKinds.StructuredPostal.getTypeLabel(res, type, label)}) : $value"
                        ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE ->
                            lines += "${if (type == ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY) "Anniversaire" else
                                ContactsContract.CommonDataKinds.Event.getTypeResource(type).let { r -> runCatching { res.getString(r) }.getOrDefault("Date") }} : $value"
                        ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE ->
                            lines += "Société : $value" + (c.getString(4)?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
                        ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE -> lines += "Surnom : $value"
                    }
                }
            }
            "— $name\n" + (if (lines.isEmpty()) "  (fiche vide)" else lines.joinToString("\n") { "  $it" })
        }
        val more = if (found.size > picked.size) "\n(${found.size - picked.size} autre(s) contact(s) correspondent : précise le nom si besoin.)" else ""
        return "Contacts trouvés :\n" + cards.joinToString("\n") + more to
            (if (picked.size == 1) "👤 Fiche de ${picked[0].second}" else "👤 ${picked.size} contacts trouvés")
    }

    /** Appel direct si autorisé, sinon le numéro est composé et l'utilisateur appuie sur Appeler. */
    fun call(context: Context, c: Contact): String = try {
        if (has(context, Manifest.permission.CALL_PHONE)) {
            start(context, Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(digits(c.number)))))
            "📞 Appel vers ${c.name} lancé ✓"
        } else {
            start(context, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(digits(c.number)))))
            "📞 Numéro de ${c.name} composé : appuie sur Appeler"
        }
    } catch (e: Exception) {
        "⚠ Appel impossible : ${e.message}"
    }

    fun sendSms(context: Context, c: Contact, text: String): String = try {
        val sms = context.getSystemService(SmsManager::class.java)
        sms.sendMultipartTextMessage(digits(c.number), null, sms.divideMessage(text), null, null)
        "💬 SMS envoyé à ${c.name} ✓"
    } catch (e: Exception) {
        "⚠ SMS non envoyé : ${e.message}"
    }

    /** Ouvre l'appli Messages préremplie (pour modifier, ou sans la permission d'envoi). */
    fun editSms(context: Context, c: Contact, text: String): String = try {
        start(context, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(digits(c.number)))).putExtra("sms_body", text))
        "💬 SMS pour ${c.name} ouvert dans Messages"
    } catch (e: Exception) {
        "⚠ Messages introuvable : ${e.message}"
    }
}
