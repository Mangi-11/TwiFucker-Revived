package twifucker.revived

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import twifucker.revived.core.HookInstaller
import twifucker.revived.core.TargetHook
import twifucker.revived.core.logI
import twifucker.revived.hook.BilingualTimelineTranslationHook
import twifucker.revived.hook.BilingualTranslationHook
import twifucker.revived.hook.BilingualTranslationSettingsHook
import twifucker.revived.hook.BilingualTranslationStatusHook
import twifucker.revived.hook.GoogleAdsHook
import twifucker.revived.hook.LegacyTimelineItemFilterHook
import twifucker.revived.hook.LocalPremiumHook
import twifucker.revived.hook.UrtTimelineItemFilterHook

class TwiFuckerRevivedModule : XposedModule() {
    companion object {
        private const val TAG = "TwiFuckerRevived"
        private const val TARGET_PACKAGE = "com.twitter.android"

        private val TARGET_HOOKS: List<TargetHook> = listOf(
            UrtTimelineItemFilterHook,
            LegacyTimelineItemFilterHook,
            GoogleAdsHook,
            LocalPremiumHook,
            BilingualTranslationHook,
            BilingualTimelineTranslationHook,
            BilingualTranslationStatusHook,
            BilingualTranslationSettingsHook,
        )
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        logI(
            TAG,
            "onModuleLoaded: ${param.processName}, framework: $frameworkName($frameworkVersionCode), API $apiVersion",
        )
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != TARGET_PACKAGE) return
        logI(TAG, "onPackageReady: ${param.packageName}")
        HookInstaller.installAll(this, param.classLoader, TARGET_HOOKS)
    }
}
