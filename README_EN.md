# TwiFucker-Revived

[简体中文](README.md) &nbsp;&nbsp;|&nbsp;&nbsp; English

TwiFucker-Revived is an Xposed module for the X (Twitter) Android app. Inspired by [Dr-TSNG/TwiFucker](https://github.com/Dr-TSNG/TwiFucker), this project reverse-engineered the newer X app and reimplemented the relevant hooks after the original project was discontinued. Current features mainly include removing ads and promoted content, removing sensitive media warnings, and providing bilingual Grok translation.

## Features

- Removes promoted tweets from the timeline.
- Removes promoted trends, promoted users, explore banners, and Google native ads.
- Filters ads, Premium upsells, and who-to-follow modules from the new URT timeline.
- Removes sensitive media warnings.
- Unlocks the local Premium UI entry (not a real subscription).
- Provides bilingual Grok translation with three display modes:
  - translation above original
  - original above translation
  - translation only
- Adds a bilingual translation toggle to the official Grok translation bottom sheet.
- Hides the who-to-follow module from the timeline.

*More features are under development.*

## Supported Versions

- Android: 8.1 and later, minimum SDK 27.
- Xposed Framework: supporting libxposed API 102.
- X (Twitter): tested on version 12.5.0-release.0 (312050000).
