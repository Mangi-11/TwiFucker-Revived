# Hook 状态记录

本文记录 TwiFucker-Revived 当前 hook 的验证状态，避免后续重复定位已经确认过的问题。

## 当前环境

- 目标应用：`com.twitter.android`
- 当前适配版本：X/Twitter 12.5.0-release.0（312050000）
- 模块包名：`twifucker.revived`
- Xposed API：libxposed API 102
- 日志位置：LSPosed verbose 日志，通常在 `/data/adb/lspd/log/verbose_*.log`
- 更新流水线：`tools/twitter-update/pull-and-analyze.sh`
- 最近静态契约报告：`references/decompiled/twitter/12.5.0-release.0_312050000/analysis.md`

## 版本策略

本项目当前只维护新版 X/Twitter 12.5.0 路径。

旧 LoganSquare / 12.1.1 hook 已删除，不再兼容旧版 Twitter/X：

- `PromotedTweetHook`
- `PromotedTrendHook`
- `PromotedUserHook`
- `TimelinePromotionEntryHook`
- `WhoToFollowModuleHook`
- `SensitiveMediaWarningHook`

## 当前 Hook

### 新版 URT 时间线过滤

相关 hook：

- `UrtTimelineItemFilterHook`
  - 入口：`com.x.urt.v$a.emit(Object, Continuation)`
  - 入口：`UrtTimelineModule.getItems()`
  - 行为：在新版 `com.x.models.timelines.items.UrtTimelineItem` 层过滤 promoted post/user/trend、Premium upsell entry、探索页推广 entry、RTB 图片广告 entry、who-to-follow module，并递归过滤 module 内部 item。
  - 命中日志：`Filtered new URT timeline items: removed=...`
  - 命中日志：`Filtered new URT module items: removed=...`

当前状态：

- 12.5.0 静态契约已通过：`new_urt_timeline_filter=pass`。
- 真机命中仍需在新版 X 进程中复验。

### Google 原生广告

相关 hook：

- `GoogleAdsHook`
  - 入口：`NativeAdView.onVisibilityChanged`
  - 行为：设置 view 为 `GONE` 并跳过原方法。
  - 命中日志：`Hidden Google native ad view`

当前状态：

- `NativeAdView` 类存在时注册；类不存在时跳过，不视为失败。
- 当前没有 Google 原生广告场景，命中待验证。

### 本地 Premium 状态

相关 hook：

- `LocalPremiumHook`
  - 入口：`com.twitter.util.config.a0` / `com.twitter.util.config.c0` 的 `(String, boolean) -> boolean` feature switch getter。
  - 入口：`com.twitter.subscriptions.features.api.i` / `...api.f` 的 Companion `g(String[], prefs)` userPreferences gate。
  - 行为：本地 UI gate 认为用户具备 Premium 状态。

当前状态：

- 12.5.0 静态契约已通过：`local_premium_gate=pass`。
- 12.5.0 迁移到 `a0` / `i` / `prefs.l` 后，真机命中仍需复验。

边界：

- 这只是本地 UI gate，不是服务端 entitlement。
- 支付页和真实 Premium 权益仍由服务端控制，不能视为真实订阅解锁。

### Grok 翻译双语对照

相关 hook：

- `BilingualTranslationHook`
  - 入口：`com.x.urt.items.post.translate.grok.l` 的 presenter state 方法
  - 入口：`com.x.urt.items.post.translate.grok.c` 的 presenter state 方法
  - 行为：官方 X 已经拿到 `TranslatedPost` 后，将译文文本改成“译文 + 原文”，并保留中间空行。
  - 命中日志：`Applied bilingual translation`
- `BilingualTimelineTranslationHook`
  - 入口：`com.twitter.tweetview.core.ui.translation.i.invoke(Object)`
  - 入口：`com.twitter.translation.d.a(f1, g, Function0)`
  - 行为：在 12.5.0 时间线自动翻译绑定时缓存“译文 -> 原文”，并在翻译文本写入 `grok_translation_text` 前替换成双语内容。
  - 命中日志：`Applied timeline bilingual translation`
- `BilingualTranslationStatusHook`
  - 入口：`com.twitter.translation.GrokTranslationStatusView.setStatus`
  - 行为：翻译文本已显示时，根据 `双语对照` 开关修正 action 文案；开启时显示 `隐藏翻译`，关闭时恢复官方 `显示原文`。
  - 命中日志：`Updated translation action label: bilingual=true/false`
- `BilingualTranslationSettingsHook`
  - 入口：`com.twitter.translation.dialog.g.a`
  - 入口：`com.twitter.ui.components.preference.v0.b`
  - 入口：`com.x.ui.common.ports.preference.n1.a`
  - 行为：在官方自动翻译 bottom sheet 里追加 `双语对照` 开关。
  - 命中日志：`Injected twitter bilingual translation switch`
  - 命中日志：`Injected x-lite bilingual translation switch`
  - 命中日志：`Bilingual translation enabled: true/false`

当前状态：

- 12.5.0 presenter state 分支注册已验证：`Registered on l.d`、`Registered on c.a`。
- 12.5.0 时间线自动翻译分支覆盖当前 `tweetview` 路径：`com.twitter.tweetview.core.ui.translation.i` -> `com.twitter.translation.d`。
- 12.5.0 翻译状态栏 action 文案覆盖当前 `GrokTranslationStatusView.setStatus`。
- 旧 TextView/legacy 自动翻译分支已移除，不再兼容旧 Twitter/X 版本。
- 自动翻译底部弹窗会注入 `双语对照` 开关，默认开启；关闭后双语文本改写会放行官方默认行为。
- 开关偏好存储在 X 目标进程本地 SharedPreferences 中，只影响 TwiFucker-Revived 的双语对照逻辑，不影响 X 官方自动翻译开关。

## 当前覆盖层级

### Kotlin serialization URT 层

覆盖 12.5.0 新版 `com.x.models.timelines.items.UrtTimelineItem` 到 timeline flow / module items 的渲染前路径。

- `UrtTimelineItemFilterHook` timeline flow 分支：`com.x.urt.v$a.emit(Object, Continuation)`
- `UrtTimelineItemFilterHook` module items 分支：`UrtTimelineModule.getItems()`

### View 层

覆盖第三方原生广告 view 渲染入口。

- `GoogleAdsHook`

### 本地配置层

覆盖本地 feature switch 和 userPreferences 判断。

- `LocalPremiumHook`

### Presenter/UI state 层

覆盖官方 X 已组装好的界面状态，不改原始模型和网络数据。

- `BilingualTranslationHook`
- `BilingualTimelineTranslationHook`
- `BilingualTranslationStatusHook`
- `BilingualTranslationSettingsHook`

## 后续建议

广告/推广移除当前先视为阶段完成，不继续猜测性添加旧 module 过滤。

后续如果继续扩展，优先考虑可稳定验证的功能：

- t.co 去重定向或真实链接展开。
- 12.5.0 敏感媒体遮罩移除，需要重新定位当前模型或 UI 层，不复用旧 LoganSquare hook。
- 媒体下载或保存入口。
- 全局 Premium upsell 隐藏，但需要先拿真实样本。

新增 hook 前应先确认：

- 是否有真实样本。
- hook 点属于模型层、repository/cache 层、Presenter/UI state 层还是 view 层。
- 命中日志是否能证明功能真实生效。
- 如果没有样本，只能记录“注册已验证，命中待样本”，不能声明功能完成。
