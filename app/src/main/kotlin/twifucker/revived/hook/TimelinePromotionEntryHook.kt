package twifucker.revived.hook

import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/**
 * 移除时间线里的推广/广告 entry（promoted tweet、Premium 升级提示、探索页大横幅、RTB 图片广告）。
 *
 * 两个 hook 点覆盖不同路径：
 *
 * 1. parse hook：hook JsonTimelineEntry$$JsonObjectMapper#parse，JSON 反序列化后若 entryId 命中
 *    推广规则，清空 content 字段，使该条目不渲染内容。
 *
 * 2. r() hook：hook JsonTimelineEntry#r()，这是 JsonTimelineEntry 转成运行时 item 的转换层，
 *    覆盖本地缓存里的 entry 重新转换的场景。若 entryId 命中规则，直接返回 null，使条目被丢弃。
 *    parse hook 只覆盖 JSON 解析路径，r() hook 更靠近渲染前，更稳。
 *
 * entryId 按 String 字段定位，content 按 interface 字段定位，方法按签名定位，不硬编码易变名字。
 * 匹配规则统一，parse 与 r() 共用。
 */
object TimelinePromotionEntryHook {
    private const val TAG = "TwiFuckerRevived/TimelinePromoEntry"

    private const val JSON_TIMELINE_ENTRY =
        "com.twitter.model.json.timeline.urt.JsonTimelineEntry"
    private const val JSON_TIMELINE_ENTRY_MAPPER =
        "com.twitter.model.json.timeline.urt.JsonTimelineEntry\$\$JsonObjectMapper"

    // 推广 tweet entryId 前缀（普通 promoted timeline tweet，如截图里的 NordVPN）。
    private val promotedTweetPrefixes = setOf("promotedTweet-", "promoted-tweet-")

    // 探索页大横幅 / RTB 图片广告 entryId 前缀。
    private val promotionPrefixes = setOf("superhero-", "rtb-image-ad-")

    // Premium 升级提示 entryId（精确匹配）。
    private val premiumUpsellIds = setOf(
        "messageprompt-ads-sharing-x-premium-upsell-candidate",
        "messageprompt-generic-non-premium-inline-prompt",
        "messageprompt-premium-announcement-inline-prompt",
        "messageprompt-premium-grok2-upsell-prompt",
        "messageprompt-premium-plus-upsell-prompt",
    )

    private val registeredMethods =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<Method, Boolean>()))

    fun register(xposed: XposedInterface, classLoader: ClassLoader) {
        try {
            installParseHook(xposed, classLoader)
        } catch (t: Throwable) {
            xposed.log(Log.ERROR, TAG, "parse hook failed: ${t.javaClass.name}: ${t.message}", t)
        }
        try {
            installConversionHook(xposed, classLoader)
        } catch (t: Throwable) {
            xposed.log(Log.ERROR, TAG, "conversion hook failed: ${t.javaClass.name}: ${t.message}", t)
        }
    }

    /** hook parse，entryId 命中规则时清空 content 字段。 */
    private fun installParseHook(xposed: XposedInterface, classLoader: ClassLoader) {
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
        xposed.log(Log.INFO, TAG, "Registered parse on ${mapperClass.simpleName}.${parseMethod.name}")
    }

    /** hook r()，entryId 命中规则时返回 null，覆盖缓存 entry 重新转换的路径。 */
    private fun installConversionHook(xposed: XposedInterface, classLoader: ClassLoader) {
        val entryClass = classLoader.loadClass(JSON_TIMELINE_ENTRY)

        val entryIdField: Field =
            entryClass.declaredFields.first { it.type == String::class.java }.apply { isAccessible = true }

        // r() 是 LoganSquare m<T> 的约定转换方法（JsonTimelineEntry 覆盖父类 m#r()），所有
        // LoganSquare 模型都用 r 作为 model→runtime item 的转换入口，稳定性类似约定方法名。
        // 运行时按签名定位受 R8 影响（returnType 的 Class 对象身份不一致），改用约定方法名定位。
        val convertMethod = entryClass.getMethod("r")

        if (!registeredMethods.add(convertMethod)) {
            xposed.log(Log.INFO, TAG, "Already registered on ${entryClass.simpleName}.${convertMethod.name}, skip")
            return
        }

        xposed.hook(convertMethod).intercept { chain ->
            val entry = chain.getThisObject() ?: return@intercept chain.proceed()
            val entryId = entryIdField.get(entry) as? String
            val reason = entryId?.let { matchPromotion(it) }
            if (reason != null) {
                xposed.log(Log.DEBUG, TAG, reason)
                return@intercept null
            }
            chain.proceed()
        }
        xposed.log(Log.INFO, TAG, "Registered conversion on ${entryClass.simpleName}.${convertMethod.name}")
    }

    /** 统一匹配规则，返回命中日志文案（null 表示不命中）。 */
    private fun matchPromotion(entryId: String): String? = when {
        promotedTweetPrefixes.any { entryId.startsWith(it) } -> "Removed promoted timeline entry"
        entryId in premiumUpsellIds -> "Removed premium upsell prompt"
        entryId.startsWith("rtb-image-ad-") -> "Removed RTB image ad"
        entryId.startsWith("superhero-") -> "Removed explore promotion banner"
        else -> null
    }
}
