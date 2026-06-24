package twifucker.revived.hook

import android.content.Context

object BilingualTranslationPreference {
    private const val PREFS_NAME = "twifucker_revived_translation"
    private const val KEY_BILINGUAL_TRANSLATION_ENABLED = "bilingual_translation_enabled"

    @Volatile
    private var cachedEnabled: Boolean? = null

    fun isEnabled(context: Context? = null): Boolean {
        val appContext = context?.applicationContext ?: currentApplication()
        if (appContext == null) return cachedEnabled ?: true

        val enabled = runCatching {
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_BILINGUAL_TRANSLATION_ENABLED, true)
        }.getOrElse {
            cachedEnabled ?: true
        }
        cachedEnabled = enabled
        return enabled
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        cachedEnabled = enabled
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_BILINGUAL_TRANSLATION_ENABLED, enabled)
            .apply()
    }

    private fun currentApplication(): Context? = runCatching {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) as? Context
    }.getOrNull()
}
