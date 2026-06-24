package twifucker.revived.hook

import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/**
 * 隐藏时间线里的推荐关注模块（Who to follow / suggest_who_to_follow）。
 *
 * 这不是广告过滤：推荐关注是正常推荐内容，不携带 promoted 元数据。按 module 的 component
 * 标识（suggest_who_to_follow）识别并丢弃整个模块。
 *
 * 两个 hook 点覆盖不同路径：
 *
 * 1. parse hook：hook JsonTimelineModule$$JsonObjectMapper#parse，解析后若 component 命中则
 *    返回 null，使 module 不被设置到 entry content。
 *
 * 2. r() 转换层：hook JsonTimelineEntry#r()，若 entry 的 content 是 JsonTimelineModule 且
 *    component 命中则返回 null，覆盖本地缓存重新转换的路径。与 TimelinePromotionEntryHook 的
 *    r() hook 共存不冲突（who-to-follow entryId 不在 promotion 规则里，后者会 proceed 放行）。
 *
 * component 字段是 JsonClientEventInfo 的第一个 String 字段（反编译确认对应 JSON key
 * `component`）。方法按签名定位，r() 按 LoganSquare 约定名定位。
 */
object WhoToFollowModuleHook {
    private const val TAG = "TwiFuckerRevived/WhoToFollow"
    private const val COMPONENT_WHO_TO_FOLLOW = "suggest_who_to_follow"

    private const val JSON_TIMELINE_MODULE =
        "com.twitter.model.json.timeline.urt.JsonTimelineModule"
    private const val JSON_TIMELINE_MODULE_MAPPER =
        "com.twitter.model.json.timeline.urt.JsonTimelineModule\$\$JsonObjectMapper"
    private const val JSON_CLIENT_EVENT_INFO =
        "com.twitter.model.json.timeline.urt.JsonClientEventInfo"
    private const val JSON_TIMELINE_ENTRY =
        "com.twitter.model.json.timeline.urt.JsonTimelineEntry"
    private const val JSON_TIMELINE_ENTRY_MAPPER =
        "com.twitter.model.json.timeline.urt.JsonTimelineEntry\$\$JsonObjectMapper"

    private val registeredMethods =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<Method, Boolean>()))

    fun register(xposed: XposedInterface, classLoader: ClassLoader) {
        try {
            installModuleParseHook(xposed, classLoader)
            installEntryConversionHook(xposed, classLoader)
        } catch (t: Throwable) {
            xposed.log(Log.ERROR, TAG, "register failed: ${t.javaClass.name}: ${t.message}", t)
        }
    }

    /** hook module parse，component 命中时返回 null。 */
    private fun installModuleParseHook(xposed: XposedInterface, classLoader: ClassLoader) {
        val moduleClass = classLoader.loadClass(JSON_TIMELINE_MODULE)
        val mapperClass = classLoader.loadClass(JSON_TIMELINE_MODULE_MAPPER)
        val eventInfoClass = classLoader.loadClass(JSON_CLIENT_EVENT_INFO)
        val (eventInfoField, componentField) = resolveComponentFields(moduleClass, eventInfoClass)

        val parseMethod = mapperClass.declaredMethods
            .firstOrNull { method ->
                method.returnType == moduleClass && method.parameterCount == 1
            }
            ?: throw NoSuchMethodException("parse on $JSON_TIMELINE_MODULE_MAPPER")

        if (!registeredMethods.add(parseMethod)) {
            xposed.log(Log.INFO, TAG, "Already registered on ${mapperClass.simpleName}.${parseMethod.name}, skip")
            return
        }

        xposed.hook(parseMethod).intercept { chain ->
            val module = chain.proceed() ?: return@intercept null
            if (readComponent(module, eventInfoField, componentField) == COMPONENT_WHO_TO_FOLLOW) {
                xposed.log(Log.DEBUG, TAG, "Removed who-to-follow module")
                return@intercept null
            }
            module
        }
        xposed.log(Log.INFO, TAG, "Registered module parse on ${mapperClass.simpleName}.${parseMethod.name}")
    }

    /** hook r() 转换层，entry content 是 who-to-follow module 时返回 null。 */
    private fun installEntryConversionHook(xposed: XposedInterface, classLoader: ClassLoader) {
        val entryClass = classLoader.loadClass(JSON_TIMELINE_ENTRY)
        val moduleClass = classLoader.loadClass(JSON_TIMELINE_MODULE)
        val eventInfoClass = classLoader.loadClass(JSON_CLIENT_EVENT_INFO)

        // content 字段：JsonTimelineEntry 上 interface 类型的字段（module/item/operation union）。
        val contentField: Field =
            entryClass.declaredFields.first { it.type.isInterface }.apply { isAccessible = true }
        val (eventInfoField, componentField) = resolveComponentFields(moduleClass, eventInfoClass)

        // r() 是 LoganSquare m<T> 约定转换方法，按约定名定位（与 TimelinePromotionEntryHook 一致）。
        val convertMethod = entryClass.getMethod("r")

        if (!registeredMethods.add(convertMethod)) {
            xposed.log(Log.INFO, TAG, "Already registered on ${entryClass.simpleName}.${convertMethod.name}, skip")
            return
        }

        xposed.hook(convertMethod).intercept { chain ->
            val entry = chain.getThisObject() ?: return@intercept chain.proceed()
            val content = contentField.get(entry)
            if (moduleClass.isInstance(content) &&
                readComponent(content, eventInfoField, componentField) == COMPONENT_WHO_TO_FOLLOW
            ) {
                xposed.log(Log.DEBUG, TAG, "Removed who-to-follow module")
                return@intercept null
            }
            chain.proceed()
        }
        xposed.log(Log.INFO, TAG, "Registered conversion on ${entryClass.simpleName}.${convertMethod.name}")
    }

    /**
     * 定位 module 的 component 读取所需字段：
     * - JsonTimelineModule 上类型为 JsonClientEventInfo 的字段
     * - JsonClientEventInfo 的第一个 String 字段（反编译确认对应 JSON key `component`）
     */
    private fun resolveComponentFields(
        moduleClass: Class<*>,
        eventInfoClass: Class<*>,
    ): Pair<Field, Field> {
        val eventInfoField = moduleClass.declaredFields
            .first { it.type == eventInfoClass }.apply { isAccessible = true }
        val componentField = eventInfoClass.declaredFields
            .first { it.type == String::class.java }.apply { isAccessible = true }
        return eventInfoField to componentField
    }

    /** 从 module 实例读取 component：先取 eventInfo 子对象，再取其 component 字符串。 */
    private fun readComponent(
        module: Any,
        eventInfoField: Field,
        componentField: Field,
    ): String? {
        val eventInfo = eventInfoField.get(module) ?: return null
        return componentField.get(eventInfo) as? String
    }
}
