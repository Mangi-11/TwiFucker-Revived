# TwiFucker-Revived

简体中文 &nbsp;&nbsp;|&nbsp;&nbsp; [English](README_EN.md)

TwiFucker-Revived 是一个用于 X（Twitter）Android 应用的 Xposed 模块，灵感来自 [Dr-TSNG/TwiFucker](https://github.com/Dr-TSNG/TwiFucker)。由于原版已停止维护，本项目面向新版 X 重新定位并实现 hook。

## 功能

- 过滤新版 URT 时间线中的推广推文、推广用户、推广趋势、Premium 升级提示、探索页/RTB 推广内容和推荐关注模块。
- 隐藏 Google 原生广告 View（仅在目标应用包含 `NativeAdView` 时生效）。
- 本地放行 Premium UI gate（非真实订阅）。
- Grok 翻译双语对照：开启后显示“译文 + 原文”，关闭后放行官方默认显示。
- 在官方 Grok 翻译底部弹窗中添加 `双语对照` 开关。
- 双语对照开启时，将翻译状态栏 action 文案修正为 `隐藏翻译`；关闭时恢复官方 `显示原文`。

*更多功能持续开发中。*

## 支持版本

- Android：8.1 及以上，最低 SDK 27。
- Xposed 框架：支持 libxposed API 102。
- X（Twitter）：当前适配目标为 12.5.0-release.0（312050000），其他版本不保证兼容。
