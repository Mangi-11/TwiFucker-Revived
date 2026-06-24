package twifucker.revived.hook

import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/**
 * 移除 URT 时间线中的推广（广告）推文。
 *
 * Twitter 用 LoganSquare mapper JsonTimelineTweet$$JsonObjectMapper#parse 解析每条时间线
 * 推文，产出 JsonTimelineTweet 模型。当模型的推广元数据字段非空时，该推文是广告；通过清空
 * 其推文结果与推文 id 字段使其失效，模型不再产出时间线条目，广告即被丢弃。
 *
 * 字段按类型签名定位（而非名字），以抵御字段重命名。解析方法按返回类型和参数个数定位，不硬
 * 编码方法名——LoganSquare 入口在旧版 Twitter 叫 _parse，当前 12.1.1 已改名为 parse。
 */
object PromotedTweetHook {
    private const val TAG = "TwiFuckerRevived/PromotedTweet"

    private const val JSON_TIMELINE_TWEET =
        "com.twitter.model.json.timeline.urt.JsonTimelineTweet"
    private const val JSON_TIMELINE_TWEET_MAPPER =
        "com.twitter.model.json.timeline.urt.JsonTimelineTweet\$\$JsonObjectMapper"
    private const val JSON_TWEET_RESULTS =
        "com.twitter.model.json.core.JsonTweetResults"
    private const val JSON_PROMOTED_CONTENT_URT =
        "com.twitter.model.json.timeline.urt.JsonPromotedContentUrt"

    // onPackageReady 在同一进程内可能被回调多次（见 LSPosed 日志），用它记录已注册过的解析
    // 方法，避免对同一个 parse method 重复 hook。Method 的 equals/hashCode 基于声明类与
    // 方法签名，同一 classloader 下同一方法语义相等；WeakHashMap 让进程退出后可被回收。
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
        val timelineTweetClass = classLoader.loadClass(JSON_TIMELINE_TWEET)
        val mapperClass = classLoader.loadClass(JSON_TIMELINE_TWEET_MAPPER)
        val promotedContentClass = classLoader.loadClass(JSON_PROMOTED_CONTENT_URT)

        // JsonTweetResults 只有一个字段，其类型即推文结果模型 builder；
        // 读取它可避免硬编码被混淆的 builder 类型。
        val tweetResultType = classLoader.loadClass(JSON_TWEET_RESULTS)
            .declaredFields.first().type

        val tweetResultField = fieldOfType(timelineTweetClass, tweetResultType)
        val tweetIdField = fieldOfType(timelineTweetClass, String::class.java)
        val promotedField = fieldOfType(timelineTweetClass, promotedContentClass)

        val parseMethod = mapperClass.declaredMethods
            .firstOrNull { method ->
                method.returnType == timelineTweetClass && method.parameterCount == 1
            }
            ?: throw NoSuchMethodException("parse on $JSON_TIMELINE_TWEET_MAPPER")

        if (!registeredMethods.add(parseMethod)) {
            xposed.log(Log.INFO, TAG, "Already registered on ${mapperClass.simpleName}.${parseMethod.name}, skip")
            return
        }

        xposed.hook(parseMethod).intercept { chain ->
            val tweet = chain.proceed() ?: return@intercept null
            if (promotedField.get(tweet) == null) return@intercept tweet
            tweetResultField.set(tweet, null)
            tweetIdField.set(tweet, null)
            xposed.log(Log.DEBUG, TAG, "Neutralized promoted timeline tweet")
            tweet
        }

        xposed.log(Log.INFO, TAG, "Registered on ${mapperClass.simpleName}.${parseMethod.name}")
    }

    private fun fieldOfType(clazz: Class<*>, type: Class<*>): Field =
        clazz.declaredFields.first { it.type == type }.apply { isAccessible = true }
}
