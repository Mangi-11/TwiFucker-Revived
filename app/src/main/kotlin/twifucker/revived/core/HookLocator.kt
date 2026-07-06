package twifucker.revived.core

import java.lang.reflect.Field
import java.lang.reflect.Method

class HookLocator(private val classLoader: ClassLoader) {
    fun requireClass(name: String): Class<*> {
        return runCatching {
            classLoader.loadClass(name)
        }.getOrElse { throwable ->
            throw ClassNotFoundException("Required class not found: $name", throwable)
        }
    }

    fun requireDeclaredMethod(
        owner: Class<*>,
        description: String,
        predicate: (Method) -> Boolean,
    ): Method {
        return owner.declaredMethods
            .firstOrNull(predicate)
            ?.apply { isAccessible = true }
            ?: throw NoSuchMethodException("$description on ${owner.name}")
    }

    fun requireDeclaredField(
        owner: Class<*>,
        description: String,
        predicate: (Field) -> Boolean,
    ): Field {
        return owner.declaredFields
            .firstOrNull(predicate)
            ?.apply { isAccessible = true }
            ?: throw NoSuchFieldException("$description on ${owner.name}")
    }
}
