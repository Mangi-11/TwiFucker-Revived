package mangi.twifuckerx.hook

import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/**
 * 移除时间线里的推广/广告 entry（Premium 升级提示、探索页大横幅、RTB 图片广告）。
 *
 * Twitter 用 LoganSquare mapper JsonTimelineEntry$$JsonObjectMapper#parse 解析 URT 时间线条目，
 * 产出 JsonTimelineEntry 模型（entryId + content）。当 entryId 命中推广/广告标识时，清空 content
 * 字段，使该条目不渲染任何内容，广告/提示被丢弃。
 *
 * 选用清空 content 而非 return null：让调用方按既有空 content 路径处理，避免突兀 null 崩溃
 * （参考 Hachidori ky/jy case 0）。
 *
 * entryId 按 String 字段定位，content 按 interface 类型字段定位，解析方法按返回类型与参数
 * 个数定位，不硬编码易变名字。
 */
object TimelinePromotionEntryHook {
    private const val TAG = "TwiFuckerX/TimelinePromoEntry"

    private const val JSON_TIMELINE_ENTRY =
        "com.twitter.model.json.timeline.urt.JsonTimelineEntry"
    private const val JSON_TIMELINE_ENTRY_MAPPER =
        "com.twitter.model.json.timeline.urt.JsonTimelineEntry\$\$JsonObjectMapper"

    // Premium 升级提示 entryId（精确匹配）。
    private val premiumUpsellIds = setOf(
        "messageprompt-ads-sharing-x-premium-upsell-candidate",
        "messageprompt-generic-non-premium-inline-prompt",
        "messageprompt-premium-announcement-inline-prompt",
        "messageprompt-premium-grok2-upsell-prompt",
        "messageprompt-premium-plus-upsell-prompt",
    )

    // 探索页大横幅 / RTB 图片广告 entryId 前缀。
    private val promotionPrefixes = setOf("superhero-", "rtb-image-ad-")

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
        val entryClass = classLoader.loadClass(JSON_TIMELINE_ENTRY)
        val mapperClass = classLoader.loadClass(JSON_TIMELINE_ENTRY_MAPPER)

        val entryIdField = entryClass.declaredFields.first { it.type == String::class.java }
        val contentField = entryClass.declaredFields.first { it.type.isInterface }
        entryIdField.isAccessible = true
        contentField.isAccessible = true

        val parseMethod = mapperClass.declaredMethods
            .firstOrNull { method ->
                method.returnType == entryClass && method.parameterCount == 1
            }
            ?: throw NoSuchMethodException("parse on $JSON_TIMELINE_ENTRY_MAPPER")

        if (!registeredMethods.add(parseMethod)) {
            xposed.log(Log.INFO, TAG, "Already registered on ${mapperClass.simpleName}.${parseMethod.name}, skip")
            return
        }

        xposed.hook(parseMethod).intercept { chain ->
            val entry = chain.proceed() ?: return@intercept null
            val entryId = entryIdField.get(entry) as? String ?: return@intercept entry
            val reason = matchPromotion(entryId) ?: return@intercept entry
            contentField.set(entry, null)
            xposed.log(Log.DEBUG, TAG, reason)
            entry
        }

        xposed.log(Log.INFO, TAG, "Registered on ${mapperClass.simpleName}.${parseMethod.name}")
    }

    private fun matchPromotion(entryId: String): String? = when {
        entryId in premiumUpsellIds -> "Removed premium upsell prompt"
        promotionPrefixes.any { entryId.startsWith(it) } ->
            if (entryId.startsWith("rtb-image-ad-")) "Removed RTB image ad"
            else "Removed explore promotion banner"
        else -> null
    }
}
