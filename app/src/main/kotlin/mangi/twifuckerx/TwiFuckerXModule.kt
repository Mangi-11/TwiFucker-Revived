package mangi.twifuckerx

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import mangi.twifuckerx.hook.PromotedTweetHook

class TwiFuckerXModule : XposedModule() {
    companion object {
        private const val TAG = "TwiFuckerX"
        private const val TARGET_PACKAGE = "com.twitter.android"
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(
            Log.INFO,
            TAG,
            "onModuleLoaded: ${param.processName}, framework: $frameworkName($frameworkVersionCode), API $apiVersion",
        )
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != TARGET_PACKAGE) return
        log(Log.INFO, TAG, "onPackageReady: ${param.packageName}")
        PromotedTweetHook.register(this, param.classLoader)
    }
}
