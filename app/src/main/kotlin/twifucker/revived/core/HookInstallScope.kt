package twifucker.revived.core

class HookInstallScope(
    private val hookName: String,
    private val expectedHooks: Int,
) {
    private var installedHooks = 0
    private val failures = mutableListOf<HookInstallFailure>()

    fun install(stage: String, block: () -> Unit) {
        try {
            block()
            installedHooks += 1
        } catch (t: Throwable) {
            fail(stage, t)
        }
    }

    fun fail(stage: String, throwable: Throwable) {
        failures += HookInstallFailure(
            stage = stage,
            message = "${throwable.javaClass.name}: ${throwable.message}",
            throwable = throwable,
        )
    }

    fun result(): HookInstallResult {
        return HookInstallResult(
            hookName = hookName,
            installedHooks = installedHooks,
            expectedHooks = expectedHooks,
            failures = failures.toList(),
        )
    }
}
