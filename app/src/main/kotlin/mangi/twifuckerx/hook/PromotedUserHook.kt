package mangi.twifuckerx.hook

import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/**
 * 移除 URT 时间线中的推广用户（who-to-follow 里的推广用户）。
 *
 * Twitter 用 LoganSquare mapper JsonTimelineUser$$JsonObjectMapper#parse 解析时间线用户条目，
 * 产出 JsonTimelineUser 模型。当模型的推广元数据字段（JsonPromotedContentUrt）非空时，该用户
 * 是推广用户；清空其 userResults 字段，使 r() 不产出有效用户条目，推广用户被丢弃。
 *
 * 选用清空字段而非直接 return null：让 r() 自然返回 null，调用方按既有空值路径处理，避免
 * 因突兀的 null 触发非预期崩溃（与 PromotedTweetHook 一致，参考 Hachidori jy case 2）。
 *
 * userResults 字段类型从 JsonUserResults 首字段读取（即 v1），promoted 字段按
 * JsonPromotedContentUrt 类型定位，解析方法按返回类型与参数个数定位，不硬编码易变名字。
 */
object PromotedUserHook {
    private const val TAG = "TwiFuckerX/PromotedUser"

    private const val JSON_TIMELINE_USER =
        "com.twitter.model.json.timeline.urt.JsonTimelineUser"
    private const val JSON_TIMELINE_USER_MAPPER =
        "com.twitter.model.json.timeline.urt.JsonTimelineUser\$\$JsonObjectMapper"
    private const val JSON_USER_RESULTS =
        "com.twitter.model.json.core.JsonUserResults"
    private const val JSON_PROMOTED_CONTENT_URT =
        "com.twitter.model.json.timeline.urt.JsonPromotedContentUrt"

    // onPackageReady 在同一进程内可能被回调多次，用它记录已注册过的解析方法，避免重复 hook。
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
        val userClass = classLoader.loadClass(JSON_TIMELINE_USER)
        val mapperClass = classLoader.loadClass(JSON_TIMELINE_USER_MAPPER)
        val promotedContentClass = classLoader.loadClass(JSON_PROMOTED_CONTENT_URT)

        // JsonUserResults 持有单个字段，其类型即 userResults 模型（v1）；读取它可避免硬编码
        // 被混淆的 v1 类型。
        val userResultsType = classLoader.loadClass(JSON_USER_RESULTS)
            .declaredFields.first().type

        val userResultsField = fieldOfType(userClass, userResultsType)
        val promotedField = fieldOfType(userClass, promotedContentClass)

        val parseMethod = mapperClass.declaredMethods
            .firstOrNull { method ->
                method.returnType == userClass && method.parameterCount == 1
            }
            ?: throw NoSuchMethodException("parse on $JSON_TIMELINE_USER_MAPPER")

        if (!registeredMethods.add(parseMethod)) {
            xposed.log(Log.INFO, TAG, "Already registered on ${mapperClass.simpleName}.${parseMethod.name}, skip")
            return
        }

        xposed.hook(parseMethod).intercept { chain ->
            val user = chain.proceed() ?: return@intercept null
            if (promotedField.get(user) == null) return@intercept user
            userResultsField.set(user, null)
            xposed.log(Log.DEBUG, TAG, "Removed promoted timeline user")
            user
        }

        xposed.log(Log.INFO, TAG, "Registered on ${mapperClass.simpleName}.${parseMethod.name}")
    }

    private fun fieldOfType(clazz: Class<*>, type: Class<*>): Field =
        clazz.declaredFields.first { it.type == type }.apply { isAccessible = true }
}
