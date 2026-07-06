package twifucker.revived.hook

import android.util.Log
import android.view.View
import io.github.libxposed.api.XposedInterface
import twifucker.revived.core.HookContext
import twifucker.revived.core.HookInstallResult
import twifucker.revived.core.HookInstallScope
import twifucker.revived.core.TargetHook
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/**
 * 隐藏 Google 原生广告 view。
 *
 * Twitter 在部分场景嵌入 com.google.android.gms.ads.nativead.NativeAdView 渲染第三方原生广告。
 * hook 其 onVisibilityChanged，在 view 即将显示时设为 GONE 并跳过原方法，使广告不可见。
 *
 * 若当前 X 未集成 Google ads（NativeAdView 类不存在），INFO 日志 skip，不算失败。
 * onVisibilityChanged 按 (View, int) -> void 签名定位，不硬编码方法名。
 */
object GoogleAdsHook : TargetHook {
    private const val TAG = "TwiFuckerRevived/GoogleAds"

    private const val NATIVE_AD_VIEW_CLASS =
        "com.google.android.gms.ads.nativead.NativeAdView"

    private val registeredMethods =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<Method, Boolean>()))

    override val name = "GoogleAds"
    override val expectedHooks = 1

    fun register(xposed: XposedInterface, classLoader: ClassLoader) {
        install(HookContext(xposed, classLoader))
    }

    override fun install(context: HookContext): HookInstallResult {
        val scope = HookInstallScope(name, expectedHooks)
        scope.install("native ad view") {
            installNativeAdViewHook(context.xposed, context.classLoader)
        }
        return scope.result()
    }

    private fun installNativeAdViewHook(xposed: XposedInterface, classLoader: ClassLoader) {
        val adViewClass = try {
            classLoader.loadClass(NATIVE_AD_VIEW_CLASS)
        } catch (e: ClassNotFoundException) {
            xposed.log(Log.INFO, TAG, "NativeAdView not present, skip")
            return
        }

        val method = adViewClass.declaredMethods.firstOrNull { m ->
            m.returnType == Void.TYPE &&
                m.parameterCount == 2 &&
                m.parameterTypes[0] == View::class.java &&
                m.parameterTypes[1] == Int::class.javaPrimitiveType
        } ?: throw NoSuchMethodException("onVisibilityChanged on $NATIVE_AD_VIEW_CLASS")

        if (!registeredMethods.add(method)) {
            xposed.log(Log.INFO, TAG, "Already registered on ${adViewClass.simpleName}.${method.name}, skip")
            return
        }

        xposed.hook(method).intercept { chain ->
            val view = chain.getThisObject() as? View
            view?.visibility = View.GONE
            xposed.log(Log.DEBUG, TAG, "Hidden Google native ad view")
            null
        }

        xposed.log(Log.INFO, TAG, "Registered on ${adViewClass.simpleName}.${method.name}")
    }
}
