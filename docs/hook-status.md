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

## 已验证生效

### 时间线推广推文

覆盖范围：普通信息流推广推文，例如带 `Ad` 标识的 NordVPN 推广推文。

相关 hook：

- `PromotedTweetHook`
  - 入口：`JsonTimelineTweet$$JsonObjectMapper.parse`
  - 行为：推广元数据非空时清空 tweetResults/tweetId。
  - 命中日志：`Neutralized promoted timeline tweet`
- `TimelinePromotionEntryHook`
  - 入口：`JsonTimelineEntry$$JsonObjectMapper.parse`
  - 入口：`JsonTimelineEntry.r()`
  - 行为：entryId 命中 `promotedTweet-` / `promoted-tweet-` 时清空 content 或在转换层返回 null。
  - 命中日志：`Removed promoted timeline entry`

验证结果：

- `Neutralized promoted timeline tweet` 曾多次命中，首页信息流广告移除已验证。
- `Removed promoted timeline entry` 命中 28 次，刷新时间线后 promoted entry 被丢弃。
- NordVPN 时间线广告问题已由 entry 前缀补全和 `r()` 转换层 hook 修复。

### 敏感媒体警告

相关 hook：

- `SensitiveMediaWarningHook`
  - 入口：`JsonSensitiveMediaWarning$$JsonObjectMapper.parse`
  - 行为：解析后将所有 boolean 警告字段置为 false。
  - 命中日志：`Cleared sensitive media warning`

验证结果：

- 注册已验证。
- 逻辑已由当前 X 的 `JsonMediaEntity` 转换路径确认：运行时敏感媒体集合由 `JsonSensitiveMediaWarning` 的 boolean 字段驱动。
- `Cleared sensitive media warning` 仍需要敏感媒体样本验证命中。

### 本地 Premium 状态

相关 hook：

- `LocalPremiumHook`
  - 入口：`com.twitter.util.config.a0` / `com.twitter.util.config.c0` 的 `(String, boolean) -> boolean` feature switch getter。
  - 入口：`com.twitter.subscriptions.features.api.i` / `...api.f` 的 Companion `g(String[], prefs)` userPreferences gate。
  - 行为：本地 UI gate 认为用户具备 Premium 状态。

验证结果：

- 12.5.0 静态契约已通过：`local_premium_gate=pass`。
- 12.1.1 上升级标志消失、Premium 入口显示曾验证。
- 12.5.0 迁移到 `a0` / `i` / `prefs.l` 后，真机命中仍需复验。

边界：

- 这只是本地 UI gate，不是服务端 entitlement。
- 支付页和真实 Premium 权益仍由服务端控制，不能视为真实订阅解锁。

## 已注册，等待样本验证

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
- 构建和 lint 已通过。
- 真机命中仍需在新版 X 进程中复验。

### 推广趋势

相关 hook：

- `PromotedTrendHook`
  - 入口：`JsonTimelineTrend$$JsonObjectMapper.parse`
  - 行为：`promotedMetadata` 非空时返回 null。
  - 命中日志：`Removed promoted timeline trend`

当前状态：

- 注册已验证。
- 探索页刷新时 `JsonTimelineTrend.parse` 曾被诊断确认调用 276 次。
- 当时 `promotedMetadata` 非空数量为 0，因此没有真实推广趋势样本。

### 推广用户

相关 hook：

- `PromotedUserHook`
  - 入口：`JsonTimelineUser$$JsonObjectMapper.parse`
  - 行为：推广元数据非空时清空 userResults。
  - 命中日志：`Removed promoted timeline user`

当前状态：

- 注册已验证。
- 当前没有推广用户样本，命中待验证。

### Google 原生广告

相关 hook：

- `GoogleAdsHook`
  - 入口：`NativeAdView.onVisibilityChanged`
  - 行为：设置 view 为 `GONE` 并跳过原方法。
  - 命中日志：`Hidden Google native ad view`

当前状态：

- `NativeAdView` 类存在，注册已验证。
- 当前没有 Google 原生广告场景，命中待验证。

### Premium 升级提示

相关 hook：

- `TimelinePromotionEntryHook`
  - 入口：`JsonTimelineEntry$$JsonObjectMapper.parse`
  - 入口：`JsonTimelineEntry.r()`
  - 行为：entryId 命中已知 `messageprompt-*` Premium upsell 时移除。
  - 命中日志：`Removed premium upsell prompt`

当前状态：

- 注册已验证。
- 当前没有对应 upsell 样本，命中待验证。

### 探索页大横幅和 RTB 图片广告

相关 hook：

- `TimelinePromotionEntryHook`
  - 入口：`JsonTimelineEntry$$JsonObjectMapper.parse`
  - 入口：`JsonTimelineEntry.r()`
  - 行为：entryId 命中 `superhero-` / `rtb-image-ad-` 时移除。
  - 命中日志：`Removed explore promotion banner` / `Removed RTB image ad`

当前状态：

- 注册已验证。
- 当前没有 `superhero-` / `rtb-image-ad-` 样本，命中待验证。

## 已调研但暂不实现

### 旧 LoganSquare Timeline module 广告过滤

调研结论：

- 当前 X 的 `JsonTimelineModule` 结构已确认：
  - items：`f96856a`
  - displayType：`d`
  - clientEventInfo：`e`
  - component：`JsonClientEventInfo.f96669a`
- Hachidori 0.32 存在 module hook，但 36 个 component 字符串被加密，无法从静态分析拿到明文。
- 真机诊断 131 个 module 样本，没有广告 module：
  - `tweet`：124
  - `suggest_who_to_follow`：4
  - `for_you_in_network`：2
  - `related_tweet`：1

处理决定：

- 不实现旧 LoganSquare module 的泛化广告过滤版。
- 不按 displayType 或猜测 component 值过滤，避免误删普通 carousel、话题、推荐关注等正常模块。
- 12.5.0 已在新版 `UrtTimelineItemFilterHook` 中实现 module 内部 item 递归过滤，以及 `suggest_who_to_follow` 整模块过滤；这不是旧 LoganSquare module 泛化过滤。
- 未来如果出现广告 module，需要重新启用诊断日志，基于真实 component/displayType/entryId 样本实现过滤。

## 非广告内容模块隐藏

### 推荐关注模块

覆盖范围：时间线里的推荐关注模块（Who to follow / suggest_who_to_follow）。

相关 hook：

- `WhoToFollowModuleHook`
  - 入口：`JsonTimelineModule$$JsonObjectMapper.parse`
  - 入口：`JsonTimelineEntry.r()`
  - 行为：module 的 component == `suggest_who_to_follow` 时返回 null，丢弃整个模块。
  - 命中日志：`Removed who-to-follow module`

验证结果：

- 注册已验证（module parse + r() 转换层双 hook）。
- `Removed who-to-follow module` 已命中，推荐关注模块隐藏已验证生效。
- 这不是广告过滤：推荐关注是正常推荐内容，不携带 promoted 元数据，按 component 标识识别。

## 翻译体验

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
  - 行为：在官方自动翻译 bottom sheet 的 `design_bottom_sheet` 里追加 `双语对照` 开关。
  - 命中日志：`Injected twitter bilingual translation switch`
  - 命中日志：`Injected x-lite bilingual translation switch`
  - 命中日志：`Bilingual translation enabled: true/false`

当前状态：

- 12.5.0 presenter state 分支注册已验证：`Registered on l.d`、`Registered on c.a`。
- 12.5.0 时间线自动翻译分支覆盖当前 `tweetview` 路径：`com.twitter.tweetview.core.ui.translation.i` -> `com.twitter.translation.d`。
- 12.5.0 翻译状态栏 action 文案覆盖当前 `GrokTranslationStatusView.setStatus`，修正自动翻译状态下 action 置空或仍显示 `显示原文` 的问题。
- 旧 TextView/legacy 自动翻译分支已移除，不再兼容旧 Twitter/X 版本；时间线双语使用当前 12.5.0 的 `tweetview` 路径。
- 自动翻译弹窗 marker 与 preference row 注册已验证：`Registered marker on g.a`、`Registered on v0.b`、`Registered on n1.a`。
- 第一版不主动批量请求翻译接口，只复用官方 X 已有的手动翻译或服务端自动翻译结果。
- 追加的原文暂不重建富文本实体，原文里的链接和 @ 提及不保证可点击；译文本身的实体列表保持原样。
- 自动翻译底部弹窗会注入 `双语对照` 开关，默认开启；关闭后双语文本改写会放行官方默认行为。
- 开关偏好存储在 X 目标进程本地 SharedPreferences 中，只影响 TwiFucker-Revived 的双语对照逻辑，不影响 X 官方自动翻译开关。
- libxposed API 102 的 `getRemotePreferences(group)` 在 hooked apps 中是只读偏好，更适合“模块自身设置页写入、目标进程读取”的跨进程配置。当前没有模块 Activity，且开关就在 X 目标进程内即时写入，因此暂用目标进程本地 SharedPreferences；后续增加模块设置页时再迁移到 `libxposed/service` + RemotePreferences。

## 当前覆盖层级

### JSON parse 层

覆盖网络响应 JSON 到 LoganSquare 模型的反序列化入口。

- `PromotedTweetHook`
- `PromotedTrendHook`
- `PromotedUserHook`
- `SensitiveMediaWarningHook`
- `TimelinePromotionEntryHook` parse 分支
- `WhoToFollowModuleHook` module parse 分支

### JsonTimelineEntry 转换层

覆盖 LoganSquare 模型到运行时 URT item 的转换入口，能处理部分本地缓存重新转换场景。

- `TimelinePromotionEntryHook` conversion 分支：`JsonTimelineEntry.r()`
- `WhoToFollowModuleHook` conversion 分支：`JsonTimelineEntry.r()`

说明：

- `r()` 是 LoganSquare 模型转换约定方法。
- 由于当前 X 的 R8/泛型/桥接处理，按返回类型签名定位不稳定，当前使用 `getMethod("r")` 定位。

### Kotlin serialization URT 层

覆盖 12.5.0 新版 `com.x.models.timelines.items.UrtTimelineItem` 到 timeline flow / module items 的渲染前路径。

- `UrtTimelineItemFilterHook` timeline flow 分支：`com.x.urt.v$a.emit(Object, Continuation)`
- `UrtTimelineItemFilterHook` module items 分支：`UrtTimelineModule.getItems()`

说明：

- 该层优先按模型接口、getter、copy 签名和 promoted metadata 定位。
- 每次更新后先运行 `tools/twitter-update/pull-and-analyze.sh`，确认 `new_urt_timeline_filter` 契约。

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

## 后续建议

广告/推广移除当前先视为阶段完成，不继续猜测性添加 module 过滤。

后续如果继续扩展，优先考虑可稳定验证的功能：

- t.co 去重定向或真实链接展开。
- 推荐关注模块隐藏。
- 媒体下载或保存入口。
- 全局 Premium upsell 隐藏，但需要先拿真实样本。

新增 hook 前应先确认：

- 是否有真实样本。
- hook 点属于 parse 层、转换层、repository/cache 层还是 view 层。
- 命中日志是否能证明功能真实生效。
- 如果没有样本，只能记录“注册已验证，命中待样本”，不能声明功能完成。
