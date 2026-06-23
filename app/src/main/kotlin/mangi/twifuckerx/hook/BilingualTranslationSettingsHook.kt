package mangi.twifuckerx.hook

import android.content.Context
import android.os.SystemClock
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.WeakHashMap

/**
 * 在 X 官方“自动翻译”Compose 弹窗里追加 TwiFuckerX 的“双语对照”开关。
 *
 * 不叠加原生 View，也不自绘控件。这里复用 X 自己的 Compose preference row：
 * - 旧版路径：com.twitter.ui.components.preference.v0
 * - X-lite 路径：com.x.ui.common.ports.preference.n1
 *
 * 这样行高、左右 padding、开关尺寸和当前 X 版本保持一致。
 */
object BilingualTranslationSettingsHook {
    private const val TAG = "TwiFuckerX/BilingualSettings"

    private const val LEGACY_DIALOG_COMPOSABLE = "com.twitter.translation.dialog.h"
    private const val X_LITE_DIALOG_COMPOSABLE = "com.x.groktranslate.h"
    private const val LEGACY_PREFERENCE_ROW = "com.twitter.ui.components.preference.v0"
    private const val X_LITE_PREFERENCE_ROW = "com.x.ui.common.ports.preference.n1"

    private const val BILINGUAL_TITLE = "双语对照"
    private const val BILINGUAL_SUBTITLE = "关闭后恢复官方翻译显示。"

    @Volatile
    private var lastAutoTranslationDialogRenderAt = 0L

    private val injectingRow = ThreadLocal.withInitial { false }
    private val registeredMethods =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<Method, Boolean>()))

    fun register(xposed: XposedInterface, classLoader: ClassLoader) {
        try {
            installDialogMarkerHook(xposed, classLoader, LEGACY_DIALOG_COMPOSABLE)
        } catch (t: Throwable) {
            xposed.log(Log.ERROR, TAG, "legacy dialog marker hook failed: ${t.javaClass.name}: ${t.message}", t)
        }
        try {
            installDialogMarkerHook(xposed, classLoader, X_LITE_DIALOG_COMPOSABLE)
        } catch (t: Throwable) {
            xposed.log(Log.ERROR, TAG, "x-lite dialog marker hook failed: ${t.javaClass.name}: ${t.message}", t)
        }
        try {
            installLegacyPreferenceRowHook(xposed, classLoader)
        } catch (t: Throwable) {
            xposed.log(Log.ERROR, TAG, "legacy preference row hook failed: ${t.javaClass.name}: ${t.message}", t)
        }
        try {
            installXLitePreferenceRowHook(xposed, classLoader)
        } catch (t: Throwable) {
            xposed.log(Log.ERROR, TAG, "x-lite preference row hook failed: ${t.javaClass.name}: ${t.message}", t)
        }
    }

    private fun installDialogMarkerHook(
        xposed: XposedInterface,
        classLoader: ClassLoader,
        dialogClassName: String,
    ) {
        val dialogClass = classLoader.loadClass(dialogClassName)
        val methods = dialogClass.declaredMethods.filter { method ->
            isAutoTranslationDialogComposable(dialogClassName, method)
        }
        if (methods.isEmpty()) throw NoSuchMethodException("auto translation composable on $dialogClassName")

        for (method in methods) {
            if (!registeredMethods.add(method)) {
                xposed.log(Log.INFO, TAG, "Already registered marker on ${dialogClass.simpleName}.${method.name}, skip")
                continue
            }
            xposed.hook(method).intercept { chain ->
                lastAutoTranslationDialogRenderAt = SystemClock.uptimeMillis()
                chain.proceed()
            }
            xposed.log(Log.INFO, TAG, "Registered marker on ${dialogClass.simpleName}.${method.name}")
        }
    }

    private fun installLegacyPreferenceRowHook(xposed: XposedInterface, classLoader: ClassLoader) {
        val preferenceClass = classLoader.loadClass(LEGACY_PREFERENCE_ROW)
        val function1Class = classLoader.loadClass("kotlin.jvm.functions.Function1")
        val composerClass = classLoader.loadClass("androidx.compose.runtime.Composer")

        val composableRow = preferenceClass.declaredMethods.first { method ->
            method.name == "b" &&
                method.parameterCount == 9 &&
                method.parameterTypes[6] == composerClass
        }

        if (!registeredMethods.add(composableRow)) {
            xposed.log(Log.INFO, TAG, "Already registered on ${preferenceClass.simpleName}.${composableRow.name}, skip")
            return
        }

        xposed.hook(composableRow).intercept { chain ->
            val result = chain.proceed()
            if (shouldInjectPreferenceRow()) {
                val modifier = chain.getArg(1)
                val composer = chain.getArg(6)
                try {
                    injectLegacyPreferenceRow(xposed, classLoader, composableRow, modifier, composer)
                } catch (t: Throwable) {
                    xposed.log(Log.ERROR, TAG, "inject legacy row failed: ${t.javaClass.name}: ${t.message}", t)
                }
            }
            result
        }

        xposed.log(Log.INFO, TAG, "Registered on ${preferenceClass.simpleName}.${composableRow.name}")
    }

    private fun installXLitePreferenceRowHook(xposed: XposedInterface, classLoader: ClassLoader) {
        val preferenceClass = classLoader.loadClass(X_LITE_PREFERENCE_ROW)
        val composerClass = classLoader.loadClass("androidx.compose.runtime.Composer")
        val rowMethod = preferenceClass.declaredMethods.first { method ->
            method.name == "a" &&
                method.parameterCount == 17 &&
                method.parameterTypes[0] == String::class.java &&
                method.parameterTypes[13] == composerClass
        }

        if (!registeredMethods.add(rowMethod)) {
            xposed.log(Log.INFO, TAG, "Already registered on ${preferenceClass.simpleName}.${rowMethod.name}, skip")
            return
        }

        xposed.hook(rowMethod).intercept { chain ->
            val result = chain.proceed()
            val title = chain.getArg(0) as? String
            if (shouldInjectPreferenceRow() && title != BILINGUAL_TITLE) {
                val composer = chain.getArg(13)
                try {
                    injectXLitePreferenceRow(xposed, classLoader, rowMethod, composer)
                } catch (t: Throwable) {
                    xposed.log(Log.ERROR, TAG, "inject x-lite row failed: ${t.javaClass.name}: ${t.message}", t)
                }
            }
            result
        }

        xposed.log(Log.INFO, TAG, "Registered on ${preferenceClass.simpleName}.${rowMethod.name}")
    }

    private fun injectLegacyPreferenceRow(
        xposed: XposedInterface,
        classLoader: ClassLoader,
        rowMethod: Method,
        modifier: Any?,
        composer: Any?,
    ) {
        runInjecting {
            val context = currentApplication(classLoader)
            val state = rememberSwitchState(classLoader, composer, BilingualTranslationPreference.isEnabled(context))
            rowMethod.invoke(
                null,
                internalComposableText(classLoader, BILINGUAL_TITLE, composer),
                modifier,
                internalComposableText(classLoader, BILINGUAL_SUBTITLE, composer),
                switchStateValue(state) ?: BilingualTranslationPreference.isEnabled(context),
                true,
                createToggleCallback(classLoader, context, xposed, state),
                composer,
                390,
                8,
            )
            xposed.log(Log.INFO, TAG, "Injected legacy bilingual translation switch")
        }
    }

    private fun internalComposableText(classLoader: ClassLoader, text: String, composer: Any?): Any {
        val function = classLoader.loadClass("com.twitter.ui.components.preference.r0")
            .getConstructor(String::class.java)
            .newInstance(text)
        return classLoader.loadClass("androidx.compose.runtime.internal.n")
            .declaredMethods
            .first { method -> method.name == "c" && method.parameterCount == 3 }
            .invoke(null, text.hashCode(), function, composer) as Any
    }

    private fun injectXLitePreferenceRow(
        xposed: XposedInterface,
        classLoader: ClassLoader,
        rowMethod: Method,
        composer: Any?,
    ) {
        runInjecting {
            val context = currentApplication(classLoader)
            val state = rememberSwitchState(classLoader, composer, BilingualTranslationPreference.isEnabled(context))
            rowMethod.invoke(
                null,
                BILINGUAL_TITLE,
                null,
                0f,
                xLiteRowPadding(classLoader),
                null,
                null,
                BILINGUAL_SUBTITLE,
                null,
                switchStateValue(state) ?: BilingualTranslationPreference.isEnabled(context),
                true,
                null,
                null,
                createToggleCallback(classLoader, context, xposed, state),
                composer,
                0,
                0,
                6582,
            )
            xposed.log(Log.INFO, TAG, "Injected x-lite bilingual translation switch")
        }
    }

    private fun xLiteRowPadding(classLoader: ClassLoader): Any {
        val spacingClass = classLoader.loadClass("com.x.compose.core.o2")
        val verticalPadding = spacingClass.getField("e").getFloat(null)
        return classLoader.loadClass("androidx.compose.foundation.layout.n3")
            .getMethod(
                "a",
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            .invoke(null, 0f, verticalPadding, 1) as Any
    }

    private fun createToggleCallback(
        classLoader: ClassLoader,
        context: Context?,
        xposed: XposedInterface,
        state: Any? = null,
    ): Any {
        val function1Class = classLoader.loadClass("kotlin.jvm.functions.Function1")
        return Proxy.newProxyInstance(classLoader, arrayOf(function1Class)) { proxy, method, args ->
            when (method.name) {
                "invoke" -> {
                    val enabled = args?.firstOrNull() as? Boolean ?: return@newProxyInstance null
                    if (context != null) {
                        BilingualTranslationPreference.setEnabled(context, enabled)
                        setSwitchStateValue(state, enabled)
                        xposed.log(Log.INFO, TAG, "Bilingual translation enabled: $enabled")
                    }
                    null
                }

                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "TwiFuckerX.BilingualTranslationToggle"
                else -> null
            }
        }
    }

    private fun rememberSwitchState(classLoader: ClassLoader, composer: Any?, initialValue: Boolean): Any? {
        if (composer == null) return null
        val composerClass = classLoader.loadClass("androidx.compose.runtime.Composer")
        composerClass.getMethod("t", Int::class.javaPrimitiveType).invoke(composer, 0x54584642)
        return try {
            val remembered = composerClass.getMethod("R").invoke(composer)
            if (isComposeEmptySentinel(remembered)) {
                val state = classLoader.loadClass("androidx.compose.runtime.q5")
                    .getMethod("f", Object::class.java)
                    .invoke(null, initialValue)
                composerClass.getMethod("K", Object::class.java).invoke(composer, state)
                state
            } else {
                remembered
            }
        } finally {
            composerClass.getMethod("q").invoke(composer)
        }
    }

    private fun isComposeEmptySentinel(value: Any?): Boolean =
        value != null && value.toString() == "Empty"

    private fun switchStateValue(state: Any?): Boolean? =
        (state?.javaClass?.methods?.firstOrNull { it.name == "getValue" && it.parameterCount == 0 }
            ?.invoke(state) as? Boolean)

    private fun setSwitchStateValue(state: Any?, value: Boolean) {
        state?.javaClass?.methods
            ?.firstOrNull { it.name == "setValue" && it.parameterCount == 1 }
            ?.invoke(state, value)
    }

    private fun shouldInjectPreferenceRow(): Boolean =
        injectingRow.get() != true && isRecentAutoTranslationDialog()

    private fun runInjecting(block: () -> Unit) {
        injectingRow.set(true)
        try {
            block()
        } finally {
            injectingRow.set(false)
        }
    }

    private fun currentApplication(classLoader: ClassLoader): Context? = runCatching {
        classLoader.loadClass("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) as? Context
    }.getOrNull()

    private fun isAutoTranslationDialogComposable(dialogClassName: String, method: Method): Boolean {
        if (dialogClassName == LEGACY_DIALOG_COMPOSABLE) {
            return method.name == "a" &&
                method.parameterCount == 6 &&
                method.parameterTypes.firstOrNull()?.name == "com.twitter.translation.dialog.AutoTranslationHelpDialogFragmentArgs"
        }

        return (method.name == "a" || method.name == "b") &&
            method.parameterTypes.firstOrNull()?.name == "com.x.groktranslate.i"
    }

    private fun isRecentAutoTranslationDialog(): Boolean =
        SystemClock.uptimeMillis() - lastAutoTranslationDialogRenderAt < 5_000L
}
