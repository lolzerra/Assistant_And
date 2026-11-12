package fr.maison.assistant

import android.Manifest
import android.content.ActivityNotFoundException
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
    val needsConfirm get() = name == "telephone_appeler" || name == "telephone_sms"
    fun arg(key: String) = args.optString(key).trim()
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
