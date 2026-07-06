package twifucker.revived.hook

import io.github.libxposed.api.XposedInterface
import twifucker.revived.core.HookContext
import twifucker.revived.core.HookInstallResult
import twifucker.revived.core.HookInstallScope
import twifucker.revived.core.HookLocator
import twifucker.revived.core.TargetHook
import twifucker.revived.core.logD
import twifucker.revived.core.logI
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/**
 * 过滤新版 Kotlin serialization URT 时间线模型。
 *
 * 12.5.0 起主时间线更多走 com.x.models.timelines.items.UrtTimelineItem，而不是旧的
 * LoganSquare JsonTimeline* 模型。这里在 DefaultUrtTimelineComponent 的 timelineUpdates
 * flow map 层过滤 List<UrtTimelineItem>，并处理 UrtTimelineModule 内部延迟展开的 item。
 */
object UrtTimelineItemFilterHook : TargetHook {
    private const val TAG = "TwiFuckerRevived/UrtTimeline"

    private const val FLOW_FILTER = "com.x.urt.v\$a"
    private const val URT_TIMELINE_ITEM = "com.x.models.timelines.items.UrtTimelineItem"
    private const val URT_TIMELINE_POST = "com.x.models.timelines.items.UrtTimelinePost"
    private const val URT_TIMELINE_USER = "com.x.models.timelines.items.UrtTimelineUser"
    private const val URT_TIMELINE_TREND = "com.x.models.timelines.items.UrtTimelineTrend"
    private const val URT_TIMELINE_MODULE = "com.x.models.timelines.items.UrtTimelineModule"
    private const val URT_TIMELINE_MODULE_ITEM = "com.x.models.timelines.items.UrtTimelineModuleItem"
    private const val TIMELINE_TREND = "com.x.models.TimelineTrend"
    private const val CLIENT_EVENT_INFO = "com.x.models.ClientEventInfo"
    private const val MODULE_HEADER = "com.x.models.timelinemodule.ModuleHeader"
    private const val MODULE_FOOTER = "com.x.models.timelinemodule.ModuleFooter"
    private const val MODULE_DISPLAY_TYPE = "com.x.models.timelinemodule.ModuleDisplayType"

    private const val COMPONENT_WHO_TO_FOLLOW = "suggest_who_to_follow"

    private val promotedTweetPrefixes = setOf("promotedTweet-", "promoted-tweet-")
    private val promotionPrefixes = setOf("superhero-", "rtb-image-ad-")
    private val premiumUpsellIds = setOf(
        "messageprompt-ads-sharing-x-premium-upsell-candidate",
        "messageprompt-generic-non-premium-inline-prompt",
        "messageprompt-premium-announcement-inline-prompt",
        "messageprompt-premium-grok2-upsell-prompt",
        "messageprompt-premium-plus-upsell-prompt",
    )

    private val registeredMethods =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<Method, Boolean>()))

    override val name = "UrtTimelineItemFilter"
    override val expectedHooks = 2

    fun register(xposed: XposedInterface, classLoader: ClassLoader) {
        install(HookContext(xposed, classLoader))
    }

    override fun install(context: HookContext): HookInstallResult {
        val scope = HookInstallScope(name, expectedHooks)
        val shape = try {
            ModelShape.resolve(context.classLoader)
        } catch (t: Throwable) {
            scope.fail("resolve model shape", t)
            return scope.result()
        }

        scope.install("timeline flow") {
            installTimelineFlowHook(context.xposed, context.classLoader, shape)
        }
        scope.install("module items") {
            installModuleItemsHook(context.xposed, shape)
        }
        return scope.result()
    }

    private fun installTimelineFlowHook(
        xposed: XposedInterface,
        classLoader: ClassLoader,
        shape: ModelShape,
    ) {
        val locator = HookLocator(classLoader)
        val flowFilterClass = locator.requireClass(FLOW_FILTER)
        val emitMethod = locator.requireDeclaredMethod(flowFilterClass, "emit(Object, Continuation)") { method ->
            method.name == "emit" && method.parameterCount == 2
        }

        if (!registeredMethods.add(emitMethod)) {
            xposed.logI(TAG, "Already registered on ${flowFilterClass.simpleName}.${emitMethod.name}, skip")
            return
        }

        xposed.hook(emitMethod).intercept { chain ->
            val items = chain.getArg(0) as? List<*> ?: return@intercept chain.proceed()
            val filtered = filterTimelineItems(items, shape)
            if (!filtered.changed) return@intercept chain.proceed()

            xposed.logD(
                TAG,
                "Filtered new URT timeline items: removed=${filtered.removed}, kept=${filtered.items.size}",
            )
            chain.proceed(arrayOf(filtered.items, chain.getArg(1)))
        }
        xposed.logI(TAG, "Registered timeline flow on ${flowFilterClass.simpleName}.${emitMethod.name}")
    }

    private fun installModuleItemsHook(xposed: XposedInterface, shape: ModelShape) {
        val getItemsMethod = shape.moduleClass.getMethod("getItems")

        if (!registeredMethods.add(getItemsMethod)) {
            xposed.logI(TAG, "Already registered on ${shape.moduleClass.simpleName}.${getItemsMethod.name}, skip")
            return
        }

        xposed.hook(getItemsMethod).intercept { chain ->
            val result = chain.proceed()
            val items = result as? List<*> ?: return@intercept result
            val filtered = filterModuleItems(items, shape)
            if (!filtered.changed) return@intercept items

            xposed.logD(
                TAG,
                "Filtered new URT module items: removed=${filtered.removed}, kept=${filtered.items.size}",
            )
            filtered.items
        }
        xposed.logI(TAG, "Registered module items on ${shape.moduleClass.simpleName}.${getItemsMethod.name}")
    }

    private fun filterTimelineItems(items: List<*>, shape: ModelShape): ListFilterResult {
        var changed = false
        var removed = 0
        val out = ArrayList<Any?>(items.size)

        for (item in items) {
            val filtered = filterTimelineItem(item, shape)
            if (filtered.remove) {
                changed = true
                removed += filtered.removed.coerceAtLeast(1)
                continue
            }
            out.add(filtered.value)
            changed = changed || filtered.changed
            removed += filtered.removed
        }

        return if (changed) ListFilterResult(out, changed = true, removed = removed) else ListFilterResult(items, false, 0)
    }

    private fun filterModuleItems(items: List<*>, shape: ModelShape): ListFilterResult {
        var changed = false
        var removed = 0
        val out = ArrayList<Any?>(items.size)

        for (moduleItem in items) {
            if (!shape.moduleItemClass.isInstance(moduleItem)) {
                out.add(moduleItem)
                continue
            }

            val nestedItem = shape.moduleItemGetItem.invoke(moduleItem)
            val filtered = filterTimelineItem(nestedItem, shape)
            if (filtered.remove) {
                changed = true
                removed += filtered.removed.coerceAtLeast(1)
                continue
            }

            if (filtered.changed && filtered.value != null) {
                val isDispensable = shape.moduleItemIsDispensable.invoke(moduleItem) as Boolean
                out.add(shape.moduleItemCopy.invoke(moduleItem, filtered.value, isDispensable))
                changed = true
            } else {
                out.add(moduleItem)
            }
            removed += filtered.removed
        }

        return if (changed) ListFilterResult(out, changed = true, removed = removed) else ListFilterResult(items, false, 0)
    }

    private fun filterTimelineItem(item: Any?, shape: ModelShape): ItemFilterResult {
        if (item == null || !shape.timelineItemClass.isInstance(item)) {
            return ItemFilterResult.keep(item)
        }

        val entryId = readEntryId(item, shape)
        if (entryId != null && matchPromotionEntry(entryId) != null) {
            return ItemFilterResult.remove()
        }

        if (shape.postClass.isInstance(item) && shape.postGetPromotedMetadata.invoke(item) != null) {
            return ItemFilterResult.remove()
        }
        if (shape.userClass.isInstance(item) && shape.userGetPromotedMetadata.invoke(item) != null) {
            return ItemFilterResult.remove()
        }
        if (shape.trendClass.isInstance(item)) {
            val trend = shape.trendGetTimelineTrend.invoke(item)
            if (trend != null && shape.timelineTrendGetPromotedMetadata.invoke(trend) != null) {
                return ItemFilterResult.remove()
            }
        }

        if (!shape.moduleClass.isInstance(item)) {
            return ItemFilterResult.keep(item)
        }

        if (readComponent(item, shape) == COMPONENT_WHO_TO_FOLLOW) {
            return ItemFilterResult.remove()
        }

        val innerContent = shape.moduleGetInnerContent.invoke(item) as? List<*> ?: return ItemFilterResult.keep(item)
        val filtered = filterModuleItems(innerContent, shape)
        if (!filtered.changed) return ItemFilterResult.keep(item)
        if (filtered.items.isEmpty()) return ItemFilterResult.remove(filtered.removed.coerceAtLeast(1))

        val replacement = shape.moduleCopy.invoke(
            item,
            filtered.items,
            shape.moduleGetModuleHeader.invoke(item),
            shape.moduleGetModuleFooter.invoke(item),
            shape.moduleGetDisplayType.invoke(item),
            shape.moduleGetSortIndex.invoke(item),
            shape.moduleGetEntryId.invoke(item),
            shape.moduleGetClientEventInfo.invoke(item),
        )
        return ItemFilterResult(value = replacement, remove = false, changed = true, removed = filtered.removed)
    }

    private fun readEntryId(item: Any, shape: ModelShape): String? =
        try {
            shape.timelineItemGetEntryId.invoke(item) as? String
        } catch (_: Throwable) {
            null
        }

    private fun readComponent(item: Any, shape: ModelShape): String? {
        val clientEventInfo = shape.timelineItemGetClientEventInfo.invoke(item) ?: return null
        return shape.clientEventInfoGetComponent.invoke(clientEventInfo) as? String
    }

    private fun matchPromotionEntry(entryId: String): String? = when {
        promotedTweetPrefixes.any { entryId.startsWith(it) } -> "promoted timeline entry"
        entryId in premiumUpsellIds -> "premium upsell prompt"
        promotionPrefixes.any { entryId.startsWith(it) } -> "promotion entry"
        else -> null
    }

    private data class ListFilterResult(
        val items: List<*>,
        val changed: Boolean,
        val removed: Int,
    )

    private data class ItemFilterResult(
        val value: Any?,
        val remove: Boolean,
        val changed: Boolean,
        val removed: Int,
    ) {
        companion object {
            fun keep(value: Any?) = ItemFilterResult(value, remove = false, changed = false, removed = 0)
            fun remove(removed: Int = 1) = ItemFilterResult(null, remove = true, changed = true, removed = removed)
        }
    }

    private data class ModelShape(
        val timelineItemClass: Class<*>,
        val postClass: Class<*>,
        val userClass: Class<*>,
        val trendClass: Class<*>,
        val moduleClass: Class<*>,
        val moduleItemClass: Class<*>,
        val timelineItemGetEntryId: Method,
        val timelineItemGetClientEventInfo: Method,
        val postGetPromotedMetadata: Method,
        val userGetPromotedMetadata: Method,
        val trendGetTimelineTrend: Method,
        val timelineTrendGetPromotedMetadata: Method,
        val moduleGetInnerContent: Method,
        val moduleGetModuleHeader: Method,
        val moduleGetModuleFooter: Method,
        val moduleGetDisplayType: Method,
        val moduleGetSortIndex: Method,
        val moduleGetEntryId: Method,
        val moduleGetClientEventInfo: Method,
        val moduleCopy: Method,
        val moduleItemGetItem: Method,
        val moduleItemIsDispensable: Method,
        val moduleItemCopy: Method,
        val clientEventInfoGetComponent: Method,
    ) {
        companion object {
            fun resolve(classLoader: ClassLoader): ModelShape {
                val locator = HookLocator(classLoader)
                val timelineItemClass = locator.requireClass(URT_TIMELINE_ITEM)
                val postClass = locator.requireClass(URT_TIMELINE_POST)
                val userClass = locator.requireClass(URT_TIMELINE_USER)
                val trendClass = locator.requireClass(URT_TIMELINE_TREND)
                val moduleClass = locator.requireClass(URT_TIMELINE_MODULE)
                val moduleItemClass = locator.requireClass(URT_TIMELINE_MODULE_ITEM)
                val timelineTrendClass = locator.requireClass(TIMELINE_TREND)
                val clientEventInfoClass = locator.requireClass(CLIENT_EVENT_INFO)
                val moduleHeaderClass = locator.requireClass(MODULE_HEADER)
                val moduleFooterClass = locator.requireClass(MODULE_FOOTER)
                val moduleDisplayTypeClass = locator.requireClass(MODULE_DISPLAY_TYPE)

                return ModelShape(
                    timelineItemClass = timelineItemClass,
                    postClass = postClass,
                    userClass = userClass,
                    trendClass = trendClass,
                    moduleClass = moduleClass,
                    moduleItemClass = moduleItemClass,
                    timelineItemGetEntryId = timelineItemClass.getMethod("getEntryId"),
                    timelineItemGetClientEventInfo = timelineItemClass.getMethod("getClientEventInfo"),
                    postGetPromotedMetadata = postClass.getMethod("getPromotedMetadata"),
                    userGetPromotedMetadata = userClass.getMethod("getPromotedMetadata"),
                    trendGetTimelineTrend = trendClass.getMethod("getTimelineTrend"),
                    timelineTrendGetPromotedMetadata = timelineTrendClass.getMethod("getPromotedMetadata"),
                    moduleGetInnerContent = moduleClass.getMethod("getInnerContent"),
                    moduleGetModuleHeader = moduleClass.getMethod("getModuleHeader"),
                    moduleGetModuleFooter = moduleClass.getMethod("getModuleFooter"),
                    moduleGetDisplayType = moduleClass.getMethod("getDisplayType"),
                    moduleGetSortIndex = moduleClass.getMethod("getSortIndex"),
                    moduleGetEntryId = moduleClass.getMethod("getEntryId"),
                    moduleGetClientEventInfo = moduleClass.getMethod("getClientEventInfo"),
                    moduleCopy = moduleClass.getMethod(
                        "copy",
                        List::class.java,
                        moduleHeaderClass,
                        moduleFooterClass,
                        moduleDisplayTypeClass,
                        java.lang.Long.TYPE,
                        String::class.java,
                        clientEventInfoClass,
                    ),
                    moduleItemGetItem = moduleItemClass.getMethod("getItem"),
                    moduleItemIsDispensable = moduleItemClass.getMethod("isDispensable"),
                    moduleItemCopy = moduleItemClass.getMethod(
                        "copy",
                        timelineItemClass,
                        java.lang.Boolean.TYPE,
                    ),
                    clientEventInfoGetComponent = clientEventInfoClass.getMethod("getComponent"),
                )
            }
        }
    }
}
