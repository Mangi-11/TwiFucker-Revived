# AGENTS.md

## 项目概览

TwiFuckerX 是一个 Kotlin Android/Xposed 模块项目，目标是基于 `libxposed/api` 102，把旧版 TwiFucker 的思路适配到当前 Twitter/X（`com.twitter.android`）。

本项目要实现的是面向新版 Twitter/X 的全新 hook。旧版 TwiFucker 只作为行为和架构参考；不要直接大段复制旧项目源码。实现任何 hook 前，都必须先在当前 Twitter/X APK 的反编译结果中重新确认目标类、方法和字段仍然存在。

`libxposed/api` 102 较新，通用模型训练语料中可能没有完整或准确资料。编写模块代码前，必须先查看本地 `references/upstream/libxposed-api/` 中的 API 102 源码和示例用法，再决定如何实现。

## 当前模块结构

- Android 模块：`:app`
- 包名 / namespace / applicationId：`mangi.twifuckerx`
- Xposed 入口类：`mangi.twifuckerx.TwiFuckerXModule`
- Xposed 元数据：
  - `app/src/main/resources/META-INF/xposed/java_init.list`
  - `app/src/main/resources/META-INF/xposed/module.prop`
  - `app/src/main/resources/META-INF/xposed/scope.list`
- 目标作用域：`com.twitter.android`
- Xposed API 依赖：`io.github.libxposed:api:102.0.0`，使用 `compileOnly`

## 构建与验证

修改代码后运行：

```bash
./gradlew :app:assembleDebug :app:lintDebug
```

修改 Xposed 元数据后，还要检查构建出的 APK 是否包含：

```text
META-INF/xposed/java_init.list
META-INF/xposed/module.prop
META-INF/xposed/scope.list
```

## 本地参考资料

本地研究资料放在 `references/` 下。该目录已被 Git 忽略，可能包含大型文件或第三方文件。

重要参考路径：

- `references/upstream/TwiFucker/`：旧版 TwiFucker 参考源码，来源为 `Dr-TSNG/TwiFucker`
- `references/upstream/libxposed-api/`：libxposed API 源码，固定在 API `102.0.0`
- `references/apks/twitter/12.1.1-release.0_312011000/`：从设备拉取的 Twitter/X APK 和 split APK
- `references/decompiled/twitter/12.1.1-release.0_312011000/base-jadx-no-res/sources/`：jadx 输出，用于查找 Java/Kotlin 类名和 hook 点
- `references/decompiled/twitter/12.1.1-release.0_312011000/apktool/`：apktool 输出，用于查看 Manifest、资源、smali、split 资源和 native 库
- `references/decompiled/twitter/12.1.1-release.0_312011000/README.md`：该版本 APK 和反编译输出的本地说明

除非用户明确要求更新本地研究资料，否则 `references/` 只用于读取和分析。不要尝试提交 `references/` 下的任何文件。

## Hook 开发规则

- 开始写 hook 代码前，先阅读 `references/upstream/libxposed-api/`，确认 API 102 的类、方法签名和生命周期。
- 参考旧版实现时，先查看 `references/upstream/TwiFucker/` 理解旧项目做了什么，再用当前 Twitter/X 反编译结果重新定位 hook 点。
- 第一阶段优先做数据层 / 模型层 hook，不优先做 UI hook。
- 查找类、方法、字段时，优先使用未 deobf 的 jadx 输出，保留运行时真实命名。
- 添加 hook 前，必须在当前 Twitter/X 反编译源码中确认目标仍然存在。
- 如果可以用返回类型、参数类型、字段类型等签名定位，就不要硬编码不稳定的方法名。
- hook 代码保持小而独立。只有出现真实重复或复杂度后，才添加抽象。
- hook 注册点和失败点要有足够清晰的日志，方便用 `logcat` 调试。
- 新代码不要使用旧版 `de.robv.android.xposed` API。本项目目标是 `libxposed/api` 102。

## 真机调试

- 用户通常会连接 Android 真机用于 ADB 调试。
- ADB shell 已被用户授予 root 权限；需要确认运行时行为时，可以使用 ADB、root shell、进程信息、日志、内存相关信息等本机可用上下文进行分析。
- 如果 ADB 设备不在线，通常表示用户已经拔掉手机。需要真机上下文时，不要继续编写或猜测相关代码；应暂停相关工作，等待用户重新连接 ADB，或明确请求用户连接 ADB 后再继续。

## 代码风格

- 项目保持 Kotlin 优先。
- 代码首先是给人读、维护和审查的，其次才是给机器执行的。可读、可理解、可验证、可安全修改，比“看起来聪明”更重要。
- 简洁不是压缩代码行数；优先选择意图清楚、命名准确、控制流直接、失败路径明确的写法。
- 优雅来自清晰边界：hook 定位、条件判断、日志、失败处理和业务行为尽量分开，不把所有逻辑堆进一个大方法。
- 有品味的代码不炫技、不滥用反射、不制造隐式魔法，不为了消除几行重复过早抽象。
- 源码组织要清晰。不要把所有 hook 都写在一个文件里；按功能域、目标模型、目标页面或稳定的 hook 入口拆分文件和包。
- Kotlin 文件应聚焦单一主题，文件名要能说明内容；同一文件中的声明必须语义相关，避免 `Utils`、`Hooks` 这类长期膨胀的万能文件。
- 模块入口只负责生命周期分发和顶层注册，不承载具体 hook 细节。具体 hook 应放到独立类或文件中，保持单个文件大小合理、便于审查。
- 新增 hook 时优先做小而可验证的增量改动；如果一次改动同时涉及多个互不相关的 hook，拆开处理。
- 用户没有要求前，不添加 Activity、配置界面或设置页。
- 初始模块保持轻量，不添加不必要的 AndroidX、Material、网络库或反射辅助库依赖。
- 遵循 Kotlin 官方编码约定、Android Kotlin Style Guide 和本项目现有风格；如果规则冲突，以本项目当前一致性和可维护性为准。
- 代码注释使用中文。运行时输出（日志字符串、tag、异常消息等）保持英文，便于在 `logcat` / LSPosed 日志中检索。
- 不要提交 feedback 式代码：不要把临时调试、解释性噪音、一次性验证代码或无意义注释放进长期代码里。

## 语言约定

- 思维链、对话、文档、代码注释都使用中文。
- 代码标识符（类名、方法名、变量名、包名）和运行时输出保持英文。

## Git 与本地文件

- 在仓库尚未推送前，保持干净、最小的提交历史。
- 不要提交生成产物、APK、反编译文件、`.idea/`、`.gradle/`、`.kotlin/`、`build/`、`local.properties` 或签名密钥。
- `references/` 必须保持为被忽略的本地目录。

## 安全与法律注意事项

- 将第三方 APK 和反编译代码视为本地研究资料。
- 不要把第三方源码的大段内容原样粘贴进项目。
- 如果后续要公开发布，应以 clean rewrite 的方式实现所需行为，不要直接复制无许可证或许可证不兼容的代码。
