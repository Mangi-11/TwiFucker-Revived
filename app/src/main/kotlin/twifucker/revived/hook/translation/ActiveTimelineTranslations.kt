package twifucker.revived.hook.translation

import java.util.Collections
import java.util.WeakHashMap

class ActiveTimelineTranslations {
    private val records: MutableMap<Any, ActiveTimelineTranslationRecord> =
        Collections.synchronizedMap(WeakHashMap())

    fun remember(
        delegate: Any?,
        content: Any,
        textStyle: Any?,
        showMoreCallback: Any?,
    ) {
        if (delegate == null) return
        records[delegate] = ActiveTimelineTranslationRecord(
            content = content,
            textStyle = textStyle,
            showMoreCallback = showMoreCallback,
        )
    }

    fun snapshot(): List<Pair<Any, ActiveTimelineTranslationRecord>> {
        return synchronized(records) {
            records.entries.map { it.key to it.value }
        }
    }
}

data class ActiveTimelineTranslationRecord(
    val content: Any,
    val textStyle: Any?,
    val showMoreCallback: Any?,
)
