<div align="center">

<img src="https://img.abdl-space.top/file/system/1781439303787_play_store_512.png" height="96" alt="ABDL Space" />

# ABDL Space

**安全私密的 ABDL 社区客户端**

<sub>基于 Mastodon 协议构建，为 ABDL 群体打造的移动社交平台：帖子、评分、推荐、私信与社区。</sub>

<p>
  <a href="README.md">简体中文</a>
</p>

<p>
  <img src="https://img.shields.io/badge/Version-1.0.5-89CFF0?style=flat-square&labelColor=ffffff" alt="Version 1.0.5" />
  <img src="https://img.shields.io/badge/Android-8.0%2B-34C759?style=flat-square&logo=android&logoColor=white" alt="Android 8.0+" />
  <img src="https://img.shields.io/badge/Java-100%25-FF3B30?style=flat-square&logo=openjdk&logoColor=white" alt="Java" />
  <img src="https://img.shields.io/badge/License-GPL--3.0-FF3B30?style=flat-square" alt="GPL-3.0" />
  <img src="https://img.shields.io/github/stars/ZYongX09/ABDL-Space-APP?style=flat-square&color=FF9500&labelColor=ffffff" alt="Stars" />
</p>

<p>
  <a href="https://abdl-space.top/app">
    <img src="https://img.shields.io/badge/Download-最新版本-89CFF0?style=for-the-badge&labelColor=ffffff" alt="Download latest release" />
  </a>
</p>

<sub>README 更新：2026-06-28 · 当前构建版本以 mastodon/build.gradle 为准</sub>

</div>

---

## 项目简介

ABDL Space 是基于 Moshidon（Mastodon Android 客户端）二次开发的 Android 应用，专为 ABDL 群体打造一个安全、私密、无干扰的社区空间。采用 Java 传统 View 体系构建，无 Kotlin、无 Compose，保持原生体验。

- **Mastodon 协议**：基于 ActivityPub，支持联邦网络，数据自主可控。
- **ABDL 专属社区**：18+ 专属平台，内置纸尿裤评分、推荐系统与社区排行。
- **移动端原生体验**：Fragment 导航、XML 布局、原生 Android View，丝滑交互。
- **自托管后端**：基于 Hono + Cloudflare Workers + D1，轻量高效。
- **隐私优先**：用户数据仅存储在自托管后端，不经过第三方。

## 真机预览

<div align="center">
  <sub>截图待补充</sub>
</div>

## 下载与安装

| 项目 | 说明 |
| --- | --- |
| 最新版本 | [官网下载](https://abdl-space.top/app) |
| 系统要求 | Android 8.0+ / API 24+ |
| 推荐系统 | Android 12+，可获得完整的 Material 3 主题体验 |
| CPU 架构 | arm64-v8a、armeabi-v7a、x86、x86_64 |
| 登录方式 | 账号密码 / 宝宝新天地 OAuth / 网页 OAuth |

安装 APK 时需要在系统设置中允许「安装未知来源应用」。

> [!TIP]
> App 内置自动更新检测，启动时会检查是否有新版本，提示用户更新。

## 核心功能

| 模块 | 功能 |
| --- | --- |
| 帖子系统 | 发帖、回复、转发、点赞、图片上传、NSFW 标记、链接预览卡片 |
| 社区发现 | 公开时间线、标签搜索、用户搜索、帖子搜索 |
| 纸尿裤评分 | 评分系统、排行榜、纸尿裤百科、品牌库 |
| 个人主页 | 资料编辑、头像/封面、帖子列表、关注/粉丝 |
| 私信系统 | 一对一私聊、实时消息 |
| 通知中心 | 点赞、回复、关注、私信通知，JPush 推送 |
| 安全防护 | NSFW 内容识别（TensorFlow Lite）、内容举报、管理员审核 |
| 账户管理 | 多账户切换、宝宝新天地绑定、OAuth 登录 |
| 网页同步 | 与网页端 [abdl-space.top](https://abdl-space.top) 共享账户体系 |

## 技术栈

| 类别 | 选型 |
| --- | --- |
| 语言 | Java |
| UI 框架 | Android View + XML 布局、Fragment 导航 |
| 网络 | OkHttp、Gson |
| 推送 | JPush（极光推送） |
| AI/ML | TensorFlow Lite（NSFW 内容识别） |
| 构建 | Gradle 8.5、AGP 8.x、JDK 17 |
| SDK | compileSdk 35、minSdk 24、targetSdk 35 |
| 签名 | 自定义 release keystore |

## 后端技术栈

| 类别 | 选型 |
| --- | --- |
| 框架 | Hono（TypeScript） |
| 运行时 | Cloudflare Workers |
| 数据库 | Cloudflare D1（SQLite） |
| 存储 | Imgbed 图床 |
| API | Mastodon API v1 兼容 |

## 项目结构

```text
ABDL-Space-APP/
├── mastodon/                    # Android 主模块
│   ├── src/main/
│   │   ├── java/                # Java 源码
│   │   │   └── org/joinmastodon/android/
│   │   │       ├── fragments/   # Fragment 页面
│   │   │       ├── ui/          # UI 组件、Activity
│   │   │       ├── api/         # API 请求
│   │   │       └── model/       # 数据模型
│   │   ├── res/                 # 资源文件（布局、drawable、values）
│   │   └── AndroidManifest.xml
│   ├── build.gradle             # 构建配置
│   ├── proguard-rules.pro       # 混淆规则
│   └── release.keystore         # 签名密钥
├── jiguang-sdk/                 # 极光推送 SDK
├── settings.gradle
├── AGENTS.md                    # 开发规范（本地）
└── LICENSE
```

## 构建

```bash
# 环境要求：JDK 17、Android SDK（API 35）
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :mastodon:assembleDebug --no-daemon
```

构建产物位于 `mastodon/build/outputs/apk/debug/`。

> [!NOTE]
> Gradle 8.5 需要 JDK 17（不支持 JDK 21+）。release 构建需要 `mastodon/release.keystore` 签名文件。

## 关联项目

| 项目 | 仓库 | 说明 |
| --- | --- | --- |
| 前端网页 | [ABDL-Space-V2](https://github.com/ZYongX09/ABDL-Space-V2) | Vite + React，主站 |
| 移动端网页 | [abdl-space-mobile](https://github.com/ZYongX09/abdl-space-mobile) | 移动端 Web 版 |
| 后端 API | [abdl-space](https://github.com/ZhX589/abdl-space) | Hono + Cloudflare Workers |

## 分支模型

| 分支 | 用途 |
| --- | --- |
| `develop` | 功能开发、bug 修复，日常开发分支 |
| `main` | 稳定发布版，仅从 develop 合并 |

发布流程：develop → 构建 release 包 → 用户测试 → 合并到 main → 打 tag

## 许可证

[GPL-3.0 License](LICENSE)

基于 [Moshidon](https://github.com/Moshidon/Moshidon) 二次开发，原项目版权归 Moshidon 团队所有。

---

<div align="center">

<sub>ABDL Space © 2026</sub>

</div>
