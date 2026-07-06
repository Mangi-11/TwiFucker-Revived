# TwiFucker-Revived

[简体中文](README.md) &nbsp;&nbsp;|&nbsp;&nbsp; English

TwiFucker-Revived is an Xposed module for the X (Twitter) Android app. Inspired by [Dr-TSNG/TwiFucker](https://github.com/Dr-TSNG/TwiFucker), this project relocates and reimplements hooks for newer X versions after the original project was discontinued.

## Features

- Filters promoted posts, promoted users, promoted trends, Premium upsells, explore/RTB promotions, and who-to-follow modules from the new URT timeline.
- Hides Google native ad views when the target app includes `NativeAdView`.
- Locally bypasses Premium UI gates (not a real subscription).
- Provides bilingual Grok translation: when enabled, it shows translation plus original text; when disabled, it lets the official UI render normally.
- Adds a `双语对照` toggle to the official Grok translation bottom sheet.
- Updates the translation status action label to `隐藏翻译` while bilingual mode is enabled, and restores the official `显示原文` label when disabled.

*More features are under development.*

## Supported Versions

- Android: 8.1 and later, minimum SDK 27.
- Xposed Framework: supporting libxposed API 102.
- X (Twitter): currently targets 12.5.0-release.0 (312050000). Other versions are not guaranteed to work.
