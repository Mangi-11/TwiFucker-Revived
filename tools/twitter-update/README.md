# Twitter/X 更新流水线

这个目录把“更新后重新逆向、重新确认 hook 点”固定成可重复流程。目标不是消灭人工判断，而是把人工判断压缩到最后一步：只审查脚本输出的契约报告和候选点。

## 运行方式

从已连接的 ADB 设备拉取当前 Twitter/X、反编译 base APK、生成分析报告：

```bash
tools/twitter-update/pull-and-analyze.sh --serial <adb-serial>
```

如果只有一台在线设备，可以省略 `--serial`：

```bash
tools/twitter-update/pull-and-analyze.sh
```

只分析已经存在的本地版本：

```bash
tools/twitter-update/pull-and-analyze.sh --analyze-existing 12.5.0-release.0_312050000
```

强制重跑 jadx：

```bash
tools/twitter-update/pull-and-analyze.sh --force-decompile
```

## 输出

脚本只写入被 git 忽略的 `references/`：

- `references/apks/twitter/<versionName>_<versionCode>/`：从设备拉取的 base/split APK。
- `references/decompiled/twitter/<versionName>_<versionCode>/base-jadx-no-res/`：未 deobf 的 jadx 输出，保留运行时真实类名。
- `references/decompiled/twitter/<versionName>_<versionCode>/analysis.json`：机器可读契约报告。
- `references/decompiled/twitter/<versionName>_<versionCode>/analysis.md`：人工审查报告。

## 契约状态

报告按 hook 域输出状态：

- `pass`：当前版本中所有关键锚点都存在。
- `partial`：部分锚点缺失，需要人工确认候选或调整 hook。
- `fail`：当前契约完全无法定位，通常表示目标路径大改。

当前脚本覆盖：

- 新版 Kotlin serialization URT 时间线过滤。
- 旧 LoganSquare URT 兜底过滤。
- 敏感媒体警告模型。
- 本地 Premium feature switch / userPreferences 门控。
- Grok/legacy 翻译双语对照入口。
- Google NativeAdView 兜底。

## 维护原则

- 第一遍 jadx 不启用 `--deobf`，因为 hook 需要运行时真实类名。
- 契约优先看接口、getter、返回类型语义、Kotlin serialization 模型结构，不把短混淆名当稳定依据。
- 报告里出现 `partial` 时，先看 `candidates`，再回到反编译源码确认。
- 新增 hook 时同步新增契约检查；不要只把类名写进模块代码。
- 运行时 resolver 以后可以读取同一套契约思路实现，但不要为了省一次逆向把 Dex 扫描依赖过早塞进模块。
