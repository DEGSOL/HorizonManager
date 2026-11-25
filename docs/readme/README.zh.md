# Hovq 0.5.5

[Русский](../../README.md) · [English](README.en.md) · [Deutsch](README.de.md) · [Français](README.fr.md) · [Italiano](README.it.md) · [Español](README.es.md) · [日本語](README.ja.md) · [한국어](README.ko.md) · **简体中文** · [Қазақша](README.kk.md) · [Татарча](README.tt.md)

Hovq 是一款 Android 应用，用于在自己的 VPS 上安装和管理 [Horizon](https://github.com/Thysrael/Horizon)，并阅读新闻摘要。Horizon 在服务器上收集文章、删除重复内容，再使用你选择的 AI 模型评分、翻译并生成每期摘要。

[下载已签名的 APK 并查看发布说明](https://github.com/DEGSOL/HorizonManager/releases/latest)

## 功能

- 支持 RSS、Telegram、Reddit 和 Hacker News，提供评分配置、封面图片和按需扩展文章。
- 支持手动运行、按星期运行，或从指定时间开始按固定间隔运行。计划任务在 VPS 上执行。
- 信息流支持搜索、筛选和未读标记，还有收藏、归档及已下载摘要的离线阅读。
- 提供操作日志，以及手机端和服务器端设置的加密备份。

## 安装与生成首期摘要

1. 在 Android 8.0 或更新版本上安装 APK。准备运行 Ubuntu Server 24.04 LTS 或 Debian 12 的 VPS，支持 systemd 和 SSH，并能访问系统软件源、GitHub 和 PyPI。安装程序会检查架构、至少 432 MiB 的内存和 1.5 GiB 的可用磁盘空间。收集和处理可能需要更多资源。
2. 在 Hovq 中添加服务器，输入 SSH 密码或选择私钥，并在服务商控制台核对指纹。SSH 必须允许转发到 VPS 上的 `127.0.0.1:18777`。确认安装，必要时输入 sudo 密码。也可以直接连接已安装的代理。
3. 在 AI 设置中指定提供商、模型和 API 密钥。OpenRouter 和 UnoRouter 的模型目录可在应用中加载。添加来源、选择处理配置，然后开始收集。
4. 在信息流中打开摘要。在新闻设置中选择运行计划，在 AI 设置中设置摘要语言。界面和摘要支持上方列出的所有语言。

## 通知与更新

在 **设置 → 界面 → 通知** 中开启新摘要通知，并允许 Android 发送通知。Hovq 会在计划运行时间附近和摘要生成期间通过 SSH 或 HTTPS 短暂连接 VPS。摘要完成后才会显示通知。应用不会运行常驻服务。Android 可能推迟后台检查，尤其是在设备深度休眠时。

APK 可覆盖安装证书相同的 Hovq 0.4.5–0.5.4。0.4.4 及更早版本需要全新安装。在已有的 VPS 上通过 Hovq 将代理更新至 0.1.17，即可用一次请求检查计划和摘要状态。

## 从源码构建

需要 JDK 17 或 21、Android SDK 36 和 Build Tools 36.0.0。通过 `ANDROID_HOME` 或 `android/local.properties` 指定 SDK 路径。

```bash
cd android
./gradlew :app:assembleRelease :app:testReleaseUnitTest :app:lintRelease
```

构建已签名的 APK 时，请设置以下环境变量

`HM_RELEASE_KEYSTORE`, `HM_RELEASE_KEY_ALIAS`, `HM_RELEASE_STORE_PASSWORD`, `HM_RELEASE_KEY_PASSWORD`

[此版本的应用与代理源码](https://github.com/DEGSOL/HorizonManager/tree/v0.5.5) · [架构和 API](https://github.com/DEGSOL/HorizonManager/blob/v0.5.5/docs/ARCHITECTURE.md) · [测试](https://github.com/DEGSOL/HorizonManager/blob/v0.5.5/docs/TESTING.md)
