package twifucker.revived.hook

import android.content.Context
import java.util.Collections
import java.util.WeakHashMap

object BilingualTranslationPreference {
    private const val PREFS_NAME = "twifucker_revived_translation"
    private const val KEY_BILINGUAL_TRANSLATION_ENABLED = "bilingual_translation_enabled"

    interface Listener {
        fun onBilingualTranslationChanged(enabled: Boolean)
    }

    private val listeners =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<Listener, Boolean>()))

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
        val previous = isEnabled(context)
        cachedEnabled = enabled
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_BILINGUAL_TRANSLATION_ENABLED, enabled)
            .apply()
        if (previous != enabled) {
            notifyListeners(enabled)
        }
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private fun notifyListeners(enabled: Boolean) {
        val snapshot = synchronized(listeners) {
            listeners.toList()
        }
        for (listener in snapshot) {
            runCatching {
                listener.onBilingualTranslationChanged(enabled)
            }
        }
    }

    private fun currentApplication(): Context? = runCatching {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) as? Context
    }.getOrNull()
}
