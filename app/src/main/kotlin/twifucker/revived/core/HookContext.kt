package twifucker.revived.core

import io.github.libxposed.api.XposedInterface

data class HookContext(
    val xposed: XposedInterface,
    val classLoader: ClassLoader,
)
