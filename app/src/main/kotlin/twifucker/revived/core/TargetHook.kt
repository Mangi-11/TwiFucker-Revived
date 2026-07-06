package twifucker.revived.core

interface TargetHook {
    val name: String
    val expectedHooks: Int

    fun install(context: HookContext): HookInstallResult
}
