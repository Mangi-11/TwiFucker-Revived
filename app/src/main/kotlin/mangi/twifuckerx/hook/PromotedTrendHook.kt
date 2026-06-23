package mangi.twifuckerx.hook

import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.util.Collections
import java.util.WeakHashMap

/**
 * LoganSquare JsonTimelineTrend 解析层的推广趋势过滤 hook。
 *
 * Twitter 用 LoganSquare mapper JsonTimelineTrend$$JsonObjectMapper#parse 解析 URT 时间线里
 * 的趋势条目，产出 JsonTimelineTrend 模型。当模型的推广元数据字段非空时，该趋势是广告趋势；
 * 直接返回 null，使该条不产出时间线 item。
 *
 * 验证状态：注册与解析路径已验证——探索页下拉刷新时 parse 会被大量调用（实测 276 次），
 * 确认探索页趋势走 JsonTimelineTrend.parse。当前 promotedMetadata 样本数为 0，因此趋势
 * 移除分支（返回 null）尚未命中，等待有广告样本时验证。
 *
 * promotedMetadata 字段类型是 JsonPromotedTrendMetadata 的产物类型（其泛型超类 m<j6> 的
 * 第一个实参），从泛型实参读取可避免硬编码被混淆的 j6 类名。字段与解析方法都按签名定位，
 * 不依赖易变的名字。
 */
object PromotedTrendHook {
    private const val TAG = "TwiFuckerX/PromotedTrend"

    private const val JSON_TIMELINE_TREND =
        "com.twitter.model.json.timeline.urt.JsonTimelineTrend"
    private const val JSON_TIMELINE_TREND_MAPPER =
        "com.twitter.model.json.timeline.urt.JsonTimelineTrend\$\$JsonObjectMapper"
    private const val JSON_PROMOTED_TREND_METADATA =
        "com.twitter.model.json.timeline.urt.JsonPromotedTrendMetadata"

    // onPackageReady 在同一进程内可能被回调多次，用它记录已注册过的解析方法，避免对同一个
    // parse method 重复 hook。Method 的 equals/hashCode 基于声明类与方法签名，同一
    // classloader 下同一方法语义相等；WeakHashMap 让进程退出后可被回收。
    private val registeredMethods =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<Method, Boolean>()))

    fun register(xposed: XposedInterface, classLoader: ClassLoader) {
        try {
            install(xposed, classLoader)
        } catch (t: Throwable) {
            xposed.log(Log.ERROR, TAG, "register failed", t)
        }
    }

    private fun install(xposed: XposedInterface, classLoader: ClassLoader) {
        val trendClass = classLoader.loadClass(JSON_TIMELINE_TREND)
        val mapperClass = classLoader.loadClass(JSON_TIMELINE_TREND_MAPPER)

        // JsonPromotedTrendMetadata extends m<j6>，j6 即推广元数据的产物类型，也是
        // JsonTimelineTrend 上 promotedMetadata 字段的类型。从泛型超类实参读取，不硬编码 j6。
        val promotedMetadataClass = classLoader.loadClass(JSON_PROMOTED_TREND_METADATA)
            .genericSuperclass.let { it as ParameterizedType }
            .actualTypeArguments[0] as Class<*>

        val promotedField = fieldOfType(trendClass, promotedMetadataClass)

        val parseMethod = mapperClass.declaredMethods
            .firstOrNull { method ->
                method.returnType == trendClass && method.parameterCount == 1
            }
            ?: throw NoSuchMethodException("parse on $JSON_TIMELINE_TREND_MAPPER")

        if (!registeredMethods.add(parseMethod)) {
            xposed.log(Log.INFO, TAG, "Already registered on ${mapperClass.simpleName}.${parseMethod.name}, skip")
            return
        }

        xposed.hook(parseMethod).intercept { chain ->
            val trend = chain.proceed() ?: return@intercept null
            if (promotedField.get(trend) == null) return@intercept trend
            xposed.log(Log.DEBUG, TAG, "Removed promoted timeline trend")
            null
        }

        xposed.log(Log.INFO, TAG, "Registered on ${mapperClass.simpleName}.${parseMethod.name}")
    }

    private fun fieldOfType(clazz: Class<*>, type: Class<*>): Field =
        clazz.declaredFields.first { it.type == type }.apply { isAccessible = true }
}
