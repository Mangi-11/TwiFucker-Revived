package twifucker.revived.hook.translation

import java.util.Collections
import java.util.LinkedHashMap

class TimelineTranslationCache(
    maxSize: Int = DEFAULT_MAX_SIZE,
) {
    private val translations: MutableMap<String, String> =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, String>(maxSize, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
                    return size > maxSize
                }
            },
        )

    fun putIfBilingual(translatedText: String, originalText: String): Boolean {
        if (BilingualTextFormatter.build(translatedText, originalText) == null) return false
        translations[BilingualTextFormatter.cacheKey(translatedText)] = originalText.trim()
        return true
    }

    fun findOriginalText(translatedText: String): String? {
        return translations[BilingualTextFormatter.cacheKey(translatedText)]
    }

    private companion object {
        const val DEFAULT_MAX_SIZE = 512
    }
}
