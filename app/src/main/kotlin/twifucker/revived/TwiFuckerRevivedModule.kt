package twifucker.revived

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import twifucker.revived.hook.BilingualTimelineTranslationHook
import twifucker.revived.hook.BilingualTranslationHook
import twifucker.revived.hook.BilingualTranslationSettingsHook
import twifucker.revived.hook.BilingualTranslationStatusHook
import twifucker.revived.hook.GoogleAdsHook
import twifucker.revived.hook.LocalPremiumHook
import twifucker.revived.hook.PromotedTrendHook
import twifucker.revived.hook.PromotedTweetHook
import twifucker.revived.hook.PromotedUserHook
import twifucker.revived.hook.SensitiveMediaWarningHook
import twifucker.revived.hook.TimelinePromotionEntryHook
import twifucker.revived.hook.UrtTimelineItemFilterHook
import twifucker.revived.hook.WhoToFollowModuleHook

class TwiFuckerRevivedModule : XposedModule() {
    companion object {
        private const val TAG = "TwiFuckerRevived"
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
        PromotedTrendHook.register(this, param.classLoader)
        PromotedUserHook.register(this, param.classLoader)
        TimelinePromotionEntryHook.register(this, param.classLoader)
        WhoToFollowModuleHook.register(this, param.classLoader)
        UrtTimelineItemFilterHook.register(this, param.classLoader)
        GoogleAdsHook.register(this, param.classLoader)
        SensitiveMediaWarningHook.register(this, param.classLoader)
        LocalPremiumHook.register(this, param.classLoader)
        BilingualTranslationHook.register(this, param.classLoader)
        BilingualTimelineTranslationHook.register(this, param.classLoader)
        BilingualTranslationStatusHook.register(this, param.classLoader)
        BilingualTranslationSettingsHook.register(this, param.classLoader)
    }
}
