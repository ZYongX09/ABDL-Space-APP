# 原生库压缩测试包（2026-10-22）

## 范围

本轮基于 develop `a5a5c79b`，在 `fix/native-apk-compression` 验证用户要求的“试试压缩”。配置提交为 `691d0af831f9a6b1bdee19c89c366a2c5d5ab178`。

仅在 `mastodon/build.gradle` 的 `android.packaging.jniLibs` 中设置 `useLegacyPackaging = true`，由 Android Gradle Plugin 正常构建、签名 APK。不是对旧 APK 手工改 ZIP 或重新签名。

保留四种 ABI、百度与高德 SDK、全部代码和资源；不启用 R8、不做 ABI 分包、不引入动态下载、不变更 SDK 或依赖版本。包名、签名、最低系统版本和 3.1.0/code33 均不变。不合入 Android main、不上传正式 APK、不更新正式下载页或版本 JSON。

## 实际体积

| 产物 | 字节 | MiB（bytes / 1,048,576） |
| --- | ---: | ---: |
| 基线 `ABDL-Space-3.1.0-code33-android8-12l-ui-test.apk` | 236,345,876 | 225.397 |
| 压缩测试包 `ABDL-Space-3.1.0-code33-native-compressed-test.apk` | 150,633,260 | 143.655 |
| 减少 | 85,712,616 | 81.742 |

实际 APK 文件缩小 **36.266%**。按十进制 MB 显示，新包为约 **150.6 MB**，避免与 MiB 混用。

29 个原生库的展开内容共 148,865,000 bytes，压缩后在 ZIP 中的数据为 63,408,647 bytes；其余减小来自打包对齐等开销变化。

## 构建与产物核验

在最新 graphics 工作树使用已有独立 Gradle home `~/.local/share/abdl-gradle-home`、Gradle 8.11.1 离线构建 `:mastodon:assembleRelease`。`-Pkotlin.incremental=false` 下生产 Kotlin 编译重新执行；构建耗时约26分钟，未清理缓存或修改机器配置。`assembleRelease`、`verifyMapKeys` 均通过。现有 Gradle/Kotlin 弃用和资源警告保留，不宣称全项目无警告。

实际核验：

- top.abdl_space.app，3.1.0/code33，minSdk26、targetSdk35、compileSdk37，非 debug。
- 既有正式证书 SHA-256 `fd2098a3d3493222c247a7ba2787ec48fd8f62d38e9432ed4d6290a057dea475`，APK V2 签名验证通过。
- arm64-v8a、armeabi-v7a、x86、x86_64 全部保留；29个 `.so` 均为 DEFLATE。
- 对照基线逐成员 SHA-256：两包均28,508项，无新增或删除；除 `AndroidManifest.xml` 外，其余28,507项展开内容完全一致，包括所有 DEX、资源、assets 和原生库。
- 二进制 Manifest 解码后仅 `extractNativeLibs=false` 改为 `true`，没有其他语义变化。未在日志中输出地图密钥。
- 全部 ZIP CRC 和 `zipalign -c -v 4` 检查通过。
- 本轮未重跑单元/Robolectric测试；构建和包内容一致性检查不等于原生运行时验收。

校验脚本最初不兼容 build-tools37 的 `V2 Signer:`、`=true` 输出格式，两次校验中止均是输出解析问题；修正解析后完整核验通过，未改变 APK 或放宽预期证书/提取要求。

## 交付文件

正式签名 release 测试包：

`/home/ZYongX/projects/ABDL-Space-3.1.0-code33-native-compressed-test.apk`

SHA-256：

`66d773e9d92a36428a336bf6801d7a74ee17da839b07f0ab8a31dd1f30b1622c`

原基线 APK 保留。测试包不提交进仓库。由于仍为 code33，现有更新器不会把它作为更高版本自动提示；此次不是正式发版。

仓外可复核证据：`/home/ZYongX/projects/abdl-native-compression-evidence-2026-10-22/`，包含 assemble-release.log、verify-apk.py、apk-verification.json、native-library-hashes.json、签名/对齐结果和 release-artifact.json。

## 安装与运行边界

预检和设备列表确认没有连接的 Android 真机或可用模拟器，因此**未安装、未验证地图原生库实际加载、未测安装时长或系统最终存储占用**，也未覆盖用户设备。

原生库压缩后由系统在安装时抽取匹配设备架构的库，不再直接映射 APK 中未压缩的库。解压后的库大小不变，不能把 APK 文件缩小36.266%等同于安装空间也同比减小。

保留原始 `.so` 字节不代表替供应商确认了16KB页兼容性，ZIP压缩也不会修复库内部的 ELF/运行时兼容问题。本轮没有更换这些库。

真机验收仍需覆盖：原签名覆盖安装、冷启动、百度/高德分别打开与切换、前后台切换、地图拖动与玻璃采样，以及其余原生库调用。只有完成对应运行验证后，才能认定压缩方案在该设备上可用。
