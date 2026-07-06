package twifucker.revived.hook

import io.github.libxposed.api.XposedInterface
import twifucker.revived.core.HookContext
import twifucker.revived.core.HookInstallResult
import twifucker.revived.core.HookInstallScope
import twifucker.revived.core.HookLocator
import twifucker.revived.core.TargetHook
import twifucker.revived.core.logD
import twifucker.revived.core.logI
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/**
 * 过滤旧版 collection adapter 链路中的不需要条目。
 *
 * TweetDetailActivity 和部分 legacy 列表仍使用 h0 -> ItemCollection 链路。
 * 这里在 h0 更新集合前移除 promoted tweet row，以及详情页的“发现更多”推荐 section。
 */
object LegacyTimelineItemFilterHook : TargetHook {
    private const val TAG = "TwiFuckerRevived/LegacyTimeline"

    private const val LEGACY_LIST_HOST = "com.twitter.app.legacy.list.h0"
    private const val ITEM_COLLECTION = "com.twitter.model.common.collection.e"
    private const val LIST_COLLECTION = "com.twitter.model.common.collection.g"
    private const val TIMELINE_ITEM = "com.twitter.model.timeline.q1"
    private const val MODULE_HEADER_ITEM = "com.twitter.model.timeline.l0"
    private const val TIMELINE_METADATA = "com.twitter.model.timeline.urt.c0"
    private const val MODULE_HEADER = "com.twitter.model.timeline.urt.b0"
    private const val TIMELINE_TWEET_ITEM = "com.twitter.model.timeline.a0"
    private const val TIMELINE_TWEET = "com.twitter.model.core.e"
    private const val SOCIAL_CONTEXT = "com.twitter.model.core.q0"
    private const val PROMOTED_CONTENT = "com.twitter.model.core.entity.ad.h"

    private val discoverMoreTitles = setOf("发现更多", "Discover more")
    private val discoverMoreSocialProofTexts = setOf("源自于整个 X", "From across X")

    private val registeredMethods =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<Method, Boolean>()))

    override val name = "LegacyTimelineItemFilter"
    override val expectedHooks = 1

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

        scope.install("legacy adapter collection") {
            installAdapterCollectionHook(context.xposed, context.classLoader, shape)
        }
        return scope.result()
    }

    private fun installAdapterCollectionHook(
        xposed: XposedInterface,
        classLoader: ClassLoader,
        shape: ModelShape,
    ) {
        val locator = HookLocator(classLoader)
        val listHostClass = locator.requireClass(LEGACY_LIST_HOST)
        val collectionClass = locator.requireClass(ITEM_COLLECTION)
        val setItemsMethod = locator.requireDeclaredMethod(listHostClass, "j2(ItemCollection)") { method ->
            method.name == "j2" &&
                method.parameterCount == 1 &&
                method.parameterTypes[0] == collectionClass
        }

        if (!registeredMethods.add(setItemsMethod)) {
            xposed.logI(TAG, "Already registered on ${listHostClass.simpleName}.${setItemsMethod.name}, skip")
            return
        }

        xposed.hook(setItemsMethod).intercept { chain ->
            val collection = chain.getArg(0) ?: return@intercept chain.proceed()
            val filtered = filterCollection(collection, shape)
            if (!filtered.changed) return@intercept chain.proceed()

            val replacement = shape.listCollectionConstructor.newInstance(filtered.items)
            xposed.logD(
                TAG,
                "Filtered legacy timeline items: removed=${filtered.removed}, " +
                    "discoverMore=${filtered.removedDiscoverMore}, kept=${filtered.items.size}",
            )
            chain.proceed(arrayOf(replacement))
        }
        xposed.logI(TAG, "Registered legacy adapter collection on ${listHostClass.simpleName}.${setItemsMethod.name}")
    }

    private fun filterCollection(collection: Any, shape: ModelShape): CollectionFilterResult {
        val size = (shape.collectionGetSize.invoke(collection) as? Int) ?: return CollectionFilterResult.unchanged()
        if (size <= 0) return CollectionFilterResult.unchanged()

        var changed = false
        var removed = 0
        var removedDiscoverMore = 0
        var discoverMoreGroupEntryId: String? = null
        val out = ArrayList<Any?>(size)

        for (index in 0 until size) {
            val item = shape.collectionGetItem.invoke(collection, index)
            if (item != null && shape.timelineItemClass.isInstance(item)) {
                val groupEntryId = shape.timelineItemGetGroupEntryId.invoke(item) as? String
                if (discoverMoreGroupEntryId != null && groupEntryId == discoverMoreGroupEntryId) {
                    changed = true
                    removed += 1
                    removedDiscoverMore += 1
                    continue
                }
                if (discoverMoreGroupEntryId != null && groupEntryId != discoverMoreGroupEntryId) {
                    discoverMoreGroupEntryId = null
                }

                if (isDiscoverMoreHeader(item, shape)) {
                    discoverMoreGroupEntryId = groupEntryId?.takeUnless { it.isBlank() || it == "unspecified" }
                    changed = true
                    removed += 1
                    removedDiscoverMore += 1
                    continue
                }
            }

            if (item != null && isPromotedTimelineItem(item, shape)) {
                changed = true
                removed += 1
                continue
            }
            out.add(item)
        }

        return if (changed) {
            CollectionFilterResult(
                items = out,
                changed = true,
                removed = removed,
                removedDiscoverMore = removedDiscoverMore,
            )
        } else {
            CollectionFilterResult.unchanged()
        }
    }

    private fun isPromotedTimelineItem(item: Any, shape: ModelShape): Boolean {
        if (!shape.timelineTweetItemClass.isInstance(item)) return false
        val tweet = shape.timelineTweetGetTweet.invoke(item) ?: return false
        if (!shape.timelineTweetClass.isInstance(tweet)) return false
        return shape.promotedContentField.get(tweet) != null
    }

    private fun isDiscoverMoreHeader(item: Any, shape: ModelShape): Boolean {
        if (!shape.moduleHeaderItemClass.isInstance(item)) return false
        val timelineMetadata = shape.timelineMetadataField.get(item) ?: return false
        val moduleHeader = shape.moduleHeaderField.get(timelineMetadata) ?: return false
        val title = shape.moduleHeaderTitleField.get(moduleHeader) as? String ?: return false
        if (title !in discoverMoreTitles) return false

        val socialContext = shape.moduleHeaderSocialContextField.get(moduleHeader) ?: return false
        val socialProof = shape.socialProofTextField.get(socialContext) as? String ?: return false
        return socialProof in discoverMoreSocialProofTexts
    }

    private data class CollectionFilterResult(
        val items: List<Any?>,
        val changed: Boolean,
        val removed: Int,
        val removedDiscoverMore: Int,
    ) {
        companion object {
            private val UNCHANGED = CollectionFilterResult(
                emptyList(),
                changed = false,
                removed = 0,
                removedDiscoverMore = 0,
            )
            fun unchanged() = UNCHANGED
        }
    }

    private data class ModelShape(
        val collectionGetSize: Method,
        val collectionGetItem: Method,
        val listCollectionConstructor: Constructor<*>,
        val timelineItemClass: Class<*>,
        val timelineItemGetGroupEntryId: Method,
        val moduleHeaderItemClass: Class<*>,
        val timelineMetadataField: Field,
        val moduleHeaderField: Field,
        val moduleHeaderTitleField: Field,
        val moduleHeaderSocialContextField: Field,
        val socialProofTextField: Field,
        val timelineTweetItemClass: Class<*>,
        val timelineTweetGetTweet: Method,
        val timelineTweetClass: Class<*>,
        val promotedContentField: Field,
    ) {
        companion object {
            fun resolve(classLoader: ClassLoader): ModelShape {
                val locator = HookLocator(classLoader)
                val collectionClass = locator.requireClass(ITEM_COLLECTION)
                val listCollectionClass = locator.requireClass(LIST_COLLECTION)
                val timelineItemClass = locator.requireClass(TIMELINE_ITEM)
                val moduleHeaderItemClass = locator.requireClass(MODULE_HEADER_ITEM)
                val timelineMetadataClass = locator.requireClass(TIMELINE_METADATA)
                val moduleHeaderClass = locator.requireClass(MODULE_HEADER)
                val timelineTweetItemClass = locator.requireClass(TIMELINE_TWEET_ITEM)
                val timelineTweetClass = locator.requireClass(TIMELINE_TWEET)
                val socialContextClass = locator.requireClass(SOCIAL_CONTEXT)
                val promotedContentClass = locator.requireClass(PROMOTED_CONTENT)

                return ModelShape(
                    collectionGetSize = collectionClass.getMethod("getSize"),
                    collectionGetItem = collectionClass.getMethod("n", Int::class.javaPrimitiveType),
                    listCollectionConstructor = listCollectionClass.getConstructor(Iterable::class.java),
                    timelineItemClass = timelineItemClass,
                    timelineItemGetGroupEntryId = timelineItemClass.getMethod("e"),
                    moduleHeaderItemClass = moduleHeaderItemClass,
                    timelineMetadataField = locator.requireDeclaredField(timelineItemClass, "timeline metadata") {
                        it.type == timelineMetadataClass
                    },
                    moduleHeaderField = locator.requireDeclaredField(timelineMetadataClass, "module header") {
                        it.type == moduleHeaderClass
                    },
                    moduleHeaderTitleField = locator.requireDeclaredField(moduleHeaderClass, "module header title") {
                        it.type == String::class.java
                    },
                    moduleHeaderSocialContextField = locator.requireDeclaredField(
                        moduleHeaderClass,
                        "module header social context",
                    ) {
                        it.type == socialContextClass
                    },
                    socialProofTextField = locator.requireDeclaredField(socialContextClass, "social proof text") {
                        it.name == "k" && it.type == String::class.java
                    },
                    timelineTweetItemClass = timelineTweetItemClass,
                    timelineTweetGetTweet = timelineTweetItemClass.getMethod("s"),
                    timelineTweetClass = timelineTweetClass,
                    promotedContentField = locator.requireDeclaredField(timelineTweetClass, "promoted content") {
                        it.type == promotedContentClass
                    },
                )
            }
        }
    }
}
