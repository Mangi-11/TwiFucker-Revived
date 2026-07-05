# TwiFucker-Revived

简体中文 &nbsp;&nbsp;|&nbsp;&nbsp; [English](README_EN.md)

TwiFucker-Revived 是一个用于 X（Twitter）Android 应用的 Xposed 模块，灵感来自 [Dr-TSNG/TwiFucker](https://github.com/Dr-TSNG/TwiFucker)。由于原版已停止维护，本项目逆向了新版 X，并重新实现了相关 hook。当前功能主要包括移除广告与推广内容、移除敏感媒体警告，以及为 Grok 翻译提供双语对照。

## 功能

- 移除时间线中的推广推文。
- 移除推广趋势、推广用户、探索页推广横幅和 Google 原生广告。
- 过滤新版 URT 时间线中的广告、Premium 升级提示和推荐关注模块。
- 移除敏感媒体警告。
- 本地解锁 Premium 界面入口（非真实订阅）。
- Grok 翻译双语对照，支持三种显示模式：
  - 译文在上，原文在下
  - 原文在上，译文在下
  - 仅显示译文
- 在官方 Grok 翻译底部弹窗中添加双语对照开关。
- 隐藏时间线中的推荐关注模块。

*更多功能持续开发中。*

## 支持版本

- Android：8.1 及以上，最低 SDK 27。
- Xposed 框架：支持 libxposed API 102。
- X（Twitter）：已在 12.5.0-release.0（312050000）版本测试。
