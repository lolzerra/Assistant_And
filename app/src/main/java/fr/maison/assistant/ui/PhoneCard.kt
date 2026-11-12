package fr.maison.assistant.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.maison.assistant.Contact
import fr.maison.assistant.PhoneAction
import fr.maison.assistant.PhoneActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Sous une réponse : résultat d'une action sur le téléphone, ou confirmation d'un appel / d'un SMS. */
@Composable
fun PhoneCard(a: PhoneAction) {
    val colors = MaterialTheme.colorScheme
    Surface(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        shape = RoundedCornerShape(12.dp),
        color = colors.surfaceVariant,
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val status = a.status
            when {
                status != null -> Text(status, fontSize = 14.sp, color = if (status.startsWith("⚠")) colors.error else colors.onSurface)
                a.needsConfirm -> ConfirmContact(a)
                else -> Text("…", color = colors.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConfirmContact(a: PhoneAction) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val sms = a.name == "telephone_sms"
    val query = a.arg("contact")
    var canRead by remember { mutableStateOf(PhoneActions.isNumber(query) || PhoneActions.has(context, Manifest.permission.READ_CONTACTS)) }
    var contacts by remember { mutableStateOf<List<Contact>?>(null) }
    var chosen by remember { mutableStateOf<Contact?>(null) }  // en attente de la permission d'appel / d'envoi

    val askContacts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { canRead = it }
    val askAct = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val c = chosen ?: return@rememberLauncherForActivityResult
        a.status = when {
            !sms -> PhoneActions.call(context, c)  // sans permission : numéro composé, l'utilisateur appuie sur Appeler
            granted -> PhoneActions.sendSms(context, c, a.arg("message"))
            else -> PhoneActions.editSms(context, c, a.arg("message"))
        }
    }
    LaunchedEffect(canRead) {
        if (canRead) contacts = withContext(Dispatchers.IO) { runCatching { PhoneActions.findContacts(context, query) }.getOrDefault(emptyList()) }
    }

    fun act(c: Contact) {
        val permission = if (sms) Manifest.permission.SEND_SMS else Manifest.permission.CALL_PHONE
        if (PhoneActions.has(context, permission)) {
            a.status = if (sms) PhoneActions.sendSms(context, c, a.arg("message")) else PhoneActions.call(context, c)
        } else {
            chosen = c
            askAct.launch(permission)
        }
    }

    Text(if (sms) "💬 SMS à « $query »" else "📞 Appeler « $query »", fontSize = 14.sp)
    if (sms) Text("« ${a.arg("message")} »", fontSize = 14.sp, color = colors.onSurfaceVariant)
    val list = contacts
    when {
        !canRead -> Button(onClick = { askContacts.launch(Manifest.permission.READ_CONTACTS) }) { Text("Autoriser l'accès aux contacts") }
        list == null -> Text("Recherche dans les contacts…", fontSize = 13.sp, color = colors.onSurfaceVariant)
        list.isEmpty() -> Text("⚠ Aucun contact « $query » avec un numéro", fontSize = 13.sp, color = colors.error)
        else -> list.forEach { c ->
            val who = c.name + (if (c.kind.isNotEmpty()) " · ${c.kind}" else "") + " · ${c.number}"
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { act(c) }) { Text(if (sms) "Envoyer à $who" else "Appeler $who", fontSize = 13.sp) }
                if (sms) OutlinedButton(onClick = { a.status = PhoneActions.editSms(context, c, a.arg("message")) }) { Text("Modifier") }
            }
        }
    }
    TextButton(onClick = { a.status = if (sms) "SMS annulé" else "Appel annulé" }) { Text("Annuler") }
}
