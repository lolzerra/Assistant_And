package fr.maison.assistant

import android.content.Context

/** Réglages de l'appli, gardés sur le téléphone. Le jeton du serveur n'est jamais affiché. */
class Prefs(context: Context) {
    private val p = context.getSharedPreferences("assistant", Context.MODE_PRIVATE)

    var server: String
        get() = p.getString("server", DEFAULT_SERVER) ?: DEFAULT_SERVER
        set(v) = p.edit().putString("server", v.trim().trimEnd('/')).apply()
    var token: String
        get() = p.getString("token", "") ?: ""
        set(v) = p.edit().putString("token", v).apply()
    var username: String
        get() = p.getString("username", "") ?: ""
        set(v) = p.edit().putString("username", v).apply()
    var model: String
        get() = p.getString("model", "qwen3.8") ?: "qwen3.8"
        set(v) = p.edit().putString("model", v.trim()).apply()
    var think: Boolean
        get() = p.getBoolean("think", false)
        set(v) = p.edit().putBoolean("think", v).apply()
    var web: Boolean
        get() = p.getBoolean("web", true)
        set(v) = p.edit().putBoolean("web", v).apply()
    var speak: Boolean
        get() = p.getBoolean("speak", true)
        set(v) = p.edit().putBoolean("speak", v).apply()
    var sendAfterDictation: Boolean
        get() = p.getBoolean("send_after_dictation", true)
        set(v) = p.edit().putBoolean("send_after_dictation", v).apply()
    /** Voix de lecture : tom, siwis, jessica, pierre, gilles (voir /api/speech/voices). */
    var voice: String
        get() = p.getString("voice", "tom") ?: "tom"
        set(v) = p.edit().putString("voice", v).apply()
    var newOnShortcut: Boolean
        get() = p.getBoolean("new_on_shortcut", true)
        set(v) = p.edit().putBoolean("new_on_shortcut", v).apply()
    var instructions: String
        get() = p.getString("instructions", DEFAULT_INSTRUCTIONS) ?: DEFAULT_INSTRUCTIONS
        set(v) = p.edit().putString("instructions", v).apply()
    var conversation: Int
        get() = p.getInt("conversation", 0)
        set(v) = p.edit().putInt("conversation", v).apply()

    val loggedIn get() = token.isNotEmpty()

    companion object {
        const val DEFAULT_SERVER = "http://192.168.1.41:8080"
        const val DEFAULT_INSTRUCTIONS =
            "Tu es l'assistant personnel sur le téléphone. Réponds en français, de façon courte et claire, " +
                "sauf si on te demande des détails. Quand une image ou une capture d'écran est jointe, décris ce qui est utile pour la question."
    }
}
