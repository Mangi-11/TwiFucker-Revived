package twifucker.revived.hook

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.TextView
import io.github.libxposed.api.XposedInterface
import twifucker.revived.core.HookContext
import twifucker.revived.core.HookInstallResult
import twifucker.revived.core.HookInstallScope
import twifucker.revived.core.HookLocator
import twifucker.revived.core.TargetHook
import twifucker.revived.hook.translation.ActiveTimelineTranslations
import twifucker.revived.hook.translation.BilingualTextFormatter
import twifucker.revived.hook.translation.TimelineTranslationCache
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.Map
import java.util.WeakHashMap

/**
 * 时间线自动翻译视图的双语对照 hook。
 *
 * 当前 X 12.5.0 时间线会把 Grok 翻译挂在 core tweet model 上，再由 tweetview 的
 * AutoTranslatedTweetViewDelegateBinder 绑定到 com.twitter.translation.d。这里先从绑定输入里
 * 缓存“译文 -> 原文”，再在 TranslationView 写入 TextView 前替换成双语内容。
 */
object BilingualTimelineTranslationHook : TargetHook {
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

    private val registeredMethods =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<Method, Boolean>()))

    private val translationCache = TimelineTranslationCache()
    private val activeTranslations = ActiveTimelineTranslations()

    private val mainHandler by lazy(LazyThreadSafetyMode.NONE) {
        Handler(Looper.getMainLooper())
    }

    private val preferenceListener = object : BilingualTranslationPreference.Listener {
        override fun onBilingualTranslationChanged(enabled: Boolean) {
            refreshActiveTranslations(enabled)
        }
    }

    override val name = "BilingualTimelineTranslation"
    override val expectedHooks = 2

    @Volatile
    private var activeRenderMethod: Method? = null

    @Volatile
    private var activeShape: TimelineTranslationShape? = null

    @Volatile
    private var activeXposed: XposedInterface? = null

    @Volatile
    private var preferenceListenerRegistered = false

    fun register(xposed: XposedInterface, classLoader: ClassLoader) {
        install(HookContext(xposed, classLoader))
    }

    override fun install(context: HookContext): HookInstallResult {
        val scope = HookInstallScope(name, expectedHooks)
        val shape = try {
            TimelineTranslationShape.resolve(context.classLoader)
        } catch (t: Throwable) {
            scope.fail("resolve timeline translation shape", t)
            return scope.result()
        }

        scope.install("timeline translation mapping") {
            installMappingHook(context.xposed, context.classLoader, shape)
        }
        scope.install("timeline translation render") {
            installRenderHook(context.xposed, context.classLoader, shape)
        }
        return scope.result()
    }

    private fun installMappingHook(
        xposed: XposedInterface,
        classLoader: ClassLoader,
        shape: TimelineTranslationShape,
    ) {
        val locator = HookLocator(classLoader)
        val binderClass = locator.requireClass(TIMELINE_TRANSLATION_BINDER)
        val invokeMethod = locator.requireDeclaredMethod(binderClass, "invoke(Object)") { method ->
            method.name == "invoke" && method.parameterCount == 1
        }

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
        val locator = HookLocator(classLoader)
        val delegateClass = locator.requireClass(TRANSLATION_VIEW_DELEGATE)
        val renderMethod = locator.requireDeclaredMethod(delegateClass, "render translation") { method ->
            method.name == "a" &&
                method.parameterCount == 3 &&
                method.parameterTypes[0] == shape.contentClass
        }

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
        return translationCache.putIfBilingual(translatedText, originalText)
    }

    private fun rememberActiveTranslation(
        delegate: Any?,
        content: Any,
        textStyle: Any?,
        showMoreCallback: Any?,
    ) {
        activeTranslations.remember(
            delegate = delegate,
            content = content,
            textStyle = textStyle,
            showMoreCallback = showMoreCallback,
        )
    }

    private fun refreshActiveTranslations(enabled: Boolean) {
        val renderMethod = activeRenderMethod ?: return
        val shape = activeShape ?: return
        runOnMainThread {
            val entries = activeTranslations.snapshot()
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
        return translationCache.findOriginalText(translatedText)
    }

    private fun readContentText(content: Any, textGetter: Method): String? {
        return textGetter.invoke(content)?.toString()
    }

    private fun buildBilingualText(translatedText: String, originalText: String): String? {
        return BilingualTextFormatter.build(translatedText, originalText)
    }

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
                val locator = HookLocator(classLoader)
                val pairClass = locator.requireClass("kotlin.Pair")
                val viewStateClass = locator.requireClass(VIEW_STATE)
                val translationDelegateClass = locator.requireClass(TRANSLATION_VIEW_DELEGATE)
                val modelTweetClass = locator.requireClass(MODEL_TWEET)
                val coreTweetClass = locator.requireClass(CORE_TWEET)
                val grokTranslatedPostClass = locator.requireClass(GROK_TRANSLATED_POST)
                val contentClass = locator.requireClass(CONTENT)
                val contentEntitiesClass = locator.requireClass(CONTENT_ENTITIES)

                val pairFirstField = locator.requireDeclaredField(pairClass, "pair first field") { field ->
                    !Modifier.isStatic(field.modifiers)
                }
                val viewStateTweetField = locator.requireDeclaredField(viewStateClass, "view state tweet field") { field ->
                    field.type == modelTweetClass
                }
                val modelTweetCoreField = locator.requireDeclaredField(modelTweetClass, "core tweet field") { field ->
                    field.type == coreTweetClass
                }
                val originalContentField = locator.requireDeclaredField(coreTweetClass, "original content field") { field ->
                    field.name == "k"
                }
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
                val translationTextViewField =
                    locator.requireDeclaredField(translationDelegateClass, "translation TextView field") { field ->
                        field.type == TextView::class.java
                    }

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
