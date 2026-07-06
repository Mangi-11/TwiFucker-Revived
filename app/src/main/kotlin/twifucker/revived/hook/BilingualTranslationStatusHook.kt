package twifucker.revived.hook

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.libxposed.api.XposedInterface
import twifucker.revived.core.HookContext
import twifucker.revived.core.HookInstallResult
import twifucker.revived.core.HookInstallScope
import twifucker.revived.core.TargetHook
import twifucker.revived.core.logD
import twifucker.revived.core.logE
import twifucker.revived.core.logI
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/**
 * 翻译状态栏 action 文案修正。
 *
 * 官方在翻译已显示时使用“显示原文”，自动翻译的部分状态还会把 action 文案置空。双语对照开启后，
 * 翻译文本里已经包含原文，此时 action 的真实作用是切回原文视图，所以显示为“隐藏翻译”更准确。
 */
object BilingualTranslationStatusHook : TargetHook {
    private const val TAG = "TwiFuckerRevived/BilingualStatus"

    private const val STATUS_VIEW = "com.twitter.translation.GrokTranslationStatusView"
    private const val HIDE_TRANSLATION_LABEL = "隐藏翻译"
    private const val SHOW_ORIGINAL_FALLBACK_LABEL = "显示原文"

    private val registeredMethods =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<Method, Boolean>()))

    private val activeStatusViews: MutableMap<View, Unit> =
        Collections.synchronizedMap(WeakHashMap())

    private val mainHandler by lazy(LazyThreadSafetyMode.NONE) {
        Handler(Looper.getMainLooper())
    }

    private val preferenceListener = object : BilingualTranslationPreference.Listener {
        override fun onBilingualTranslationChanged(enabled: Boolean) {
            refreshActiveActionLabels(enabled)
        }
    }

    @Volatile
    private var lastLoggedBilingualState: Boolean? = null

    @Volatile
    private var activeGetTranslationActionMethod: Method? = null

    @Volatile
    private var activeXposed: XposedInterface? = null

    @Volatile
    private var preferenceListenerRegistered = false

    override val name = "BilingualTranslationStatus"
    override val expectedHooks = 1

    fun register(xposed: XposedInterface, classLoader: ClassLoader) {
        install(HookContext(xposed, classLoader))
    }

    override fun install(context: HookContext): HookInstallResult {
        val scope = HookInstallScope(name, expectedHooks)
        scope.install("status view") {
            installStatusHook(context.xposed, context.classLoader)
        }
        return scope.result()
    }

    private fun installStatusHook(xposed: XposedInterface, classLoader: ClassLoader) {
        val statusViewClass = classLoader.loadClass(STATUS_VIEW)
        val statusClass = classLoader.loadClass("$STATUS_VIEW\$a")
        val getTranslationActionMethod = statusViewClass.getMethod("getTranslationAction")
        val setStatusMethod = statusViewClass.getMethod("setStatus", statusClass)
        getTranslationActionMethod.isAccessible = true

        if (!registeredMethods.add(setStatusMethod)) {
            xposed.logI(TAG, "Already registered on ${statusViewClass.simpleName}.${setStatusMethod.name}, skip")
            return
        }

        activeGetTranslationActionMethod = getTranslationActionMethod
        activeXposed = xposed
        installPreferenceListener(xposed)

        xposed.hook(setStatusMethod).intercept { chain ->
            val result = chain.proceed()
            updateActionLabel(
                statusView = chain.getThisObject(),
                status = chain.getArg(0),
                getTranslationActionMethod = getTranslationActionMethod,
                xposed = xposed,
            )
            result
        }

        xposed.logI(TAG, "Registered on ${statusViewClass.simpleName}.${setStatusMethod.name}")
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
            xposed.logI(TAG, "Registered bilingual preference listener")
        }
    }

    private fun updateActionLabel(
        statusView: Any?,
        status: Any?,
        getTranslationActionMethod: Method,
        xposed: XposedInterface,
    ) {
        val view = statusView as? View ?: return
        val statusName = status?.toString() ?: return
        if (!isTranslationVisibleStatus(statusName)) {
            activeStatusViews.remove(view)
            return
        }
        if (!isTranslationTextVisible(view)) {
            activeStatusViews.remove(view)
            return
        }

        activeStatusViews[view] = Unit
        val bilingualEnabled = BilingualTranslationPreference.isEnabled(view.context)
        applyActionLabel(view, getTranslationActionMethod, bilingualEnabled)
        logLabelUpdate(xposed, bilingualEnabled)
    }

    private fun applyActionLabel(
        statusView: View,
        getTranslationActionMethod: Method,
        bilingualEnabled: Boolean,
    ) {
        val action = getTranslationActionMethod.invoke(statusView) as? TextView ?: return
        action.visibility = View.VISIBLE
        if (bilingualEnabled) {
            action.text = HIDE_TRANSLATION_LABEL
        } else {
            setShowOriginalText(action)
        }
    }

    private fun refreshActiveActionLabels(enabled: Boolean) {
        val getTranslationActionMethod = activeGetTranslationActionMethod ?: return
        runOnMainThread {
            val views = synchronized(activeStatusViews) {
                activeStatusViews.keys.toList()
            }
            var refreshedCount = 0
            var skippedCount = 0
            var failedCount = 0
            var firstFailure: Throwable? = null

            for (view in views) {
                if (view.windowToken == null || !isTranslationTextVisible(view)) {
                    activeStatusViews.remove(view)
                    skippedCount += 1
                    continue
                }
                runCatching {
                    applyActionLabel(view, getTranslationActionMethod, enabled)
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
                    xposed?.logE(
                        TAG,
                        "refresh active status labels failed: count=$failedCount, first=${failure.javaClass.name}: ${failure.message}",
                        failure,
                    )
                } else {
                    xposed?.logE(TAG, "refresh active status labels failed: count=$failedCount")
                }
            }
            xposed?.logD(
                TAG,
                "Refreshed active status labels: count=$refreshedCount, skipped=$skippedCount, bilingual=$enabled",
            )
            if (xposed != null) {
                logLabelUpdate(xposed, enabled)
            }
        }
    }

    private fun isTranslationVisibleStatus(statusName: String): Boolean {
        return statusName.startsWith("ShowingTranslation(") ||
            statusName.startsWith("ShowingAutoTranslation(") ||
            statusName.startsWith("NoActionAutoTranslate(")
    }

    private fun isTranslationTextVisible(statusView: View): Boolean {
        if (statusView.visibility != View.VISIBLE) return false
        val textViewId = statusView.resources.getIdentifier(
            "grok_translation_text",
            "id",
            statusView.context.packageName,
        )
        if (textViewId == 0) return true

        val translationTextView = findNearbyViewById(statusView, textViewId) ?: return true
        return translationTextView.visibility == View.VISIBLE
    }

    private fun findNearbyViewById(statusView: View, viewId: Int): View? {
        var current: View? = statusView
        while (current != null) {
            if (current is ViewGroup) {
                val found = current.findViewById<View>(viewId)
                if (found != null) return found
            }
            current = current.parent as? View
        }
        return statusView.rootView.findViewById(viewId)
    }

    private fun setShowOriginalText(action: TextView) {
        val showOriginalId = action.resources.getIdentifier(
            "translate_show_original",
            "string",
            action.context.packageName,
        )
        if (showOriginalId != 0) {
            action.setText(showOriginalId)
        } else {
            action.text = SHOW_ORIGINAL_FALLBACK_LABEL
        }
    }

    private fun logLabelUpdate(xposed: XposedInterface, bilingualEnabled: Boolean) {
        val shouldLog = synchronized(this) {
            if (lastLoggedBilingualState == bilingualEnabled) {
                false
            } else {
                lastLoggedBilingualState = bilingualEnabled
                true
            }
        }
        if (shouldLog) {
            xposed.logD(TAG, "Updated translation action label: bilingual=$bilingualEnabled")
        }
    }

    private fun runOnMainThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }
}
