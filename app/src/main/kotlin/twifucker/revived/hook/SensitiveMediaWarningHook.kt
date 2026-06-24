package twifucker.revived.hook

import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/**
 * 移除敏感媒体警告/遮罩。
 *
 * Twitter 用 LoganSquare mapper JsonSensitiveMediaWarning$$JsonObjectMapper#parse 解析敏感媒体
 * 警告，产出 JsonSensitiveMediaWarning 模型（adult_content / graphic_violence / other 三个
 * primitive boolean 字段）。把所有为 true 的字段置 false，使媒体不再被标记为敏感，遮罩消失。
 *
 * 选用 boolean 根因层而非运行时 Set 层：JsonMediaEntity 把这三个 boolean 转成运行时
 * Set<media.l>（ADULT_CONTENT/GRAPHIC_VIOLENCE/OTHER），是运行时 getSensitiveMediaCategories()
 * 的唯一来源。在 boolean 层置 false 即可让运行时 Set 为空，无需 hook 运行时 media entity 构造器
 * （Hachidori 0.32 的 Set 方案针对的是另一构建，当前 12.1.1 的 boolean 是根因）。
 *
 * 字段不写死名字，遍历 declaredFields 筛选 primitive boolean，以抵御字段重命名。解析方法按
 * 返回类型与参数个数定位，不硬编码 parse 方法名。
 */
object SensitiveMediaWarningHook {
    private const val TAG = "TwiFuckerRevived/SensitiveMediaWarning"

    private const val JSON_SENSITIVE_MEDIA_WARNING =
        "com.twitter.model.json.core.JsonSensitiveMediaWarning"
    private const val JSON_SENSITIVE_MEDIA_WARNING_MAPPER =
        "com.twitter.model.json.core.JsonSensitiveMediaWarning\$\$JsonObjectMapper"

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
        val warningClass = classLoader.loadClass(JSON_SENSITIVE_MEDIA_WARNING)
        val mapperClass = classLoader.loadClass(JSON_SENSITIVE_MEDIA_WARNING_MAPPER)

        val parseMethod = mapperClass.declaredMethods
            .firstOrNull { method ->
                method.returnType == warningClass && method.parameterCount == 1
            }
            ?: throw NoSuchMethodException("parse on $JSON_SENSITIVE_MEDIA_WARNING_MAPPER")

        if (!registeredMethods.add(parseMethod)) {
            xposed.log(Log.INFO, TAG, "Already registered on ${mapperClass.simpleName}.${parseMethod.name}, skip")
            return
        }

        xposed.hook(parseMethod).intercept { chain ->
            val warning = chain.proceed() ?: return@intercept null
            var cleared = 0
            for (field in warningClass.declaredFields) {
                if (field.type == Boolean::class.javaPrimitiveType) {
                    field.isAccessible = true
                    if (field.getBoolean(warning)) {
                        field.setBoolean(warning, false)
                        cleared++
                    }
                }
            }
            if (cleared > 0) {
                xposed.log(Log.DEBUG, TAG, "Cleared sensitive media warning")
            }
            warning
        }

        xposed.log(Log.INFO, TAG, "Registered on ${mapperClass.simpleName}.${parseMethod.name}")
    }
}
