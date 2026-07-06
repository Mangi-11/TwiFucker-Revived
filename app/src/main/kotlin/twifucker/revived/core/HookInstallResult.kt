package twifucker.revived.core

data class HookInstallResult(
    val hookName: String,
    val installedHooks: Int,
    val expectedHooks: Int,
    val failures: List<HookInstallFailure> = emptyList(),
) {
    val isComplete: Boolean
        get() = failures.isEmpty() && installedHooks >= expectedHooks

    companion object {
        fun complete(
            hookName: String,
            expectedHooks: Int,
            installedHooks: Int = expectedHooks,
        ): HookInstallResult {
            return HookInstallResult(
                hookName = hookName,
                installedHooks = installedHooks,
                expectedHooks = expectedHooks,
            )
        }

        fun failed(
            hookName: String,
            expectedHooks: Int,
            stage: String,
            throwable: Throwable,
        ): HookInstallResult {
            return HookInstallResult(
                hookName = hookName,
                installedHooks = 0,
                expectedHooks = expectedHooks,
                failures = listOf(
                    HookInstallFailure(
                        stage = stage,
                        message = "${throwable.javaClass.name}: ${throwable.message}",
                        throwable = throwable,
                    ),
                ),
            )
        }
    }
}

data class HookInstallFailure(
    val stage: String,
    val message: String,
    val throwable: Throwable? = null,
)
