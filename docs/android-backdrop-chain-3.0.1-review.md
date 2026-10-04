# 液态玻璃真实背景节点链修复（3.0.1 / code32 不变）

基线为 recovery `31fe1ff3`，保留液态玻璃/关于背景/私信标题/评论两轮修复。

## 真机根因

2026-10-04 在线 USB 设备（型号2409BRN2CC）日志记录：

```text
12:31:43.347 NAVIGATION disabled for this session: navigation pill shaders
java.lang.IllegalStateException: Expected one backdrop node
  at GraphicsSafetyKt.safeBackdropEffect (GraphicsSafety.kt:139)
  at IosLiquidGlassNavigationBar (IosLiquidGlassNavigationBar.kt:436)
```

这是应用安全包装器的错误断言，不是 GPU 不支持、低内存或 shader 编译失败。miuix-blur 0.9.3 的实际 `drawBackdrop` 在存在 `layerBlock` 时先追加 `graphicsLayer`，再追加 `DrawBackdropElement`。导航 pill 与拖动 indicator 均使用该组合；先前只允许一个元素的包装器在构造时抛异常，随后 NAVIGATION 门禁锁定整个进程。

之前Probe生命周期测试只覆盖单节点，未包含真实库的多节点返回，所以未发现这个组合错误。

## 最小修复

- 保留 drawBackdrop 的完整前缀节点及原顺序（包含 layerBlock 的缩放/变换），只包装末尾真正的背景效果节点。
- 比较disabled/enabled两条链的长度及对应元素类型，保持库内部层次结构；不按lambda对象相等判断合法性（构造两次可产生不同回调对象）。仅用于已核对的drawBackdrop/textureBlur末端效果，不推广成任意节点生命周期包装器。
- 背景节点仍先disabled attach，再受保护enable/update；可恢复错误回退、Canvas恢复、资源清理和系统API33下限仍保留。
- 真实图形层/委托节点显示列表可以在外层绘制前独立重录，所以将绘制保护放到graphicsLayer前缀内侧，并由SafeBackdropNode自身实现DrawModifierNode，显式在安全边界内调用末端真实节点draw；仅新增外部drawWithContent仍会被独立显示列表更新绕过。真实onDrawSurface错误因此能被捕获、恢复Canvas并同帧回退。
- 不删异常保护总门禁，不清除真实失败状态，不改变捕获内存预算或用户性能提示。本轮仅修真机证实的节点组合bug。
- versionName仍3.0.1/versionCode仍32；正式包独立backdrop文件后缀，不覆盖之前comments或graphics包。

## 验证边界

读设备日志不等于修复后真机验收；本轮只读取已安装3.0.1/code32的日志与版本（真机API36），未覆盖安装或重启用户已登录应用，不声称完整首页shader/GPU效果已在真机恢复。本轮不发布线上APK元数据。

Robolectric 4.14.1 对API33/34的标量RuntimeShader uniform更新为no-op，resolution/alpha像素因此未定义；本轮复验遇到API33非透明断言在两个方法间不稳定失败。测试保留API33 shader构造、uniform调用、绘制无异常和域隔离，但有意义的非透明像素及动画差异只在API35原生uniform路径断言；未为此修改生产shader或设备门禁。

## 回归结果

- 近期全部功能focused回归294次通过（0 failures/errors/skipped），包含本轮真实miuix节点链API33/35共12次、新旧评论18次、图形/私信/设置222次及其它发布/版本契约。
- 真库双节点构造、factory新lambda、pillar/CombinedBackdrop indicator真实attach/update/detach/remount、单节点路径通过。故障注入首次发现真实委托draw可绕过外层边界，修复为节点内显式draw后12次通过。
- 后续移除节点内外重复回退，防止半透明indicator同帧叠加两次。Compose一次View.draw可以包含重录和播放多轮draw，所以不把调用次数当作视觉叠加次数；最终断言半透明红fallback输出alpha精确128（不是重复叠加的192），并核对Canvas恢复、后续不再进入真实效果及可移除。最终安全边界34次（真实链12+基础9+生命周期13）全通过，Release构建通过。
- 本轮未重跑全仓测试，既有QQ资产静态格式契约失败仍保留上一轮记录，不宣称全仓Lint/安全审计通过。
- 正式包保留此前所有功能且保持3.0.1/code32，独立保存避免覆盖旧包。

## 最新交付

`/home/ZYongX/projects/assets/releases/3.0.1-32-backdrop/ABDL-Space-3.0.1-32-backdrop.apk`，86,621,157 bytes。SHA-256 `364069587871a31930efb24cbc443e4ea6e21b993ab28af55ce9021e07ed63b4`。

APK本体versionName3.0.1/versionCode32/minSdk26/targetSdk35、非debug；v2签名通过，原证书SHA-256 `fd2098a3d3493222c247a7ba2787ec48fd8f62d38e9432ed4d6290a057dea475`。backdrop仅文件名后缀，之前comments包和graphics包保持原哈希，不覆盖。日志与XML在交付目录validation下保存。

修复分支 `fix/3.0.1-backdrop-chain` 完成后合入并推送 `recovery/20260925`；main与线上下载不变。没有对真机覆盖安装或重启，本轮修复后真机视觉/GPU验收未做。
