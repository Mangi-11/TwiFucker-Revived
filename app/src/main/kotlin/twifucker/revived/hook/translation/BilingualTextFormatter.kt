package twifucker.revived.hook.translation

object BilingualTextFormatter {
    private const val ORIGINAL_SEPARATOR = "\n\n"

    fun build(
        translatedText: String,
        originalText: String,
        trimTranslatedStart: Boolean = false,
    ): String? {
        val normalizedTranslatedText = translatedText.trim()
        val normalizedOriginalText = originalText.trim()
        if (normalizedTranslatedText.isBlank() || normalizedOriginalText.isBlank()) return null
        if (normalizedTranslatedText == normalizedOriginalText) return null
        if (normalizedTranslatedText.endsWith(normalizedOriginalText)) return null
        val displayedTranslatedText = if (trimTranslatedStart) {
            normalizedTranslatedText
        } else {
            translatedText.trimEnd()
        }
        return displayedTranslatedText + ORIGINAL_SEPARATOR + normalizedOriginalText
    }

    fun cacheKey(text: String): String = text.trim()
}
