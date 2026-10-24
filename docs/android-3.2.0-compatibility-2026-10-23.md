# Android 3.2.0 / 34 兼容性复核与测试包

> 本文记录最初的测试包交付边界。用户后续批准正式发布，同一安装包改名为 `ABDL-Space-3.2.0(34).apk`，字节与SHA256不变；正式更新日志和发布规则见 [3.2.0发布说明](release-3.2.0.md)。下文“main未更新/未发布”等为当时状态，不代表后续正式发布结果。

## 范围

用户要求版本名改为 3.2.0、内部号 34，最后检查修复低版本 Android 兼容性并重新打包。本轮保持 minSdk 26（Android 8.0）、targetSdk 35、compileSdk 37、既有正式签名和四 ABI，不更改正式下载、更新 JSON 或 Android main，不生成 debug APK。

后续补充的“已同步原生帖首次删除仅远端成功、本地仍在”由后端独立修复确认持久化与本地重试；Android 不把 HTTP 500 当作成功，也不在服务端未确认本地删除时伪造列表移除。

## 已完成源码检查与修改

- `mastodon/build.gradle`：versionName 3.2.0、versionCode 34；API 身份请求头继续由 BuildConfig 生成，新增明确版本断言。
- `OAuthActivity`：标准 code 登录回调在原瞬态认证上下文丢失时已创建 finalInstance/finalApp，但成功后仍把原可空变量传给 addAccount，造成条件性空指针；改为使用本次流程解析出的 finalInstance/finalApp。不修改 NBW token 登录、绑定 nonce、锁屏门禁、认证端点或客户端凭据持久化。
- 新增 API 26/27/28 的 OAuth 冷/热上下文回调回归，模拟传输、账号写入和已解锁测试边界，不使用真实账号。此测试不证明服务端会接受丢失客户端凭据后的 code exchange。
- StatusLifecycleHttpTest 与 NBWProfileBehaviorTest 扩展至 API 26/27/28；DELETE 的 500/502/503 仍严格失败。
- 新增 API 26/27/28 的本地 NBW 头像软件解码、尺寸/处理顺序、来源标志 1em 测试，以及真正 Parcel/Bundle 序列化的帖子同步与编辑源字段回归。

对图形、新版 API 与 Java 库进行只读复核：RenderEffect 路径有 API31 门槛，RuntimeShader 与液态导航有 API33 门槛，低版本保留原有不透明回退。未发现需凭猜测重写的高置信新增图形 API 崩溃。实际 AGP8.9.1 内置 R8 BackportedMethodList 在 min-api26 包含 String.isBlank/strip，因此没有把已回移的方法误判为低版本缺失。相机、定位、媒体权限和地图回退纳入既有回归。

## 本轮回归验证

最终同源码、同次 release 单元回归 **45类408项全部通过，0失败/错误/跳过**，Robolectric仅启用API26/27/28。涵盖登录冷/热回调、NBW头像/来源与主页、Parcel与编辑源、发帖/编辑/删除HTTP契约、线程事件与通知、玻璃回退、折扇菜单、地图门禁、相册选择/上传、相机/窗口和定位。初轮408项中新增OAuth fixture因未加载GlobalUserPreferences在主题入口失败6项；补齐与生产相同的测试初始化后6/6再过，随后整组408再次完整通过，不把早期部分结果拼成最终计数。

API33/35专属的硬件位图与地图图标两类未纳入本次低版本范围，不修改其测试断言。桌面原生Canvas/软件回退与Robolectric不等同老真机验收。只读API lint曾因宿主中断/换页未完成，不能据旧report宣称本轮lint全绿；源码SDK门槛和实际R8回移另行复核。正式签名测试包已完成构建与核验，见下方交付信息。

首个只读 API lint 构建在宿主中断后重新执行；第二次非增量生产 Kotlin 已完成并输出全部类，Java 阶段受到本机换页压力显著拖慢。为降低峰值，终止该构建并将后续 Java/测试/打包分阶段运行；未删除缓存。仅在确认所有 Kotlin 源文件 hash 与该非增量输出输入一致后，仓外 init 通过 task.onlyIf 保留生产 Kotlin 任务节点、复用已完成输出，生产 Java 与新增测试仍重新编译。

仓外证据目录：`/home/ZYongX/.local/share/abdl-space-release/20261023-android-3.2.0/`。

## 最终交付

- APK：`/home/ZYongX/projects/ABDL-Space-3.2.0-code34-compat-test.apk`。
- 文件大小：151,512,653 bytes。
- SHA256：`c214272180dd1f3ad087bf895ddb35bf27e58375921f0df189eaf0498fc4c7cc`。
- 包名top.abdl_space.app，3.2.0/code34，min26/target35/compile37，非debug。
- 既有正式证书SHA256 `fd2098a3d3493222c247a7ba2787ec48fd8f62d38e9432ed4d6290a057dea475`，V2签名、ZIP CRC及zipalign通过；verifyMapKeys通过。四ABI/29个原生库均DEFLATE且与上一包字节相同。
- 对应功能源码提交fb0cf31a；后续仅更新交付文档，不改变已测源码与APK。Android develop已合并推送，正式main仍ed555e85；不改正式下载/更新JSON、不覆盖设备，旧包保留。
- 后端删帖确认持久化/仅delete_thread有界幂等确认回归51/51，通过后已推develop f2f5905，但main仍acb3fc2，未上线；安装此APK不会自动上线该修复。生产首次删除的确切失败原因尚无日志证明。

完整构建成功；初次打包仓外init脚本过晚修改lint.checkOnly失败，纠正该仓外脚本后重跑成功，项目配置未受此影响。D8仍报告既有工具链对较新Kotlin metadata的兼容警告（SplashActivityKt/AboutPageKt），非零错误未被忽略；本轮未升级AGP/Kotlin，不宣称工具链零警告。签名/完整性/版本/原生库核验已完成，物理低版本设备验收仍见下方边界。

## 验证边界

本机桌面模拟器后端不支持当前 Linux，且未配置 AVD。可连接设备为 Android 16/API36，不是低版本验收设备。本轮不覆盖安装用户设备、不读取 token/PIN；低版本验证使用 Robolectric SDK26/27/28 等回归与 API 检查，不冒称物理 Android8/9 已验收。NBW 实际 get_replies503 属外站接口问题，本轮 App 升版不等于外站评论恢复。
