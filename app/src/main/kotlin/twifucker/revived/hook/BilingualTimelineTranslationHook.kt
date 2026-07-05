package twifucker.revived.hook

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.TextView
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Map
import java.util.WeakHashMap

/**
 * 时间线自动翻译视图的双语对照 hook。
 *
 * 当前 X 12.5.0 时间线会把 Grok 翻译挂在 core tweet model 上，再由 tweetview 的
 * AutoTranslatedTweetViewDelegateBinder 绑定到 com.twitter.translation.d。这里先从绑定输入里
 * 缓存“译文 -> 原文”，再在 TranslationView 写入 TextView 前替换成双语内容。
 */
object BilingualTimelineTranslationHook {
    private const val TAG = "TwiFuckerRevived/BilingualTimeline"

    private const val TIMELINE_TRANSLATION_BINDER =
        "com.twitter.tweetview.core.ui.translation.i"
    private const val TRANSLATION_VIEW_DELEGATE =
        "com.twitter.translation.d"
    private const val VIEW_STATE =
        "com.twitter.tweetview.core.a0"
    private const val MODEL_TWEET =
        "com.twitter.model.core.e"
    private const val CORE_TWEET =
        "com.twitter.model.core.d"
    private const val GROK_TRANSLATED_POST =
        "com.twitter.model.grok.g"
    private const val CONTENT =
        "com.twitter.model.core.entity.f1"
    private const val CONTENT_ENTITIES =
        "com.twitter.model.core.entity.h1"

    private const val ORIGINAL_SEPARATOR = "\n\n"
    private const val MAX_TRANSLATION_CACHE_SIZE = 512

    private val registeredMethods =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<Method, Boolean>()))

    private val translationTextToOriginalText: MutableMap<String, String> =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, String>(MAX_TRANSLATION_CACHE_SIZE, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
                    return size > MAX_TRANSLATION_CACHE_SIZE
                }
            },
        )

    private val activeTranslations: MutableMap<Any, ActiveTranslationRecord> =
        Collections.synchronizedMap(WeakHashMap())

    private val mainHandler by lazy(LazyThreadSafetyMode.NONE) {
        Handler(Looper.getMainLooper())
    }

    private val preferenceListener = object : BilingualTranslationPreference.Listener {
        override fun onBilingualTranslationChanged(enabled: Boolean) {
            refreshActiveTranslations(enabled)
        }
    }

    @Volatile
    private var activeRenderMethod: Method? = null

    @Volatile
    private var activeShape: TimelineTranslationShape? = null

    @Volatile
    private var activeXposed: XposedInterface? = null

    @Volatile
    private var preferenceListenerRegistered = false

    fun register(xposed: XposedInterface, classLoader: ClassLoader) {
        val shape = try {
            TimelineTranslationShape.resolve(classLoader)
        } catch (t: Throwable) {
            xposed.log(Log.ERROR, TAG, "resolve timeline translation shape failed: ${t.javaClass.name}: ${t.message}", t)
            return
        }

        try {
            installMappingHook(xposed, classLoader, shape)
        } catch (t: Throwable) {
            xposed.log(Log.ERROR, TAG, "timeline translation mapping hook failed: ${t.javaClass.name}: ${t.message}", t)
        }
        try {
            installRenderHook(xposed, classLoader, shape)
        } catch (t: Throwable) {
            xposed.log(Log.ERROR, TAG, "timeline translation render hook failed: ${t.javaClass.name}: ${t.message}", t)
        }
    }

    private fun installMappingHook(
        xposed: XposedInterface,
        classLoader: ClassLoader,
        shape: TimelineTranslationShape,
    ) {
        val binderClass = classLoader.loadClass(TIMELINE_TRANSLATION_BINDER)
        val invokeMethod = binderClass.declaredMethods
            .firstOrNull { method -> method.name == "invoke" && method.parameterCount == 1 }
            ?: throw NoSuchMethodException("invoke(Object) on $TIMELINE_TRANSLATION_BINDER")

        if (!registeredMethods.add(invokeMethod)) {
            xposed.log(Log.INFO, TAG, "Already registered mapping on ${binderClass.simpleName}.${invokeMethod.name}, skip")
            return
        }

        xposed.hook(invokeMethod).intercept { chain ->
            cacheTimelineTranslations(chain.getArg(0), shape)
            chain.proceed()
        }

        xposed.log(Log.INFO, TAG, "Registered mapping on ${binderClass.simpleName}.${invokeMethod.name}")
    }

    private fun installRenderHook(
        xposed: XposedInterface,
        classLoader: ClassLoader,
        shape: TimelineTranslationShape,
    ) {
        val delegateClass = classLoader.loadClass(TRANSLATION_VIEW_DELEGATE)
        val renderMethod = delegateClass.declaredMethods
            .firstOrNull { method ->
                method.name == "a" &&
                    method.parameterCount == 3 &&
                    method.parameterTypes[0] == shape.contentClass
            }
            ?: throw NoSuchMethodException("a(f1, g, Function0) on $TRANSLATION_VIEW_DELEGATE")
        renderMethod.isAccessible = true

        if (!registeredMethods.add(renderMethod)) {
            xposed.log(Log.INFO, TAG, "Already registered render on ${delegateClass.simpleName}.${renderMethod.name}, skip")
            return
        }

        activeRenderMethod = renderMethod
        activeShape = shape
        activeXposed = xposed
        installPreferenceListener(xposed)

        xposed.hook(renderMethod).intercept { chain ->
            val content = chain.getArg(0) ?: return@intercept chain.proceed()
            val translatedText = readContentText(content, shape.contentTextGetter) ?: return@intercept chain.proceed()
            val originalText = findOriginalText(translatedText) ?: return@intercept chain.proceed()
            rememberActiveTranslation(
                delegate = chain.getThisObject(),
                content = content,
                textStyle = chain.getArg(1),
                showMoreCallback = chain.getArg(2),
            )

            if (!BilingualTranslationPreference.isEnabled()) return@intercept chain.proceed()

            val bilingualText = buildBilingualText(translatedText, originalText) ?: return@intercept chain.proceed()

            val newContent = shape.contentConstructor.newInstance(
                bilingualText,
                shape.contentEntitiesGetter.invoke(content),
                null,
            )
            val args = chain.getArgs().toTypedArray()
            args[0] = newContent

            xposed.log(Log.DEBUG, TAG, "Applied timeline bilingual translation")
            chain.proceed(args)
        }

        xposed.log(Log.INFO, TAG, "Registered render on ${delegateClass.simpleName}.${renderMethod.name}")
    }

    private fun installPreferenceListener(xposed: XposedInterface) {
        val shouldRegister = synchronized(this) {
            if (preferenceListenerRegistered) {
                false
            } else {
                preferenceListenerRegistered = true
                true
            }
        }
        if (shouldRegister) {
            BilingualTranslationPreference.addListener(preferenceListener)
            xposed.log(Log.INFO, TAG, "Registered bilingual preference listener")
        }
    }

    private fun cacheTimelineTranslations(pair: Any?, shape: TimelineTranslationShape): Int {
        if (pair == null || !shape.pairClass.isInstance(pair)) return 0

        val viewState = shape.pairFirstField.get(pair) ?: return 0
        if (!shape.viewStateClass.isInstance(viewState)) return 0

        val modelTweet = shape.viewStateTweetField.get(viewState) ?: return 0
        val coreTweet = shape.modelTweetCoreField.get(modelTweet) ?: return 0
        val originalContent = shape.originalContentField.get(coreTweet) ?: return 0
        val originalText = readContentText(originalContent, shape.contentTextGetter) ?: return 0
        if (originalText.isBlank()) return 0

        val grokPost = shape.grokTranslatedPostMethod.invoke(coreTweet) ?: return 0
        var cachedCount = 0
        for (contentField in shape.grokTranslatedContentFields) {
            val translatedContent = contentField.get(grokPost) ?: continue
            val translatedText = readContentText(translatedContent, shape.contentTextGetter) ?: continue
            if (cacheTranslationText(translatedText, originalText)) {
                cachedCount += 1
            }
        }
        return cachedCount
    }

    private fun cacheTranslationText(translatedText: String, originalText: String): Boolean {
        if (buildBilingualText(translatedText, originalText) == null) return false
        translationTextToOriginalText[normalizeKey(translatedText)] = originalText.trim()
        return true
    }

    private fun rememberActiveTranslation(
        delegate: Any?,
        content: Any,
        textStyle: Any?,
        showMoreCallback: Any?,
    ) {
        if (delegate == null) return
        activeTranslations[delegate] = ActiveTranslationRecord(
            content = content,
            textStyle = textStyle,
            showMoreCallback = showMoreCallback,
        )
    }

    private fun refreshActiveTranslations(enabled: Boolean) {
        val renderMethod = activeRenderMethod ?: return
        val shape = activeShape ?: return
        runOnMainThread {
            val entries = synchronized(activeTranslations) {
                activeTranslations.entries.map { it.key to it.value }
            }
            var refreshedCount = 0
            var skippedCount = 0
            var failedCount = 0
            var firstFailure: Throwable? = null

            for ((delegate, record) in entries) {
                if (!isDelegateTranslationVisible(delegate, shape)) {
                    skippedCount += 1
                    continue
                }
                runCatching {
                    renderMethod.invoke(delegate, record.content, record.textStyle, record.showMoreCallback)
                }.onSuccess {
                    refreshedCount += 1
                }.onFailure { throwable ->
                    failedCount += 1
                    if (firstFailure == null) {
                        firstFailure = throwable
                    }
                }
            }

            val xposed = activeXposed
            if (failedCount > 0) {
                val failure = firstFailure
                if (failure != null) {
                    xposed?.log(
                        Log.ERROR,
                        TAG,
                        "refresh active timeline translations failed: count=$failedCount, first=${failure.javaClass.name}: ${failure.message}",
                        failure,
                    )
                } else {
                    xposed?.log(Log.ERROR, TAG, "refresh active timeline translations failed: count=$failedCount")
                }
            }
            xposed?.log(
                Log.DEBUG,
                TAG,
                "Refreshed active timeline translations: count=$refreshedCount, skipped=$skippedCount, bilingual=$enabled",
            )
        }
    }

    private fun isDelegateTranslationVisible(delegate: Any, shape: TimelineTranslationShape): Boolean {
        val textView = shape.translationTextViewField.get(delegate) as? View ?: return true
        return textView.visibility == View.VISIBLE && textView.windowToken != null
    }

    private fun runOnMainThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private fun findOriginalText(translatedText: String): String? {
        return translationTextToOriginalText[normalizeKey(translatedText)]
    }

    private fun readContentText(content: Any, textGetter: Method): String? {
        return textGetter.invoke(content)?.toString()
    }

    private fun buildBilingualText(translatedText: String, originalText: String): String? {
        val normalizedTranslatedText = translatedText.trim()
        val normalizedOriginalText = originalText.trim()
        if (normalizedTranslatedText.isBlank() || normalizedOriginalText.isBlank()) return null
        if (normalizedTranslatedText == normalizedOriginalText) return null
        if (normalizedTranslatedText.endsWith(normalizedOriginalText)) return null
        return translatedText.trimEnd() + ORIGINAL_SEPARATOR + normalizedOriginalText
    }

    private fun normalizeKey(text: String): String = text.trim()

    private data class ActiveTranslationRecord(
        val content: Any,
        val textStyle: Any?,
        val showMoreCallback: Any?,
    )

    private data class TimelineTranslationShape(
        val pairClass: Class<*>,
        val viewStateClass: Class<*>,
        val contentClass: Class<*>,
        val pairFirstField: Field,
        val viewStateTweetField: Field,
        val modelTweetCoreField: Field,
        val originalContentField: Field,
        val grokTranslatedPostMethod: Method,
        val grokTranslatedContentFields: List<Field>,
        val contentTextGetter: Method,
        val contentEntitiesGetter: Method,
        val contentConstructor: Constructor<*>,
        val translationTextViewField: Field,
    ) {
        companion object {
            fun resolve(classLoader: ClassLoader): TimelineTranslationShape {
                val pairClass = classLoader.loadClass("kotlin.Pair")
                val viewStateClass = classLoader.loadClass(VIEW_STATE)
                val translationDelegateClass = classLoader.loadClass(TRANSLATION_VIEW_DELEGATE)
                val modelTweetClass = classLoader.loadClass(MODEL_TWEET)
                val coreTweetClass = classLoader.loadClass(CORE_TWEET)
                val grokTranslatedPostClass = classLoader.loadClass(GROK_TRANSLATED_POST)
                val contentClass = classLoader.loadClass(CONTENT)
                val contentEntitiesClass = classLoader.loadClass(CONTENT_ENTITIES)

                val pairFirstField = pairClass.declaredFields
                    .filterNot { Modifier.isStatic(it.modifiers) }
                    .first()
                    .apply { isAccessible = true }
                val viewStateTweetField = viewStateClass.declaredFields
                    .first { it.type == modelTweetClass }
                    .apply { isAccessible = true }
                val modelTweetCoreField = modelTweetClass.declaredFields
                    .first { it.type == coreTweetClass }
                    .apply { isAccessible = true }
                val originalContentField = coreTweetClass.getDeclaredField("k")
                    .apply { isAccessible = true }
                val grokTranslatedPostMethod = coreTweetClass.getMethod("b")
                val grokTranslatedContentFields = grokTranslatedPostClass.declaredFields
                    .filter { field -> field.type == contentClass && !Modifier.isStatic(field.modifiers) }
                    .onEach { it.isAccessible = true }
                require(grokTranslatedContentFields.isNotEmpty()) {
                    "translated content fields on $GROK_TRANSLATED_POST"
                }

                val contentTextGetter = contentClass.getMethod("getText")
                val contentEntitiesGetter = contentClass.getMethod("b")
                val contentConstructor = contentClass.getConstructor(
                    String::class.java,
                    contentEntitiesClass,
                    Map::class.java,
                )
                val translationTextViewField = translationDelegateClass.declaredFields
                    .first { it.type == TextView::class.java }
                    .apply { isAccessible = true }

                return TimelineTranslationShape(
                    pairClass = pairClass,
                    viewStateClass = viewStateClass,
                    contentClass = contentClass,
                    pairFirstField = pairFirstField,
                    viewStateTweetField = viewStateTweetField,
                    modelTweetCoreField = modelTweetCoreField,
                    originalContentField = originalContentField,
                    grokTranslatedPostMethod = grokTranslatedPostMethod,
                    grokTranslatedContentFields = grokTranslatedContentFields,
                    contentTextGetter = contentTextGetter,
                    contentEntitiesGetter = contentEntitiesGetter,
                    contentConstructor = contentConstructor,
                    translationTextViewField = translationTextViewField,
                )
            }
        }
    }
}
