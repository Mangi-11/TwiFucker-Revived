# Hook 状态记录

本文记录 TwiFuckerX 当前 hook 的验证状态，避免后续重复定位已经确认过的问题。

## 当前环境

- 目标应用：`com.twitter.android`
- 当前适配版本：X/Twitter 12.1.1
- 模块包名：`mangi.twifuckerx`
- Xposed API：libxposed API 102
- 日志位置：LSPosed verbose 日志，通常在 `/data/adb/lspd/log/verbose_*.log`

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
  - 入口：feature switch / userPreferences 相关本地判断。
  - 行为：本地 UI gate 认为用户具备 Premium 状态。

验证结果：

- 注册已验证。
- 升级标志消失、Premium 入口显示已验证。

边界：

- 这只是本地 UI gate，不是服务端 entitlement。
- 支付页和真实 Premium 权益仍由服务端控制，不能视为真实订阅解锁。

## 已注册，等待样本验证

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

### Timeline module 过滤

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

- 不实现 module 过滤版。
- 不按 displayType 或猜测 component 值过滤，避免误删普通 carousel、话题、推荐关注等正常模块。
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

### View 层

覆盖第三方原生广告 view 渲染入口。

- `GoogleAdsHook`

### 本地配置层

覆盖本地 feature switch 和 userPreferences 判断。

- `LocalPremiumHook`

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
