package twifucker.revived.hook

import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/**
 * 本地解锁 Premium 状态（最小可验证版本）。
 *
 * 仅处理客户端本地 feature switch 与 userPreferences 判断，不碰支付流程、不伪造服务端订阅、
 * 不改网络请求。Premium 本地门控集中在 com.twitter.subscriptions.features.api.f：
 *
 * 1. feature switch 层：f.b()/e() 通过 com.twitter.util.config.c0 的 boolean getter
 *    （a/m，签名 (String, boolean) -> boolean）查询 subscriptions_enabled /
 *    subscriptions_gating_bypass / subscriptions_feature_1003 / 1005 等 key。把这些 key 的
 *    本地返回值强制为 true，让前置门控通过。
 *
 * 2. userPreferences 层：f.b() 还依赖 d() -> a.b(userPreferences) -> a.g(String[], k)，
 *    后者检查 userPreferences 的 "subscriptions" stringSet 是否含 premium feature 字符串
 *    （feature/twitter_blue 等）。若 d()=false，即使 subscriptions_enabled=true，仍会走到
 *    upsell 分支显示升级标志。hook a.g 对 premium feature 返回 true，让 d()=true，从而
 *    f.b() 返回 NOT_SUPPORTED，升级标志消失。
 *
 * 方法按签名定位，不硬编码方法名；a.g 所在的 Companion 类用 declaredClasses 定位，不硬编码
 * 混淆的内部类名。
 *
 * 边界：若发现某功能必须依赖服务端 entitlement（而非本地 feature switch/userPreferences），
 * 应停止，不硬绕。
 */
object LocalPremiumHook {
    private const val TAG = "TwiFuckerRevived/LocalPremium"

    private const val CONFIG_CLASS = "com.twitter.util.config.c0"
    private const val FEATURES_CLASS = "com.twitter.subscriptions.features.api.f"
    private const val PREFS_CLASS = "com.twitter.util.prefs.k"

    // 强制返回 true 的 feature switch key。对应 f.e() 门控链路：
    // subscriptions_enabled / subscriptions_gating_bypass 是前置开关，
    // subscriptions_feature_1003 / 1005 是具体功能 gate（grok 等）。
    private val forcedKeys = setOf(
        "subscriptions_enabled",
        "subscriptions_gating_bypass",
        "subscriptions_feature_1003",
        "subscriptions_feature_1005",
    )

    // premium feature 标识，对应 f.e 数组：检查 userPreferences 的 subscriptions stringSet
    // 是否含其中之一，是则认为用户已有 Premium（d() 返回 true）。
    private val premiumFeatures = setOf(
        "feature/twitter_blue",
        "feature/premium_basic",
        "feature/twitter_blue_verified",
        "feature/premium_plus",
    )

    // onPackageReady 在同一进程内可能被回调多次，用它记录已注册过的方法，避免重复 hook。
    private val registeredMethods =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<Method, Boolean>()))

    fun register(xposed: XposedInterface, classLoader: ClassLoader) {
        try {
            installFeatureSwitchGate(xposed, classLoader)
            installUserPreferencesGate(xposed, classLoader)
        } catch (t: Throwable) {
            xposed.log(Log.ERROR, TAG, "register failed", t)
        }
    }

    /** hook c0 的 boolean getter，对 forcedKeys 强制返回 true。 */
    private fun installFeatureSwitchGate(xposed: XposedInterface, classLoader: ClassLoader) {
        val configClass = classLoader.loadClass(CONFIG_CLASS)

        // 定位所有 (String, boolean) -> boolean 的 feature switch getter（a 与 m）。
        val getters = configClass.declaredMethods.filter { method ->
            method.returnType == Boolean::class.javaPrimitiveType &&
                method.parameterCount == 2 &&
                method.parameterTypes[0] == String::class.java &&
                method.parameterTypes[1] == Boolean::class.javaPrimitiveType
        }
        if (getters.isEmpty()) {
            throw NoSuchMethodException("boolean getter on $CONFIG_CLASS")
        }

        for (getter in getters) {
            if (!registeredMethods.add(getter)) {
                xposed.log(Log.INFO, TAG, "Already registered on ${configClass.simpleName}.${getter.name}, skip")
                continue
            }
            xposed.hook(getter).intercept { chain ->
                val result = chain.proceed()
                val key = chain.getArg(0) as? String
                if (key != null && key in forcedKeys) {
                    xposed.log(Log.DEBUG, TAG, "Forced premium gate: $key = true")
                    return@intercept true
                }
                result
            }
            xposed.log(Log.INFO, TAG, "Registered on ${configClass.simpleName}.${getter.name}")
        }
    }

    /** hook f 的 Companion.a.g(String[], k)，对 premiumFeatures 返回 true，让 d()=true。 */
    private fun installUserPreferencesGate(xposed: XposedInterface, classLoader: ClassLoader) {
        val featuresClass = classLoader.loadClass(FEATURES_CLASS)
        val prefsClass = classLoader.loadClass(PREFS_CLASS)
        val stringArrayType = Array<String>::class.java

        // Companion 是 f 的内部类（混淆名 a），用 declaredClasses 定位，不硬编码内部类名。
        val companionClass = featuresClass.declaredClasses.firstOrNull { cls ->
            cls.declaredMethods.any { method ->
                method.returnType == Boolean::class.javaPrimitiveType &&
                    method.parameterCount == 2 &&
                    method.parameterTypes[0] == stringArrayType &&
                    method.parameterTypes[1] == prefsClass
            }
        } ?: throw ClassNotFoundException("companion of $FEATURES_CLASS")

        val gMethod = companionClass.declaredMethods.firstOrNull { method ->
            method.returnType == Boolean::class.javaPrimitiveType &&
                method.parameterCount == 2 &&
                method.parameterTypes[0] == stringArrayType &&
                method.parameterTypes[1] == prefsClass
        } ?: throw NoSuchMethodException("g on ${companionClass.name}")

        if (!registeredMethods.add(gMethod)) {
            xposed.log(Log.INFO, TAG, "Already registered on ${companionClass.simpleName}.${gMethod.name}, skip")
            return
        }

        xposed.hook(gMethod).intercept { chain ->
            val result = chain.proceed()
            val strArr = chain.getArg(0) as? Array<*>
            if (strArr != null && strArr.any { (it as? String) in premiumFeatures }) {
                xposed.log(Log.DEBUG, TAG, "Forced premium feature gate (userPreferences)")
                return@intercept true
            }
            result
        }
        xposed.log(Log.INFO, TAG, "Registered on ${companionClass.simpleName}.${gMethod.name}")
    }
}
