# 移除图形运行异常门禁（3.0.1 / code32 不变）

用户在3.0.1正式发布后明确要求移除全部“异常保护门禁”。范围为液态导航、动态背景和页面模糊，非登录/认证/权限安全控制；以main `f8c510fa` 为基线。

## 行为变更

- 删除 NAVIGATION/BACKGROUND/PAGE_BLUR 运行失效状态、失败上报/监听、主线程关闭通知及全进程熔断。
- 移除图形 RuntimeException/LinkageError/OutOfMemoryError 转静态fallback的catch；系统支持时真实异常原样传播，不再永久关闭效果或自动回经典。
- 移除safeBackdrop节点disabled-attach/enable委托包装及节点链数量假设，直接使用库factory(true)原始Modifier链；recording也直接接入原始库节点。
- 删除设置“液态玻璃未能安全工作”的subtitle/弹窗及中英文文案；低性能开启确认与用户选择保留。
- Home创建/更新/释放图形controller不再以catch转换为会话关闭；用户主动关闭液态效果仍恢复经典布局。
- 保留API33系统能力边界（应用minSdk26不变）、真实硬件窗口前置条件、软件截图帧不绘RuntimeShader、16MiB捕获预算/有界采样、finally Canvas恢复与drawable恢复/资源释放。这些是兼容与资源约束，不是异常后的效果禁用状态。
- 捕获预算不足仅停止该捕获，不宣布整个设备/会话不支持；实际绘制/分配/图片转换错误完成必要恢复后原样抛出。

## 重要边界

移除异常转换后，真正的图形库或驱动Java/Kotlin异常可能导致闪退，不承诺此方案更稳定；native/RenderThread崩溃本来也无法被这些catch保护。不可把不再出现工作失败提示当作GPU效果已验证。

本轮保持3.0.1/code32，原签名不变，独立构建no-graphics-fuse包。不覆盖已发布immutable对象 `apk/ABDL-Space-3.0.1.apk`，不更新线上metadata，不轮换密钥或安装覆盖手机账号数据。当前无在线ADB设备，真机升级/GPU视觉验收无法执行。

## 验证结果

- 最终集中focused **364次执行全部通过，0 failures/errors/skipped**，包括API26–35偏好/系统路径、真实设置行和M3Switch交互、真实miuix单/双节点direct enabled生命周期、异常直传和Canvas恢复、捕获27项资源恢复/预算/重试、评论行为与HTTP契约、私信标题和版本上报。
- 首次测试编译遇到Kotlin factory返回Unit而预期Modifier，修正测试抛AssertionError；首轮运行362/364通过，唯一两失败是Compose重组夹具错误要求新建LayerBackdropElement保持identity。将fixture库Modifier以remember保留后32项真实链通过，随后完整364项重跑全绿。没有放宽生产异常直传或SDK门禁。
- Debug Kotlin/Java编译与`:mastodon:assembleRelease`通过；本轮非全仓测试/完整Lint/完整安全审计，不宣称既有QQ资产契约失败已修。
- 只读复核发现软件窗口重新attach可能留下旧Liquid controller，修为调用经典恢复但不改用户选择、不上报、不锁定会话。
- 当前无在线ADB设备/可用模拟器，未执行真机覆盖安装、GPU视觉或真实评论端到端验收。

## 独立交付

`/home/ZYongX/projects/assets/releases/3.0.1-32-no-graphics-fuse/ABDL-Space-3.0.1-32-no-graphics-fuse.apk`，86,603,637 bytes。SHA-256 `54efcf4f89df925af10d9791313847afcfcbbb2697adef9e8c71ac506c8d9a2c`。

APK本体versionName3.0.1/versionCode32/minSdk26/targetSdk35，非debug；v2签名验证通过，原证书SHA-256 `fd2098a3d3493222c247a7ba2787ec48fd8f62d38e9432ed4d6290a057dea475`。旧graphics/comments/backdrop及线上正式包保留不覆盖，no-graphics-fuse仅文件名后缀。验证日志和XML在交付目录validation中保存。

代码从main建立 `fix/3.0.1-no-graphics-fuse`，验证后提交推送并整合到recovery；main和线上版本不在本轮修改范围。已经安装code32的设备不能通过versionCode比较自动发现此同版本包。
