<div align="center">
  <img src="https://img.abdl-space.top/file/system/1781439303787_play_store_512.png" width="100" alt="ABDL Space 图标" />
  <h1>ABDL Space · Android</h1>
  <p>面向中文 ABDL 社区的原生客户端，基于 Moshidon（Mastodon fork）二次开发</p>
  <p>
    <img src="https://img.shields.io/badge/Release-3.2.0%20%2834%29-blue?style=flat-square" alt="正式版本 3.2.0 (34)" />
    <img src="https://img.shields.io/badge/Android-8.0%2B-green?style=flat-square&logo=android&logoColor=white" alt="Android 8.0 及以上" />
    <img src="https://img.shields.io/badge/License-GPL--3.0-red?style=flat-square" alt="GPL-3.0" />
  </p>
  <p><a href="https://abdl-space.top/app">官网下载与安装说明</a> · <a href="https://github.com/ZYongX09/ABDL-Space-APP/releases/tag/v3.2.0">3.2.0 更新日志与安装包</a> · <a href="https://abdl-space.top">社区网站</a></p>
</div>

## 项目简介

ABDL Space Android 客户端与网页端共用账户和社区数据，在 Mastodon 兼容接口之上接入纸尿裤评价、交友、私信、相册、小说阅读与社区安全等业务。它不是网页套壳，也不是只连接任意 Mastodon 实例即可使用全部扩展功能的通用客户端；业务功能需要配套的 ABDL Space 后端。

当前版本为 **3.2.0（内部版本号 34）**，最低支持 Android 8.0。普通用户可从[官网下载页](https://abdl-space.top/app)或 [GitHub Release](https://github.com/ZYongX09/ABDL-Space-APP/releases/tag/v3.2.0) 获取使用既有正式签名的安装包；完整变更见 [3.2.0 更新日志](docs/release-3.2.0.md)。

`main` 保存正式发布源码，`develop` 用于后续开发，具体版本配置见 [应用构建配置](mastodon/build.gradle)。源码合并、构建、上传安装包和更新线上版本信息是独立步骤，推送 `main` 不会自动完成官网发布。

## 关联仓库

| 仓库 | 职责 |
| --- | --- |
| [abdl-space](https://github.com/ZhX589/abdl-space) | 社区后端与业务 API |
| [ABDL-Space-V2](https://github.com/ZYongX09/ABDL-Space-V2) | 桌面网页端 |
| [abdl-space-mobile](https://github.com/ZYongX09/abdl-space-mobile) | 移动网页端，与原生 Android App 分开维护 |
| [ABDL-Space-APP](https://github.com/ZYongX09/ABDL-Space-APP) | 本仓库，原生 Android 客户端 |

仓库访问权限与服务部署权限分开管理；后端部署和网页端开发请查阅对应仓库。

## 当前功能与发布边界

以下为当前开发线中的实现范围，实际可用性还取决于后端接口、账户权限、第三方 SDK 配置和设备能力。正式用户可用版本以官网下载页及正式发布说明为准。

| 范围 | 当前源码能力 |
| --- | --- |
| 社区与纸尿裤评价 | 时间线、发帖与回复、转发、点赞、搜索、图片与视频附件、NSFW 标记，以及纸尿裤评分、榜单、百科和品牌筛选 |
| 账户与沟通 | 邮箱/密码及验证码登录、QQ 登录接入、NewBabyWorld 绑定、私信与通知、极光推送；第三方登录需匹配各平台的应用配置 |
| 相册 | 宝宝相册列表、详情、上传和浏览、相册发帖入口、邀请链接、权限与保护设置、举报及容量/原图额度处理；属于独立业务流程，不等同于普通发帖附件 |
| 小说 | 本地导入与阅读、私有云书库与同步、公共书城和创作编辑；分别依赖阅读模块、编辑模块及小说业务 API |
| 安全与界面 | TensorFlow Lite 本地 NSFW 识别、举报、宝宝认证、PIN/生物识别应用锁；传统 View 页面与 Compose/miuix 页面并存，包含液态玻璃导航与兼容性处理 |
| 应用更新 | Release 更新实现读取后端 `/api/v1/version`，提示下载并通过系统安装流程更新；生成 APK 不会自动更新该接口或官网下载页 |

### 开发线新增人机验证（未正式发布）

新版邮箱首次发送及重发验证码使用独立人机协议，由配套管理端配置关闭/按风险/每次验证，默认关闭。服务器要求验证后先显示主题一致的原生安全提示页；仅点击“开始验证”才创建居中验证弹窗并立即显示加载动画。提示页返回、弹窗关闭、初始化完成和页面加载完成均不是验证通过；只有服务端确认邮件发送成功才进入验证码页或启动重发倒计时。未知发送结果只查询，不自动重发，不回退旧接口。

配套后端已于 2026-10-28 上线：main `ab9651b`、Worker 791 承接 100% 流量，0097 已单次登记，不要重放。生产策略仍默认关闭；本仓 App 与两站管理卡仍未正式发布，旧 App/网页接口保留，不强制升级。未修改 widget/密钥/允许域名/WAF。原生请求 proof 仅进程内保存；WebView 仅固定 HTTPS 页面、origin/主 frame 限定消息桥和必要 Cloudflare 资源。TLS 错误始终取消；WebView 沿用既有 App 证书信任策略，本轮不全局调整用户 CA 行为。受控 API/Canvas 回归不能代替真实 Turnstile、邮件送达或真机验收。实现入口为 [HumanCheckActivity](mastodon/src/main/java/org/joinmastodon/android/HumanCheckActivity.java)，完整交互及开发发布边界见 [App 人机验证](docs/app-human-check.md)，服务端合同见后端仓 `docs/app-human-check.md`。

### 3.2.0 重点功能

- **交友地图**：首页地图入口、百度/高德原生地图切换、设备定位、软件级模糊位置、公开/仅互关/隐藏设置，以及指定展示位置的赞助权益控制；全国浏览不要求赞助身份。SDK 隐私同意、系统定位授权与是否公开位置是不同步骤，不能用定位授权替代公开确认；隐藏或删除位置也不能仅靠退出登录。实现入口见 [FriendMapFragment](mastodon/src/main/java/org/joinmastodon/android/fragments/discover/FriendMapFragment.java)。
- **商家/官方广告**：可选时间线广告载荷、独立广告卡片、落地页跳转及曝光/点击等事件上报。客户端展示与上报不等于具备商家投放后台，仍需服务端提供广告数据和事件接口。
- **扩列卡（功能测试版）**：提供个人扩列卡、广场、详情和发帖引用入口，让自我介绍、兴趣与社交信息更容易展示；公开字段与可见性按用户设置和后端权限处理。
- **折扇 FAB 与发帖路由**：普通模式下的小型折扇菜单提供普通发帖、交友申请、扩列卡和相册上传入口，并与液态工具栏共享业务路由。
- **赞助者感谢与界面优化**：新增赞助者感谢页，统一 PIN 输入页配色，改进宝宝新天地用户主页，并修复帖子编辑、删除、外站点赞和回复相关问题。

交友地图与扩列卡在 3.2.0 中以功能测试版本提供；实际可用性仍取决于账户权限、地图厂商授权、设备和服务端状态。宝宝新天地外站接口故障不因客户端升级而自动恢复。

## 技术栈与模块

| 项目 | 当前配置 |
| --- | --- |
| 语言与 UI | Java + Kotlin；Android View/XML、Fragment 与 Jetpack Compose、Material 3、miuix 混合使用 |
| Android | `minSdk 26`（Android 8.0+）、`targetSdk 35`、`compileSdk 37`；应用 ID 为 `top.abdl_space.app`，源码 namespace 为 `org.joinmastodon.android` |
| 构建工具 | Gradle Wrapper **8.11.1**、Android Gradle Plugin **8.9.1**、Kotlin/Compose 编译器插件 **2.4.10** |
| Java | 构建环境采用 **JDK 21**；Java 源码/目标兼容级别与 Kotlin JVM target 均为 **17**，不要混淆二者 |
| 网络与数据 | OkHttp、Gson、Room、WorkManager、Kotlin Coroutines |
| SDK | 百度地图、高德地图、QQ Open SDK、极光推送、TensorFlow Lite |
| 测试 | JUnit 4、Robolectric、MockWebServer、Compose UI 测试、AndroidX Test/Espresso |

模块以 [settings.gradle](settings.gradle) 为准：

- [`mastodon`](mastodon/)：Android 应用，名称沿用上游，包含社区、相册、交友地图、登录、私信等集成。
- [`reader-core`](reader-core/)：阅读、解析、书库数据与 Room schema。
- [`novel-editor-core`](novel-editor-core/)：小说导入解析与编辑核心。
- [`jiguang-sdk:jiguang_sdk:jiguang`](jiguang-sdk/jiguang_sdk/jiguang/)：本地极光 SDK 包装模块；其 `compileSdkVersion` 为 **30**，不代表应用最低版本为 API 21。

Gradle 参数及插件版本分别见 [gradle.properties](gradle.properties)、[Wrapper 配置](gradle/wrapper/gradle-wrapper.properties)和[根构建脚本](build.gradle)。仓库当前启用了 compile SDK 检查相关兼容设置；不要将其视为 AGP 对 API 37 的官方兼容保证，升级 SDK 或插件时应成组验证。

## Android 开发环境

1. 安装 Android Studio 或 Android SDK 命令行工具，以及 JDK 21。
2. 安装 SDK Platform **37**（应用和阅读/编辑模块）与 **30**（极光模块）、Platform Tools 和 AGP 所需 Build Tools，并接受 SDK 许可。使用仓库自带 `gradlew`，无需另装系统 Gradle。
3. 确保可访问 Google Maven、Maven Central 和 Gradle 分发服务。地图/QQ JAR、极光依赖及地图原生库也需要完整检出，不能只复制 Java/Kotlin 源码目录。

```bash
git clone --branch develop https://github.com/ZYongX09/ABDL-Space-APP.git
cd ABDL-Space-APP

# 以下路径均为占位值，请替换为自己的安装目录。
export JAVA_HOME="/absolute/path/to/jdk-21"
export ANDROID_HOME="/absolute/path/to/Android/Sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-37" "platforms;android-30"
./gradlew -Dorg.gradle.java.home="$JAVA_HOME" --version
```

仓库的 `gradle.properties` 含固定的 `org.gradle.java.home` 设置，因此下文命令显式通过 `-Dorg.gradle.java.home="$JAVA_HOME"` 指定本机 JDK；仅修改 `JAVA_HOME` 不一定会覆盖 Gradle 配置。Android Studio 的 Gradle JDK 也应指向 JDK 21。

在根目录创建被 Git 忽略的 `local.properties`；仓库没有单独的已跟踪示例文件，以下即可作为最小模板，**所有值都必须自行替换**：

```properties
sdk.dir=/absolute/path/to/Android/Sdk
# 仅构建签名 Release 时需要以下字段，勿提交真实值。
SIGNING_STORE_FILE=/absolute/path/to/your-release.keystore
SIGNING_STORE_PASSWORD=YOUR_STORE_PASSWORD
SIGNING_KEY_ALIAS=YOUR_KEY_ALIAS
SIGNING_KEY_PASSWORD=YOUR_KEY_PASSWORD
```

SDK 位置可以由 Android Studio 生成。使用自己的签名只能生成自己的包，无法替代已有正式签名或保证覆盖安装正式版。

## API 与第三方服务配置

### 后端 API

当前代码没有统一的 `API_BASE_URL` Gradle 参数或通过 `local.properties` 切换全部 API 的机制：

- 登录与会话默认使用 `https://abdl-space.top`。通用请求由 [MastodonAPIRequest](mastodon/src/main/java/org/joinmastodon/android/api/MastodonAPIRequest.java)根据会话域名构造 HTTPS URL，默认前缀为 `/api/v1`；相册与地图分别使用 `/albums`、`/map` 扩展路由。
- QQ 登录交换、徽章、公告、小说和版本检查等还有直接指向 `https://api.abdl-space.top` 的独立请求；只改登录域名并不能完成环境切换。
- 接入自己的后端时，需统一核对登录、请求类、OAuth 回调、Manifest App Links、上传/COS 地址、更新接口及对应服务端白名单。OAuth 回调使用 `abdl-space://callback`。不要将访问令牌或服务端密钥写入源码。
- `BABY_VERIFICATION_COS_HOST` 可由同名环境变量在构建时覆盖，但它只影响宝宝认证上传主机，不是全局 API 配置。

### 地图 SDK

地图密钥经 Manifest placeholder 注入。构建脚本读取顺序为 **同名环境变量优先，其次是仓库外的用户级配置文件** `~/.config/abdl-space/android-maps.env`。该文件应自行创建、限制文件权限，不要复制到仓库。文件内容示例：

```dotenv
BAIDU_MAP_ANDROID_AK=YOUR_BAIDU_ANDROID_AK
AMAP_ANDROID_KEY=YOUR_AMAP_ANDROID_KEY
```

也可以在构建进程环境中设置上述变量。默认文件路径可用 `ABDL_MAP_ENV_FILE` 或 `-PmapEnvFile=/absolute/path/to/android-maps.env` 覆盖；无需手动 `source`。这两个变量不是从 `local.properties` 读取的。

- 向各 SDK 平台申请 Android 应用凭据，并按平台要求绑定**实际包名与签名证书指纹**。Release 使用 `top.abdl_space.app`，Debug 使用 `top.abdl_space.app.debug`；不同签名和变体应分别核对授权。
- 占位值仅用于说明，不能显示真实地图。非 Release 调试构建缺少地图凭据时不会因缺钥检查失败，但地图页会显示服务未配置/设备不受支持；Release 打包默认要求**两个**密钥均已配置。
- `assertMapCredentials` 在 Release 构建前检查密钥，打包后 `verifyMapKeys` 检查 APK/AAB 的 Manifest 是否包含配置值。它不验证厂商授权、网络连通性或地图渲染是否成功。
- `-PallowMissingMapKeys=true`（或 `ABDL_ALLOW_MISSING_MAP_KEYS=true`）仅适用于明确需要无地图包的隔离验证，会跳过上述有效性保障，不应用于正式发布。
- 高德原生库当前提供 ARM ABI，注册逻辑只在 `arm64-v8a` / `armeabi-v7a` 设备上启用高德。x86 模拟器上的结果不能代替 ARM 真机地图验证。

### QQ 与推送

QQ App ID、`QQ_LOGIN_ENABLED` 和极光 Manifest 配置见 [mastodon/build.gradle](mastodon/build.gradle)，QQ 回调组件还在各变体 Manifest 中。当前并没有可用环境变量统一覆盖 QQ/极光所有参数的实现；使用自己的应用注册时需同步调整源码配置与平台后台，而不是自行添加一个无效的 `local.properties` 字段。`release`/`debug` 启用 QQ，`githubRelease`、`githubDebug`、`nightly` 等变体关闭 QQ。不要在 Issue、日志或文档中粘贴 SDK key、访问令牌或签名凭据。

## 构建与签名安全

### 签名读取规则

Release 默认使用模块目录下的 `mastodon/release.keystore`，可通过 `SIGNING_STORE_FILE` 环境变量或根 `local.properties` 同名字段指定其他位置。相对签名路径按 `mastodon` 模块解析，建议使用仓库外的绝对路径。

签名密码优先取 `SIGNING_STORE_PASSWORD` / `SIGNING_KEY_PASSWORD` 环境变量，再回退到 `local.properties`。**别名有一个当前实现细节**：`SIGNING_KEY_ALIAS` 环境变量缺省时会先采用内置默认别名，因此仅在 `local.properties` 设置别名未必生效；使用自己的证书时应显式设置该环境变量。

- 正式更新必须保留同一签名身份；不要临时生成新证书冒充正式签名，也不要把测试证书发成正式包。
- keystore、密码、地图配置、`local.properties`、服务账号 JSON 和访问令牌均应保存在受控的本地配置或 CI 密钥系统中，不能提交到仓库。
- 不要把真实密码写进命令行参数或示例；避免将完整环境、签名信息或打包后的 Manifest 输出到公开日志。APK/AAB 中的客户端 SDK key 可以被提取，仍需使用厂商包名/证书约束，不能当作服务端秘密使用。

### 本地 Release 构建

完成 SDK、地图与签名配置后，从项目根目录执行：

```bash
# 占位别名必须替换为证书中的真实别名；密码通过安全环境或 local.properties 提供。
export SIGNING_KEY_ALIAS="YOUR_KEY_ALIAS"
./gradlew -Dorg.gradle.java.home="$JAVA_HOME" :mastodon:assembleRelease --no-daemon
```

APK 输出目录为 `mastodon/build/outputs/apk/release/`。需要 AAB 时使用：

```bash
./gradlew -Dorg.gradle.java.home="$JAVA_HOME" :mastodon:bundleRelease --no-daemon
```

AAB 输出目录为 `mastodon/build/outputs/bundle/release/`。这两种打包任务均已接入地图检查；单独执行 `verifyMapKeys` 在没有产物时只会跳过，不能替代构建。构建完成后还需检查签名、版本与真机业务流程；本地构建不会上传、发布或修改线上更新信息。

## 测试与静态检查

以下命令从仓库根目录执行；Debug 单元测试任务不要求正式签名，也不需要生成应用 APK：

```bash
# 应用与两个业务库的本地单元测试
./gradlew -Dorg.gradle.java.home="$JAVA_HOME" \
  :mastodon:testDebugUnitTest \
  :reader-core:testDebugUnitTest \
  :novel-editor-core:testDebugUnitTest --no-daemon

# 示例：只运行地图与折扇菜单相关测试
./gradlew -Dorg.gradle.java.home="$JAVA_HOME" :mastodon:testDebugUnitTest \
  --tests '*MapCoordinateConverterTest' \
  --tests '*ComposeFanMenuInteractionTest' --no-daemon

# Android Lint
./gradlew -Dorg.gradle.java.home="$JAVA_HOME" :mastodon:lintDebug --no-daemon
```

本地测试包含 Robolectric、MockWebServer 和 Compose 测试。Lint 当前配置为 `abortOnError false`，因此退出成功不代表没有问题，应检查生成的报告。

设备测试使用 `screenshotsUiTest`，不是默认的 Debug 测试变体。连接测试设备或启动模拟器后可执行：

```bash
./gradlew -Dorg.gradle.java.home="$JAVA_HOME" \
  :mastodon:connectedScreenshotsUiTestAndroidTest --no-daemon
```

该命令会构建并安装测试 APK；需要相机、媒体权限或测试账户的用例应在隔离环境中准备。单元测试不能替代地图授权、定位隐私、QQ 登录、推送、签名覆盖升级及依赖服务端权益的端到端验证。

## 手动发布

**推送 `main` 不会自动发布正式包。** 现有发布定义为：

- [build_and_deploy.yml](.github/workflows/build_and_deploy.yml)：支持 `workflow_dispatch`，另保留对 `ci_setup` 分支的 push 触发；不是 `main` push 发布。
- [build_and_deploy_beta.yml](.github/workflows/build_and_deploy_beta.yml)：仅手动 `workflow_dispatch`。
- [fastlane/Fastfile](fastlane/Fastfile)：`deploy` 构建 Release AAB 并向 Google Play 上传 **draft**；`beta` 上传到 beta track。正式流程另构建 Release / GitHub Release APK 作为 Actions artifact，上传 artifact 不等于更新官网。

**上述 Actions 文件已在源码中标为弃用，不能直接当作可用的发版通道。** 恢复使用前至少需要核对：

1. 地图密钥、签名文件/密码和 Google Play 服务账号是否通过 CI 密钥系统正确提供；缺少地图密钥时工作流会提前失败。现有工作流仅在前置检查步骤设置地图变量，后续 Gradle/Fastlane 构建步骤也必须能获取它们。
2. CI 的 SDK Platform 37/30、Build Tools 和 Gradle JDK 配置是否满足当前源码要求；设置 JDK action 并不能自动消除项目中固定 JDK 路径的影响。
3. [fastlane/Appfile](fastlane/Appfile)仍使用上游包名 `org.joinmastodon.android`，与本应用的 `top.abdl_space.app` 不一致；需先核对目标 Play 应用及上传配置。
4. CI 使用 [mastodon/ci_signing.gradle](mastodon/ci_signing.gradle)中的 `KEYSTORE_FILE` / `KEYSTORE_PASSWORD` 配置，不等同于本地的 `SIGNING_*`；要确认所用别名与既有正式证书一致。

当前官网正式发布沿用已验证的 **R2 安装包 + 版本 API** 流程，不运行上述弃用 Actions，也不自动发布 Google Play：

1. 维护者明确批准后，将已验证的 `develop` 以普通 merge 或 fast-forward 合入 `main`，创建指向正式源码的版本标签，并发布 GitHub Release。
2. 将同一份正式签名 APK 上传到既有 R2 的独立版本对象，保留旧版对象。**完整回下载后逐字节核对文件大小与 SHA256**，不能只凭上传命令成功或 HTTP 200 判断完整。
3. 通过既有版本发布入口登记版本名、内部版本号、下载 URL、准确字节数和更新日志；核对版本 API、主站/移动站下载页及 GitHub 附件一致。
4. 将发布记录同步回 `develop`，保证 `develop` 包含 `main` 的全部提交；不要因补写记录重复部署后端或重新生成不同的 APK。

3.2.0 正式文件名为 `ABDL-Space-3.2.0(34).apk`，大小 **151,512,653 字节**，SHA256 为 `c214272180dd1f3ad087bf895ddb35bf27e58375921f0df189eaf0498fc4c7cc`。构建或上传成功仍不能替代真实设备的覆盖升级、地图授权、登录与业务验收；Google Play draft、beta、Actions artifact 和开发测试包也不等于官网正式上线。

## 分支与贡献

| 分支 | 用途 |
| --- | --- |
| `develop` | 标准开发分支，始终包含 `main` 的全部增量 |
| `main` | 正式版代码线；合入与发布均需明确批准 |
| `feat/*`、`fix/*` | 从 `develop` 创建，完成后合回 `develop`，再删除已合并分支 |
| `hotfix/*` | 从 `main` 创建，修复合回 `main` 后，必须将 `main` 合回 `develop` |

日常功能不要直接提交到 `main`。`develop` → `main` 不使用 squash，以免破坏 `develop` 包含 `main` 的提交关系；也不要用 reset 丢弃开发线独有提交。欢迎提交聚焦的 Issue 和 PR，并注明变更目的、验证方式及涉及的 API/SDK 配置；不要附带敏感凭据、构建产物或本地配置。

## 安装与社区规则

普通用户请从 [官网下载页](https://abdl-space.top/app)获取正式 APK，按系统提示允许相应来源安装。已有安装能否覆盖更新取决于包名、版本号与签名，不应通过替换签名绕过安装限制。

社区仅限 **18 岁及以上**用户，须遵守平台内容规则；违规内容可举报，管理员负责审核与账号处置。开发和测试同样应使用获准的数据、账户与服务。

## 许可证与致谢

本仓库按 [GNU GPL v3](LICENSE)许可发布，基于 [Moshidon](https://github.com/Moshidon/Moshidon)及 Mastodon Android 项目二次开发，保留原作者版权与许可声明。分发修改版时应遵守 GPL 对对应源码和许可证的要求。

第三方组件仍各自适用其许可证，不能将所有 SDK 都视为 GPL：阅读模块保留 [FlowReader 来源记录](reader-core/UPSTREAM.md)与[许可证](reader-core/LICENSE.flowreader)；安全域改编说明见 [2FAS NOTICE](mastodon/src/main/assets/licenses/2fas-security-NOTICE.txt)；动画图标见 [itshover NOTICE](mastodon/src/main/assets/licenses/itshover-NOTICE.txt)与 [Apache-2.0](mastodon/src/main/assets/licenses/Apache-2.0.txt)。百度、高德、QQ、极光等 SDK 的使用和分发还须遵守各厂商条款。
