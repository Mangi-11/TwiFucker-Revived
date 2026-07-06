package twifucker.revived.core

import io.github.libxposed.api.XposedInterface

object HookInstaller {
    private const val TAG = "TwiFuckerRevived/HookInstaller"

    fun installAll(
        xposed: XposedInterface,
        classLoader: ClassLoader,
        hooks: List<TargetHook>,
    ): List<HookInstallResult> {
        val context = HookContext(xposed, classLoader)
        val results = hooks.map { hook ->
            val result = runCatching {
                hook.install(context)
            }.getOrElse { throwable ->
                HookInstallResult.failed(
                    hookName = hook.name,
                    expectedHooks = hook.expectedHooks,
                    stage = "install",
                    throwable = throwable,
                )
            }
            logResult(xposed, result)
            result
        }
        logSummary(xposed, results)
        return results
    }

    private fun logResult(xposed: XposedInterface, result: HookInstallResult) {
        if (result.isComplete) {
            xposed.logI(
                TAG,
                "Installed ${result.hookName}: ${result.installedHooks}/${result.expectedHooks}",
            )
            return
        }

        val firstFailure = result.failures.firstOrNull()
        if (firstFailure == null) {
            xposed.logE(
                TAG,
                "Install ${result.hookName} incomplete: ${result.installedHooks}/${result.expectedHooks}",
            )
            return
        }

        if (firstFailure.throwable != null) {
            xposed.logE(
                TAG,
                "Install ${result.hookName} incomplete: ${result.installedHooks}/${result.expectedHooks}, ${firstFailure.stage}: ${firstFailure.message}",
                firstFailure.throwable,
            )
        } else {
            xposed.logE(
                TAG,
                "Install ${result.hookName} incomplete: ${result.installedHooks}/${result.expectedHooks}",
            )
        }
        for (failure in result.failures.drop(1)) {
            val throwable = failure.throwable
            if (throwable != null) {
                xposed.logE(TAG, "${result.hookName}/${failure.stage}: ${failure.message}", throwable)
            } else {
                xposed.logE(TAG, "${result.hookName}/${failure.stage}: ${failure.message}")
            }
        }
    }

    private fun logSummary(xposed: XposedInterface, results: List<HookInstallResult>) {
        val installed = results.sumOf { it.installedHooks }
        val expected = results.sumOf { it.expectedHooks }
        val failed = results.count { !it.isComplete }
        val message = "Hook install summary: installed=$installed/$expected, incomplete=$failed"
        if (failed == 0) {
            xposed.logI(TAG, message)
        } else {
            xposed.logW(TAG, message)
        }
    }
}
