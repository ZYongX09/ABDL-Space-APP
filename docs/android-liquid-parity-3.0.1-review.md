# 液态玻璃采样恢复与旧版视觉一致性修复（3.0.1 / code32）

用户在门禁前后只读对比后要求修复。基线recovery `3352159d`，参考门禁前 `7f9c1656`，不整体回滚App，不恢复运行异常熔断，不改评论/认证或版本。

## 修复范围

- 预算暂停不再清空捕获listener或永久停止：保留同一回调，清理局部资源，首次暂停通知null背景以清除consumer旧图；后续真实内容/尺寸/高度变化可以自动恢复，避免每帧重试和重复清图通知。
- 软件截图Canvas仅跳过本帧捕获，不破坏后续硬件帧捕获状态。
- 新硬件图片软件副本在采样选择前计入预算，使用bitmap身份去重；只遍历实际可见且可能参与采样的子树，防止隐藏tab/无关图片消耗预算。保留16MiB、8192维度、有界最多1/4中间采样及实际allocation核验。
- Home与两个controller接受nullable背景交付，ViewBitmapBackdrop支持清图再更新；不存在最后旧图继续显示的问题，用户选择和已注册捕获状态不被误关闭。
- 导航pill恢复drawBackdrop/layerBlock直接作用于可见Row，按压缩放包含图标与文字；隐藏tabs capture恢复背景绘制在水平4dp padding之前，玻璃范围不再少8dp。
- Morphing恢复drawBackdrop在内容clip之前，玻璃/高光不再被新增外层clip提前裁剪。
- 保留API33前非shader路径、低性能开启提醒、finally资源恢复和异常直传。当前已移除的异常门禁不重新引入。

## 验证与交付边界

本轮无在线USB设备或可用模拟器，不能声称恢复后的真机GPU/视觉已验证。测试应覆盖真实nativeCanvas预算暂停/同callback恢复、新副本选择低分辨率、hidden/非采样图片、nullable消费者真实绘制，以及Compose布局几何/变换/裁剪顺序与现有菜单交互。离线测试不替代像素级厂商GPU对照。

版本保持3.0.1/code32，独立liquid-parity后缀签名包不覆盖前轮包或线上immutable对象，不部署或更新线上metadata。代码验证后提交推送并整合到recovery，main/线上不在本轮发布范围。

## 实际验证结果

- 最终集中focused **396次全部通过，0 failures/errors/skipped**，含捕获45次、真实Compose布局8次及源码接线4次、nullableconsumer绘制2次、评论及此前保留功能回归。
- 预算恢复使用同callback，不手动重注册；指定1440×3200+1080²硬图反例成功按2x中间采样规划。验证预算暂停仅首次null通知、无定时自旋、尺寸/高度/真实invalidated自然恢复、softwareframe后硬件继续、隐藏/GONE/central图片不copy、scroll/clip/变换边界和generation。
- 复核补充修复central图片屏蔽时的requestLayout循环：用保留原intrinsic/min/bounds的no-draw占位，不用null drawable。实际requestLayout计数及静止5轮capture验证通过；真实尺寸变化仍触发布局。
- 视觉几何测试在API33/35覆盖可见图标/标签按压0/0.5/1/0进度缩放、LTR/RTL及density1/2全宽/内tabs宽、内容clip不裁剪外层surface软件像素。空effects真实库节点与软件draw seam不代表真GPU高光/折射像素验收。
- 初轮失败包含旧source stop断言、fixture缺显式draw、320px窗口夹400px与PixelCopy超时，已按新行为和真实软件View.draw修正；占位补充两fixture失败分别为normaldraw前bounds未初始化、祖先host默认clipChildren，修正夹具隔离后全部通过。未为此放宽生产预算或恢复门禁。
- Debug编译与`:mastodon:assembleRelease`通过；本轮非全仓测试/完整Lint/完整安全审计，既有QQ资产契约失败未扩大处理。

## 独立签名交付

`/home/ZYongX/projects/assets/releases/3.0.1-32-liquid-parity/ABDL-Space-3.0.1-32-liquid-parity.apk`，86,603,637 bytes，SHA-256 `e1fe49a71e495044e3de34731c5d479bba5495ded016073690ade48e6d72d965`。

APK本体仍versionName3.0.1/versionCode32/minSdk26/targetSdk35、非debug；v2签名通过，原证书SHA-256 `fd2098a3d3493222c247a7ba2787ec48fd8f62d38e9432ed4d6290a057dea475`。XML与构建日志保存交付目录validation下，前轮本地包与线上正式包保持原哈希不覆盖。本轮未安装真机、未发布线上或合main。
