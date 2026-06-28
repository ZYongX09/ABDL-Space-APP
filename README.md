<div align="center">
  <img src="https://img.abdl-space.top/file/system/1781439303787_play_store_512.png" width="100" />
  <h1>ABDL Space</h1>
  <p>Android 客户端 · 基于 Moshidon（Mastodon fork）二次开发</p>
  <p>
    <img src="https://img.shields.io/badge/Version-1.0.5-blue?style=flat-square" />
    <img src="https://img.shields.io/badge/Android-8.0%2B-green?style=flat-square&logo=android&logoColor=white" />
    <img src="https://img.shields.io/badge/License-GPL--3.0-red?style=flat-square" />
  </p>
  <p>
    <a href="https://abdl-space.top/app"><img src="https://img.shields.io/badge/下载最新版-89CFF0?style=for-the-badge&logo=android&logoColor=white" /></a>
  </p>
</div>

---

## 这是什么

ABDL Space 是一个基于 Mastodon 协议的 Android 社区客户端，面向 ABDL 爱好者群体。

网页端 [abdl-space.top](https://abdl-space.top) 和 App 共用同一套账户体系，数据互通。App 端额外提供了原生推送通知、NSFW 图片本地识别、纸尿裤评分系统等功能。

## 功能

**社区**
发帖、回复、转发、点赞、图片上传、NSFW 标记。发链接自动生成预览卡片（B站、YouTube、微博等）。标签搜索、用户搜索、帖子搜索。

**纸尿裤系统**
评分、排行榜、百科、品牌库。按类型/尺码/品牌筛选，社区共同维护数据。

**私信 & 通知**
一对一实时私信。点赞、回复、关注、私信均触发推送通知（JPush）。

**安全**
NSFW 图片通过 TensorFlow Lite 本地识别，图片不上传服务器。支持内容举报和管理员审核。

**自动更新**
App 启动时检查后端版本接口，有新版提示下载安装。

## 技术栈

| | |
|---|---|
| 语言 | Java（无 Kotlin / Compose） |
| UI | Android View + XML 布局，Fragment 导航 |
| 构建 | Gradle 8.5，AGP 8.x，compileSdk 35，minSdk 24 |
| 网络 | OkHttp + Gson |
| 推送 | JPush（极光推送） |
| AI | TensorFlow Lite（NSFW 本地识别） |
| 后端 | Hono + Cloudflare Workers + D1（独立仓库） |

## 构建

需要 JDK 17 和 Android SDK（API 35）。

```bash
git clone https://github.com/ZYongX09/ABDL-Space-APP.git
cd ABDL-Space-APP
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :mastodon:assembleDebug --no-daemon
```

APK 产物在 `mastodon/build/outputs/apk/debug/`。

Release 构建需要签名文件 `mastodon/release.keystore`。

## 安装

直接从 [官网下载页](https://abdl-space.top/app) 下载 APK 安装即可。安装时需要允许「安装未知来源应用」。

## 参与

欢迎提 Issue 和 PR。Fork 后从 `develop` 分支建功能分支，改动聚焦，PR 里说清楚改了什么、为什么改。

## 平台规则

- 仅限 18 岁以上用户
- 违规内容零容忍
- 管理员保留审核和处置账号的权利

## 许可证

[GPL-3.0](LICENSE)

基于 [Moshidon](https://github.com/Moshidon/Moshidon) 二次开发，原项目版权归 Moshidon 团队。
