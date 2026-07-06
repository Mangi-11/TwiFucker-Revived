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
 * 过滤旧版 collection adapter 链路中的 promoted tweet row。
 *
 * TweetDetailActivity 和部分 legacy 列表仍使用 h0 -> ItemCollection 链路。
 * 这里在 h0 更新集合前过滤 a0.s() 指向的 Tweet 中带有 promotedContent 的 item，
 * 避免 UI 层继续渲染 tweet_ad_badge_top_right 这类旧 row 广告。
 */
object LegacyTimelineItemFilterHook : TargetHook {
    private const val TAG = "TwiFuckerRevived/LegacyTimeline"

    private const val LEGACY_LIST_HOST = "com.twitter.app.legacy.list.h0"
    private const val ITEM_COLLECTION = "com.twitter.model.common.collection.e"
    private const val LIST_COLLECTION = "com.twitter.model.common.collection.g"
    private const val TIMELINE_TWEET_ITEM = "com.twitter.model.timeline.a0"
    private const val TIMELINE_TWEET = "com.twitter.model.core.e"
    private const val PROMOTED_CONTENT = "com.twitter.model.core.entity.ad.h"

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
                "Filtered legacy timeline items: removed=${filtered.removed}, kept=${filtered.items.size}",
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
        val out = ArrayList<Any?>(size)

        for (index in 0 until size) {
            val item = shape.collectionGetItem.invoke(collection, index)
            if (item != null && isPromotedTimelineItem(item, shape)) {
                changed = true
                removed += 1
                continue
            }
            out.add(item)
        }

        return if (changed) {
            CollectionFilterResult(items = out, changed = true, removed = removed)
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

    private data class CollectionFilterResult(
        val items: List<Any?>,
        val changed: Boolean,
        val removed: Int,
    ) {
        companion object {
            private val UNCHANGED = CollectionFilterResult(emptyList(), changed = false, removed = 0)
            fun unchanged() = UNCHANGED
        }
    }

    private data class ModelShape(
        val collectionGetSize: Method,
        val collectionGetItem: Method,
        val listCollectionConstructor: Constructor<*>,
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
                val timelineTweetItemClass = locator.requireClass(TIMELINE_TWEET_ITEM)
                val timelineTweetClass = locator.requireClass(TIMELINE_TWEET)
                val promotedContentClass = locator.requireClass(PROMOTED_CONTENT)

                return ModelShape(
                    collectionGetSize = collectionClass.getMethod("getSize"),
                    collectionGetItem = collectionClass.getMethod("n", Int::class.javaPrimitiveType),
                    listCollectionConstructor = listCollectionClass.getConstructor(Iterable::class.java),
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
