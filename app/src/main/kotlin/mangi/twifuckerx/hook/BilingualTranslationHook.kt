package mangi.twifuckerx.hook

import android.util.Log
import android.widget.TextView
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.LinkedHashMap
import java.util.WeakHashMap

/**
 * 官方 X Grok 翻译结果的双语对照 hook。
 *
 * 目标不是自己请求翻译接口，而是复用官方 X 已经拿到的翻译结果，在 presenter 返回 UI state 后，
 * 将 TranslatedPost.text 改成：
 *
 * 译文
 *
 * 原文
 *
 * 并把顶部按钮从官方“显示原文”改成“隐藏翻译”，让它与双语正文状态匹配。手动点击“翻译”和
 * 服务端已下发的自动翻译结果都走同一条安全路径。第一版只保留译文的 entityList，追加的原文
 * 不带可点击实体，避免为了显示原文去重建复杂的富文本索引。
 */
object BilingualTranslationHook {
    private const val TAG = "TwiFuckerX/BilingualTranslate"

    private const val MANUAL_TRANSLATION_PRESENTER =
        "com.x.urt.items.post.translate.grok.l"
    private const val AUTO_TRANSLATION_PRESENTER =
        "com.x.urt.items.post.translate.grok.c"
    private const val POST_RESULT =
        "com.x.models.PostResult"
    private const val TRANSLATED_POST =
        "com.x.groktranslate.TranslatedPost"
    private const val TRANSLATION_STATE =
        "com.x.groktranslate.j"
    private const val TRANSLATION_COMPLETED_STATE =
        "com.x.groktranslate.j\$a"
    private const val TRANSLATION_STREAMING_STATE =
        "com.x.groktranslate.j\$e"
    private const val TRANSLATION_SETTINGS_STATE =
        "com.x.groktranslate.i"
    private const val GROK_TRANSLATE_POST_STATE =
        "com.x.urt.items.post.translate.grok.w"

    private const val LEGACY_AUTO_TRANSLATION_BINDER =
        "com.twitter.tweetview.core.ui.translation.i"
    private const val LEGACY_TRANSLATION_VIEW_DELEGATE =
        "com.twitter.translation.c"
    private const val LEGACY_TRANSLATION_INLINE_VIEW_DELEGATE =
        "com.twitter.translation.x"
    private const val LEGACY_TRANSLATION_STATUS_VIEW =
        "com.twitter.translation.GrokTranslationStatusView"
    private const val TWEET_VIEW_CORE_STATE =
        "com.twitter.tweetview.core.v"
    private const val LEGACY_TWEET =
        "com.twitter.model.core.e"
    private const val LEGACY_CORE_TWEET =
        "com.twitter.model.core.d"
    private const val LEGACY_GROK_TRANSLATED_POST =
        "com.twitter.model.grok.g"
    private const val LEGACY_CONTENT =
        "com.twitter.model.core.entity.i1"
    private const val LEGACY_ENTITIES =
        "com.twitter.model.core.entity.k1"

    private const val ORIGINAL_SEPARATOR = "\n\n"
    private const val HIDE_TRANSLATION_ACTION_LABEL = "隐藏翻译"

    private val registeredMethods =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<Method, Boolean>()))
    private val legacyOriginalByTranslationText =
        Collections.synchronizedMap(object : LinkedHashMap<String, String>(128, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean = size > 256
        })

    fun register(xposed: XposedInterface, classLoader: ClassLoader) {
        try {
            installPresenterHook(xposed, classLoader, MANUAL_TRANSLATION_PRESENTER)
        } catch (t: Throwable) {
            xposed.log(Log.ERROR, TAG, "manual presenter hook failed: ${t.javaClass.name}: ${t.message}", t)
        }
        try {
            installPresenterHook(xposed, classLoader, AUTO_TRANSLATION_PRESENTER)
        } catch (t: Throwable) {
            xposed.log(Log.ERROR, TAG, "auto presenter hook failed: ${t.javaClass.name}: ${t.message}", t)
        }
        try {
            installLegacyTranslationCacheHook(xposed, classLoader)
        } catch (t: Throwable) {
            xposed.log(Log.ERROR, TAG, "legacy cache hook failed: ${t.javaClass.name}: ${t.message}", t)
        }
        try {
            installLegacyTranslationContentHook(xposed, classLoader, LEGACY_TRANSLATION_VIEW_DELEGATE)
            installLegacyTranslationContentHook(xposed, classLoader, LEGACY_TRANSLATION_INLINE_VIEW_DELEGATE)
            installLegacyTranslationStatusHook(xposed, classLoader)
        } catch (t: Throwable) {
            xposed.log(Log.ERROR, TAG, "legacy content hook failed: ${t.javaClass.name}: ${t.message}", t)
        }
    }

    private fun installPresenterHook(
        xposed: XposedInterface,
        classLoader: ClassLoader,
        presenterName: String,
    ) {
        val presenterClass = classLoader.loadClass(presenterName)
        val stateClass = classLoader.loadClass(GROK_TRANSLATE_POST_STATE)
        val postResultClass = classLoader.loadClass(POST_RESULT)
        val translatedPostClass = classLoader.loadClass(TRANSLATED_POST)
        val translationStateClass = classLoader.loadClass(TRANSLATION_STATE)
        val completedStateClass = classLoader.loadClass(TRANSLATION_COMPLETED_STATE)
        val streamingStateClass = classLoader.loadClass(TRANSLATION_STREAMING_STATE)
        val settingsStateClass = classLoader.loadClass(TRANSLATION_SETTINGS_STATE)

        val postField = presenterClass.declaredFields
            .first { postResultClass.isAssignableFrom(it.type) }
            .apply { isAccessible = true }
        val getTextMethod = postResultClass.getMethod("getText")

        val stateShape = resolveStateShape(stateClass, translationStateClass, settingsStateClass)
        val translatedPostShape = resolveTranslatedPostShape(translatedPostClass)
        val completedShape = resolveCompletedStateShape(completedStateClass)
        val streamingShape = resolveStreamingStateShape(streamingStateClass)

        val presenterMethod = presenterClass.declaredMethods
            .firstOrNull { method ->
                method.returnType == stateClass && method.parameterCount == 1
            }
            ?: throw NoSuchMethodException("presenter state method on $presenterName")

        if (!registeredMethods.add(presenterMethod)) {
            xposed.log(Log.INFO, TAG, "Already registered on ${presenterClass.simpleName}.${presenterMethod.name}, skip")
            return
        }

        xposed.hook(presenterMethod).intercept { chain ->
            if (!BilingualTranslationPreference.isEnabled()) return@intercept chain.proceed()

            val state = chain.proceed() ?: return@intercept null
            val presenter = chain.getThisObject() ?: return@intercept state
            val originalText = readOriginalText(presenter, postField, getTextMethod)
            if (originalText.isNullOrBlank()) return@intercept state

            val newState = buildBilingualState(
                state = state,
                originalText = originalText,
                stateShape = stateShape,
                translatedPostShape = translatedPostShape,
                completedShape = completedShape,
                streamingShape = streamingShape,
                translatedPostClass = translatedPostClass,
                completedStateClass = completedStateClass,
                streamingStateClass = streamingStateClass,
            ) ?: return@intercept state

            xposed.log(Log.DEBUG, TAG, "Applied bilingual translation")
            newState
        }

        xposed.log(Log.INFO, TAG, "Registered on ${presenterClass.simpleName}.${presenterMethod.name}")
    }

    /**
     * 老时间线自动翻译链路仍使用 View/TextView 渲染。这个 Function1 在调用 view delegate 前同时
     * 持有原 tweet 与 GrokTranslatedPost，在这里缓存译文文本到原文文本的映射，供下一层渲染 hook 使用。
     */
    private fun installLegacyTranslationCacheHook(xposed: XposedInterface, classLoader: ClassLoader) {
        val binderClass = classLoader.loadClass(LEGACY_AUTO_TRANSLATION_BINDER)
        val pairClass = classLoader.loadClass("kotlin.Pair")
        val viewStateClass = classLoader.loadClass(TWEET_VIEW_CORE_STATE)
        val tweetClass = classLoader.loadClass(LEGACY_TWEET)
        val coreTweetClass = classLoader.loadClass(LEGACY_CORE_TWEET)
        val grokPostClass = classLoader.loadClass(LEGACY_GROK_TRANSLATED_POST)
        val contentClass = classLoader.loadClass(LEGACY_CONTENT)

        val tweetField = viewStateClass.declaredFields
            .first { it.type == tweetClass }
            .apply { isAccessible = true }
        val coreTweetField = tweetClass.declaredFields
            .first { it.type == coreTweetClass }
            .apply { isAccessible = true }
        val originalContentField = coreTweetClass.declaredFields
            .first { it.type == contentClass }
            .apply { isAccessible = true }
        val grokPostMethod = coreTweetClass.getMethod("b")
        val grokContentFields = grokPostClass.declaredFields
            .filter { it.type == contentClass }
            .onEach { it.isAccessible = true }

        val textField = contentClass.superclass.declaredFields
            .first { it.type == String::class.java }
            .apply { isAccessible = true }
        val pairFirstField = pairClass.declaredFields
            .first { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .apply { isAccessible = true }

        val invokeMethod = binderClass.declaredMethods
            .first { it.name == "invoke" && it.parameterCount == 1 }

        if (!registeredMethods.add(invokeMethod)) {
            xposed.log(Log.INFO, TAG, "Already registered on ${binderClass.simpleName}.${invokeMethod.name}, skip")
            return
        }

        xposed.hook(invokeMethod).intercept { chain ->
            if (!BilingualTranslationPreference.isEnabled()) return@intercept chain.proceed()

            val pair = chain.getArg(0) ?: return@intercept chain.proceed()
            try {
                cacheLegacyTranslationMapping(
                    pair = pair,
                    pairFirstField = pairFirstField,
                    tweetField = tweetField,
                    coreTweetField = coreTweetField,
                    originalContentField = originalContentField,
                    grokPostMethod = grokPostMethod,
                    grokContentFields = grokContentFields,
                    textField = textField,
                )
            } catch (t: Throwable) {
                xposed.log(Log.ERROR, TAG, "legacy cache failed: ${t.javaClass.name}: ${t.message}", t)
            }
            chain.proceed()
        }

        xposed.log(Log.INFO, TAG, "Registered legacy cache on ${binderClass.simpleName}.${invokeMethod.name}")
    }

    private fun installLegacyTranslationContentHook(
        xposed: XposedInterface,
        classLoader: ClassLoader,
        delegateName: String,
    ) {
        val delegateClass = classLoader.loadClass(delegateName)
        val contentClass = classLoader.loadClass(LEGACY_CONTENT)
        val entitiesClass = classLoader.loadClass(LEGACY_ENTITIES)

        val textField = contentClass.superclass.declaredFields
            .first { it.type == String::class.java }
            .apply { isAccessible = true }
        val entitiesField = contentClass.declaredFields
            .first { it.type == entitiesClass }
            .apply { isAccessible = true }
        val contentConstructor = contentClass.declaredConstructors
            .first { constructor ->
                val types = constructor.parameterTypes
                types.size == 3 &&
                    types[0] == String::class.java &&
                    types[1] == entitiesClass &&
                    Map::class.java.isAssignableFrom(types[2])
            }
            .apply { isAccessible = true }

        val bindMethod = delegateClass.declaredMethods
            .first { method ->
                method.name == "a" &&
                    method.parameterCount == 3 &&
                    method.parameterTypes[0] == contentClass
            }

        if (!registeredMethods.add(bindMethod)) {
            xposed.log(Log.INFO, TAG, "Already registered on ${delegateClass.simpleName}.${bindMethod.name}, skip")
            return
        }

        xposed.hook(bindMethod).intercept { chain ->
            if (!BilingualTranslationPreference.isEnabled()) return@intercept chain.proceed()

            try {
                val content = chain.getArg(0) ?: return@intercept chain.proceed()
                val translatedText = textField.get(content) as? String ?: return@intercept chain.proceed()
                val originalText = legacyOriginalByTranslationText[translatedText]
                    ?: return@intercept chain.proceed()
                val bilingualText = buildBilingualText(translatedText, originalText)
                    ?: return@intercept chain.proceed()
                val bilingualContent = contentConstructor.newInstance(
                    bilingualText,
                    entitiesField.get(content),
                    null,
                )
                val args = chain.argsToArray()
                args[0] = bilingualContent
                xposed.log(Log.DEBUG, TAG, "Applied legacy bilingual translation")
                chain.proceed(args)
            } catch (t: Throwable) {
                xposed.log(Log.ERROR, TAG, "legacy content failed: ${t.javaClass.name}: ${t.message}", t)
                chain.proceed()
            }
        }

        xposed.log(Log.INFO, TAG, "Registered legacy content on ${delegateClass.simpleName}.${bindMethod.name}")
    }

    private fun installLegacyTranslationStatusHook(xposed: XposedInterface, classLoader: ClassLoader) {
        val statusViewClass = classLoader.loadClass(LEGACY_TRANSLATION_STATUS_VIEW)
        val getActionMethod = statusViewClass.getMethod("getTranslationAction")
        val setStatusMethod = statusViewClass.getMethod(
            "setStatus",
            statusViewClass.declaredClasses.first { it.simpleName == "a" },
        )

        if (!registeredMethods.add(setStatusMethod)) {
            xposed.log(Log.INFO, TAG, "Already registered on ${statusViewClass.simpleName}.${setStatusMethod.name}, skip")
            return
        }

        xposed.hook(setStatusMethod).intercept { chain ->
            chain.proceed()
            if (!BilingualTranslationPreference.isEnabled()) return@intercept null

            try {
                val status = chain.getArg(0) ?: return@intercept null
                if (!isShowingTranslationStatus(status)) return@intercept null
                val statusView = chain.getThisObject() ?: return@intercept null
                val actionView = getActionMethod.invoke(statusView) as? TextView ?: return@intercept null
                if (actionView.text?.toString() != HIDE_TRANSLATION_ACTION_LABEL) {
                    actionView.text = HIDE_TRANSLATION_ACTION_LABEL
                    xposed.log(Log.DEBUG, TAG, "Renamed show original action")
                }
            } catch (t: Throwable) {
                xposed.log(Log.ERROR, TAG, "legacy status failed: ${t.javaClass.name}: ${t.message}", t)
            }
            null
        }

        xposed.log(Log.INFO, TAG, "Registered legacy status on ${statusViewClass.simpleName}.${setStatusMethod.name}")
    }

    private fun cacheLegacyTranslationMapping(
        pair: Any,
        pairFirstField: Field,
        tweetField: Field,
        coreTweetField: Field,
        originalContentField: Field,
        grokPostMethod: Method,
        grokContentFields: List<Field>,
        textField: Field,
    ) {
        val viewState = pairFirstField.get(pair) ?: return
        val tweet = tweetField.get(viewState) ?: return
        val coreTweet = coreTweetField.get(tweet) ?: return
        val originalContent = originalContentField.get(coreTweet) ?: return
        val originalText = textField.get(originalContent) as? String ?: return
        if (originalText.isBlank()) return

        val grokPost = grokPostMethod.invoke(coreTweet) ?: return
        for (field in grokContentFields) {
            val translatedContent = field.get(grokPost) ?: continue
            val translatedText = textField.get(translatedContent) as? String ?: continue
            if (translatedText.isBlank()) continue
            if (buildBilingualText(translatedText, originalText) != null) {
                legacyOriginalByTranslationText[translatedText] = originalText
            }
        }
    }

    private fun readOriginalText(
        presenter: Any,
        postField: Field,
        getTextMethod: Method,
    ): String? {
        val post = postField.get(presenter) ?: return null
        return getTextMethod.invoke(post) as? String
    }

    private fun buildBilingualState(
        state: Any,
        originalText: String,
        stateShape: StateShape,
        translatedPostShape: TranslatedPostShape,
        completedShape: CompletedStateShape,
        streamingShape: StreamingStateShape,
        translatedPostClass: Class<*>,
        completedStateClass: Class<*>,
        streamingStateClass: Class<*>,
    ): Any? {
        val oldTranslationState = stateShape.translationStateField.get(state) ?: return null
        val newTranslationState = when {
            completedStateClass.isInstance(oldTranslationState) -> {
                val oldPost = completedShape.contentField.get(oldTranslationState) ?: return null
                if (!translatedPostClass.isInstance(oldPost)) return null
                val newPost = buildBilingualPost(oldPost, originalText, translatedPostShape) ?: return null
                completedShape.constructor.newInstance(newPost, completedShape.sourceLanguageField.get(oldTranslationState))
            }

            streamingStateClass.isInstance(oldTranslationState) -> {
                val oldPost = streamingShape.contentField.get(oldTranslationState) ?: return null
                if (!translatedPostClass.isInstance(oldPost)) return null
                val newPost = buildBilingualPost(oldPost, originalText, translatedPostShape) ?: return null
                streamingShape.constructor.newInstance(newPost)
            }

            else -> return null
        }

        val booleans = stateShape.booleanFields.map { it.getBoolean(state) }
        return stateShape.constructor.newInstance(
            booleans[0],
            newTranslationState,
            booleans[1],
            null,
            booleans[2],
            stateShape.settingsField.get(state),
            stateShape.eventSinkField.get(state),
        )
    }

    private fun buildBilingualPost(
        translatedPost: Any,
        originalText: String,
        shape: TranslatedPostShape,
    ): Any? {
        val translatedText = shape.textGetter.invoke(translatedPost) as? String ?: return null
        if (translatedText.isBlank()) return null
        val bilingualText = buildBilingualText(translatedText, originalText) ?: return null
        return shape.constructor.newInstance(
            bilingualText,
            shape.entityListGetter.invoke(translatedPost),
            shape.pollGetter.invoke(translatedPost),
        )
    }

    private fun buildBilingualText(translatedText: String, originalText: String): String? {
        val normalizedTranslatedText = translatedText.trim()
        val normalizedOriginalText = originalText.trim()
        if (normalizedTranslatedText.isBlank() || normalizedOriginalText.isBlank()) return null
        if (normalizedTranslatedText == normalizedOriginalText) return null
        if (normalizedTranslatedText.endsWith(normalizedOriginalText)) return null
        return normalizedTranslatedText + ORIGINAL_SEPARATOR + normalizedOriginalText
    }

    private fun isShowingTranslationStatus(status: Any): Boolean {
        val name = status.javaClass.name
        return name.endsWith("\$f") || name.endsWith("\$g")
    }

    private fun XposedInterface.Chain.argsToArray(): Array<Any?> =
        getArgs().toTypedArray()

    private fun resolveStateShape(
        stateClass: Class<*>,
        translationStateClass: Class<*>,
        settingsStateClass: Class<*>,
    ): StateShape {
        val booleanFields = stateClass.declaredFields
            .filter { it.type == Boolean::class.javaPrimitiveType }
            .onEach { it.isAccessible = true }
        require(booleanFields.size == 3) { "unexpected boolean field count on $GROK_TRANSLATE_POST_STATE" }

        val translationStateField = stateClass.declaredFields
            .first { it.type == translationStateClass }
            .apply { isAccessible = true }
        val settingsField = stateClass.declaredFields
            .first { it.type == settingsStateClass }
            .apply { isAccessible = true }
        val eventSinkField = stateClass.declaredFields
            .first { it.type.name == "kotlin.jvm.functions.Function1" }
            .apply { isAccessible = true }

        val constructor = stateClass.declaredConstructors
            .firstOrNull { constructor ->
                val types = constructor.parameterTypes
                types.size == 7 &&
                    types[0] == Boolean::class.javaPrimitiveType &&
                    types[1] == translationStateClass &&
                    types[2] == Boolean::class.javaPrimitiveType &&
                    types[3] == String::class.java &&
                    types[4] == Boolean::class.javaPrimitiveType &&
                    types[5] == settingsStateClass &&
                    types[6].name == "kotlin.jvm.functions.Function1"
            }
            ?: throw NoSuchMethodException("primary constructor on $GROK_TRANSLATE_POST_STATE")
        constructor.isAccessible = true

        return StateShape(
            constructor = constructor,
            booleanFields = booleanFields,
            translationStateField = translationStateField,
            settingsField = settingsField,
            eventSinkField = eventSinkField,
        )
    }

    private fun resolveTranslatedPostShape(translatedPostClass: Class<*>): TranslatedPostShape {
        val textGetter = translatedPostClass.getMethod("getText")
        val entityListGetter = translatedPostClass.getMethod("getEntityList")
        val pollGetter = translatedPostClass.getMethod("getPoll")
        val constructor = translatedPostClass.declaredConstructors
            .firstOrNull { constructor ->
                val types = constructor.parameterTypes
                types.size == 3 &&
                    types[0] == String::class.java &&
                    types[2] == List::class.java
            }
            ?: throw NoSuchMethodException("primary constructor on $TRANSLATED_POST")
        constructor.isAccessible = true
        return TranslatedPostShape(constructor, textGetter, entityListGetter, pollGetter)
    }

    private fun resolveCompletedStateShape(completedStateClass: Class<*>): CompletedStateShape {
        val contentField = completedStateClass.declaredFields
            .first { it.type != String::class.java && !java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .apply { isAccessible = true }
        val sourceLanguageField = completedStateClass.declaredFields
            .first { it.type == String::class.java }
            .apply { isAccessible = true }
        val constructor = completedStateClass.declaredConstructors
            .first { it.parameterTypes.size == 2 }
            .apply { isAccessible = true }
        return CompletedStateShape(constructor, contentField, sourceLanguageField)
    }

    private fun resolveStreamingStateShape(streamingStateClass: Class<*>): StreamingStateShape {
        val contentField = streamingStateClass.declaredFields
            .first { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .apply { isAccessible = true }
        val constructor = streamingStateClass.declaredConstructors
            .first { it.parameterTypes.size == 1 }
            .apply { isAccessible = true }
        return StreamingStateShape(constructor, contentField)
    }

    private data class StateShape(
        val constructor: Constructor<*>,
        val booleanFields: List<Field>,
        val translationStateField: Field,
        val settingsField: Field,
        val eventSinkField: Field,
    )

    private data class TranslatedPostShape(
        val constructor: Constructor<*>,
        val textGetter: Method,
        val entityListGetter: Method,
        val pollGetter: Method,
    )

    private data class CompletedStateShape(
        val constructor: Constructor<*>,
        val contentField: Field,
        val sourceLanguageField: Field,
    )

    private data class StreamingStateShape(
        val constructor: Constructor<*>,
        val contentField: Field,
    )
}
