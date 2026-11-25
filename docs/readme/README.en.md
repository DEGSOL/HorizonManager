# Hovq 0.5.8

[Русский](../../README.md) · **English** · [Deutsch](README.de.md) · [Français](README.fr.md) · [Italiano](README.it.md) · [Español](README.es.md) · [日本語](README.ja.md) · [한국어](README.ko.md) · [简体中文](README.zh.md) · [Қазақша](README.kk.md) · [Татарча](README.tt.md)

Hovq is an Android app for installing and managing [Horizon](https://github.com/Thysrael/Horizon) on your own VPS and reading news digests. Horizon collects articles on the server, removes duplicates, and uses your chosen AI model to score and translate the articles and prepare each issue.

[Download the signed APK and read the release notes](https://github.com/DEGSOL/HorizonManager/releases/latest)

## Features

- RSS, Telegram, Reddit, and Hacker News sources, scoring profiles, cover images, and on-demand article enrichment.
- Manual, weekly, or fixed-interval runs with a configurable start time. The schedule runs on the VPS.
- A feed with search, filters, and unread articles, plus favorites, an archive, and downloaded issues for offline reading.
- An activity log and encrypted backups for phone and server settings.

## Installation and first digest

**To install, all you need is your own VPS running Ubuntu Server 24.04 LTS or Debian 12 with SSH access.** Connect to it from Hovq and confirm installation. The app installs Horizon, its companion agent, and the required dependencies automatically.

1. Install the APK on Android 8.0 or newer. Prepare a VPS with Ubuntu Server 24.04 LTS or Debian 12, systemd, SSH, and access to OS repositories, GitHub, and PyPI. The installer checks the architecture, at least 432 MiB of RAM, and 1.5 GiB of free disk space. Collection and processing may require more resources.
2. Add the server in Hovq, enter an SSH password or select a private key, and verify the fingerprint in the provider’s console. SSH must allow forwarding to `127.0.0.1:18777` on the VPS. Confirm installation and enter the sudo password if requested. You can also connect to an existing agent without reinstalling it.
3. Set the AI provider, model, and API key in the AI settings. For OpenRouter and UnoRouter, you can load the model catalog in the app. Add a source, choose a processing profile, and start collection.
4. Open the issue in the feed. Choose a schedule in news settings and a digest language in AI settings. The interface and digests support all the languages listed above.

## Notifications and updates

Turn on new digest notifications under **Settings → Interface → Notifications** and allow them in Android. Hovq keeps a direct connection to your VPS over SSH or configured HTTPS. Android displays a silent service notification while monitoring is active. You get a separate alert when an issue is ready. If the connection drops, Hovq reconnects and checks for a new issue.

The APK installs over Hovq 0.4.5–0.5.7 signed with the same certificate. Versions 0.4.4 and earlier require a clean install. On an existing VPS, use Hovq to update the agent to 0.1.20. It checks whether differently worded reports describe the same event, even in on-demand enrichment mode. Each profile’s topic deduplication setting still applies.

## Build from source

Use JDK 17 or 21, Android SDK 36, and Build Tools 36.0.0. Set the SDK location via `ANDROID_HOME` or `android/local.properties`.

```bash
cd android
./gradlew :app:assembleRelease :app:testReleaseUnitTest :app:lintRelease
```

For a signed build, set

`HM_RELEASE_KEYSTORE`, `HM_RELEASE_KEY_ALIAS`, `HM_RELEASE_STORE_PASSWORD`, `HM_RELEASE_KEY_PASSWORD`

[App and agent source for this release](https://github.com/DEGSOL/HorizonManager/tree/v0.5.8) · [Architecture and API](https://github.com/DEGSOL/HorizonManager/blob/v0.5.8/docs/ARCHITECTURE.md) · [Testing](https://github.com/DEGSOL/HorizonManager/blob/v0.5.8/docs/TESTING.md)
