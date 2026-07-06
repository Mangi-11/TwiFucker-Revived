package twifucker.revived.hook

import io.github.libxposed.api.XposedInterface
import twifucker.revived.core.HookContext
import twifucker.revived.core.HookInstallResult
import twifucker.revived.core.HookInstallScope
import twifucker.revived.core.TargetHook
import twifucker.revived.core.logD
import twifucker.revived.core.logI
import twifucker.revived.hook.translation.BilingualTextFormatter
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
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
 * 手动点击“翻译”和服务端已下发的自动翻译结果都走同一条安全路径。当前只保留译文的
 * entityList，追加的原文不带可点击实体，避免为了显示原文去重建复杂的富文本索引。
 */
object BilingualTranslationHook : TargetHook {
    private const val TAG = "TwiFuckerRevived/BilingualTranslate"

    private const val MANUAL_TRANSLATION_PRESENTER =
        "com.x.urt.items.post.translate.grok.l"
    private const val AUTO_TRANSLATION_PRESENTER =
        "com.x.urt.items.post.translate.grok.c"
    private const val POST_RESULT =
        "com.x.models.PostResult"
    private const val TRANSLATED_POST =
        "com.x.groktranslate.TranslatedPost"
    private const val TRANSLATION_STATE =
        "com.x.groktranslate.i"
    private const val GROK_TRANSLATE_POST_STATE =
        "com.x.urt.items.post.translate.grok.x"

    private val registeredMethods =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<Method, Boolean>()))

    override val name = "BilingualTranslation"
    override val expectedHooks = 2

    fun register(xposed: XposedInterface, classLoader: ClassLoader) {
        install(HookContext(xposed, classLoader))
    }

    override fun install(context: HookContext): HookInstallResult {
        val scope = HookInstallScope(name, expectedHooks)
        scope.install("manual presenter") {
            installPresenterHook(context.xposed, context.classLoader, MANUAL_TRANSLATION_PRESENTER)
        }
        scope.install("auto presenter") {
            installPresenterHook(context.xposed, context.classLoader, AUTO_TRANSLATION_PRESENTER)
        }
        return scope.result()
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

        val postField = presenterClass.declaredFields
            .first { postResultClass.isAssignableFrom(it.type) }
            .apply { isAccessible = true }
        val getTextMethod = postResultClass.getMethod("getText")

        val stateShape = resolveStateShape(stateClass, translationStateClass)
        val translatedPostShape = resolveTranslatedPostShape(translatedPostClass)
        val completedShape = resolveCompletedStateShape(translationStateClass)
        val streamingShape = resolveStreamingStateShape(translationStateClass)

        val presenterMethod = presenterClass.declaredMethods
            .firstOrNull { method ->
                method.returnType == stateClass && method.parameterCount == 1
            }
            ?: throw NoSuchMethodException("presenter state method on $presenterName")

        if (!registeredMethods.add(presenterMethod)) {
            xposed.logI(TAG, "Already registered on ${presenterClass.simpleName}.${presenterMethod.name}, skip")
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
            ) ?: return@intercept state

            xposed.logD(TAG, "Applied bilingual translation")
            newState
        }

        xposed.logI(TAG, "Registered on ${presenterClass.simpleName}.${presenterMethod.name}")
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
    ): Any? {
        val oldTranslationState = stateShape.translationStateField.get(state) ?: return null
        val newTranslationState = when {
            completedShape.stateClass.isInstance(oldTranslationState) -> {
                val oldPost = completedShape.contentField.get(oldTranslationState) ?: return null
                if (!translatedPostClass.isInstance(oldPost)) return null
                val newPost = buildBilingualPost(oldPost, originalText, translatedPostShape) ?: return null
                completedShape.constructor.newInstance(newPost, completedShape.sourceLanguageField.get(oldTranslationState))
            }

            streamingShape.stateClass.isInstance(oldTranslationState) -> {
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
        return BilingualTextFormatter.build(
            translatedText = translatedText,
            originalText = originalText,
            trimTranslatedStart = true,
        )
    }

    private fun resolveStateShape(
        stateClass: Class<*>,
        translationStateClass: Class<*>,
    ): StateShape {
        val booleanFields = stateClass.declaredFields
            .filter { it.type == Boolean::class.javaPrimitiveType }
            .onEach { it.isAccessible = true }
        require(booleanFields.size >= 3) { "unexpected boolean field count on ${stateClass.name}" }

        val constructor = stateClass.declaredConstructors
            .firstOrNull { constructor ->
                val types = constructor.parameterTypes
                types.size == 7 &&
                    types[0] == Boolean::class.javaPrimitiveType &&
                    translationStateClass.isAssignableFrom(types[1]) &&
                    types[2] == Boolean::class.javaPrimitiveType &&
                    types[3] == String::class.java &&
                    types[4] == Boolean::class.javaPrimitiveType &&
                    types[6].name == "kotlin.jvm.functions.Function1"
            }
            ?: throw NoSuchMethodException("primary constructor on ${stateClass.name}")
        constructor.isAccessible = true
        val settingsStateClass = constructor.parameterTypes[5]

        val translationStateField = stateClass.declaredFields
            .first { translationStateClass.isAssignableFrom(it.type) }
            .apply { isAccessible = true }
        val settingsField = stateClass.declaredFields
            .first { it.type == settingsStateClass }
            .apply { isAccessible = true }
        val eventSinkField = stateClass.declaredFields
            .first { it.type.name == "kotlin.jvm.functions.Function1" }
            .apply { isAccessible = true }

        return StateShape(
            constructor = constructor,
            booleanFields = booleanFields.take(3),
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

    private fun resolveCompletedStateShape(translationStateClass: Class<*>): CompletedStateShape {
        val completedStateClass = translationStateClass.declaredClasses
            .firstOrNull { candidate ->
                translationStateClass.isAssignableFrom(candidate) &&
                    candidate.declaredConstructors.any { constructor ->
                        val types = constructor.parameterTypes
                        types.size == 2 && types[1] == String::class.java
                    }
            }
            ?: throw NoSuchMethodException("completed translation state on ${translationStateClass.name}")
        val contentField = completedStateClass.declaredFields
            .first { it.type != String::class.java && !java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .apply { isAccessible = true }
        val sourceLanguageField = completedStateClass.declaredFields
            .first { it.type == String::class.java }
            .apply { isAccessible = true }
        val constructor = completedStateClass.declaredConstructors
            .first { it.parameterTypes.size == 2 && it.parameterTypes[1] == String::class.java }
            .apply { isAccessible = true }
        return CompletedStateShape(completedStateClass, constructor, contentField, sourceLanguageField)
    }

    private fun resolveStreamingStateShape(translationStateClass: Class<*>): StreamingStateShape {
        val streamingStateClass = translationStateClass.declaredClasses
            .firstOrNull { candidate ->
                translationStateClass.isAssignableFrom(candidate) &&
                    candidate.declaredConstructors.any { it.parameterTypes.size == 1 }
            }
            ?: throw NoSuchMethodException("streaming translation state on ${translationStateClass.name}")
        val contentField = streamingStateClass.declaredFields
            .first { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .apply { isAccessible = true }
        val constructor = streamingStateClass.declaredConstructors
            .first { it.parameterTypes.size == 1 }
            .apply { isAccessible = true }
        return StreamingStateShape(streamingStateClass, constructor, contentField)
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
        val stateClass: Class<*>,
        val constructor: Constructor<*>,
        val contentField: Field,
        val sourceLanguageField: Field,
    )

    private data class StreamingStateShape(
        val stateClass: Class<*>,
        val constructor: Constructor<*>,
        val contentField: Field,
    )
}
