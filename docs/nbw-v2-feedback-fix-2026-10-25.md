# NBW V2 实测交互修复

日期：2026-10-25。基于 Android develop `fa29a5e5`，分支 `fix/nbw-v2-images-ui-20261025`。用户核查五项问题后授权修复；本轮交付修复源码与新的既有正式签名 release 测试包，不改 main、不正式发版。

## 交互合同

1. **发帖页“AI 推荐”单击直接弹出二级菜单**。以真实 `btn_visibility` 为锚点，复用 miuix 的两级叠加列表 primitive；没有分类中间页面、没有全屏 ComponentDialog，也不需要第二次点击摘要。
2. **分类与回退同意分离**。发帖卡片和连接页的“默认去向设置”仅打开紧凑 AppKit BottomSheet。说明默认闲聊 FID18，在用户明确接受或拒绝后请求服务器保存，认证能力回读才是状态权威；不会逐帖询问、不会隐式同意、不阻本站发布。
3. **分类合法性**。必选板块不得选择0；可选板块用“无子分类”描述typeid0。菜单展开时冻结顺序和ID，点击时对当前目录和generation重新验证，过期不偷换板块。返回先收二级，再收根菜单。
4. **连接引导复用旧版UI**。恢复原XML的Space背景、logo、标题与留白、浅蓝圆角主按钮、描边次按钮、文字跳过和设置返回入口；保留新版V2授权/取消/会话代际、回调只刷新元数据。V1旧绑定/登录代码不恢复。
5. **本站体验保持**。缺授权、目录失败、远端故障均不锁普通本站编辑和提交；媒体、投票、CW、typed父、扩列卡等既有约束不为NBW改变。

NBW列表配图由配套后端修复：只展示经匿名详情权限校验的附件图，合并列表只增强实际返回前缀；不是App逐帖额外请求，也不直接透传可能包含隐藏或付费内容的preview。后端修复本轮先交develop，未部署前线上图片行为不会因为安装APK自动改变。

## 验证边界

- 测试必须点击真实ComposeFragment按钮再检查菜单，不再用standalone控制器设置页冒充入口验收。
- 连接页、菜单、同意sheet将生成实际native Canvas深浅色PNG并检查布局；这是Robolectric软件渲染，不是真机、硬件玻璃或IME视觉验收。
- 本机Linux插件不支持桌面模拟器、没有设备/AVD；不安装SDK、不接受许可、不代用户授权或发布远端内容。
- Android生产Kotlin/Java首次编译通过；实际菜单主层/二级层及独立同意sheet已经生成native Canvas图，真实按钮一次点击和子菜单返回已实际执行。
- 入口测试的首次停滞定位为Robolectric原生窗口在暂停vsync下挂载后留下的绘制同步屏障；恢复帧后先有界处理原生帧，原生动作改用runOnUiThread，只有实际Compose节点断言才等待Compose空闲，不替换真实按钮/回调。
- 连接页测试初次失败为资源限定符次序（night必须在mdpi之前），后续字体检查改为native实际Paint粗体与XML双重核验，按钮颜色从同一整页渲染取样而不是重复孤立draw；具体失败证据保留。
- 实际连接页渲染复核后保留旧浅蓝按钮填色，但提高按钮文字对比度，说明区改中性表面色而不是红色警告条，跳过文字改主题主色。
- **最终同源码一次串行回归98/98通过，0 fail/error/skip**：真实发帖菜单/独立同意13、连接页实际UI9、会话生命周期13、发布行为19、repository7、Parcel6、外站主页9、头像2、来源2、API26–28兼容6、标准OAuth6、既有互动6。随后同一Gradle执行`assembleRelease`成功、地图密钥构建护栏通过。不是Android全仓测试，也不等于真机验收。
- 最终验证前后28005个源码/构建配置文件指纹一致，`changed_paths=[]`；10张实际native Canvas深浅色截图已检查，菜单第一层/子层、独立BottomSheet、注册/设置两种连接入口均无全屏中间设置页、文字遮挡或按钮不可读。截图位于`/tmp/abdl-nbw-v2-menu-renders/`与`/tmp/abdl-nbw-v2-connection-renders/`，长期证据副本见仓外证据目录。
- D8仍提示Kotlin metadata版本兼容警告，旧测试包构建已有同类提示，本轮未升级工具链；构建和签名检查通过，不把警告隐藏或宣称已解决。

## 正式签名修复测试包

- `/home/ZYongX/projects/ABDL-Space-3.2.0-code34-nbw-v2-ui-fix-test.apk`
- 大小151546078bytes，SHA-256 `a9df76875597d8b8d07ecddbb48c7d0321e97bc9957eed37159c001153c09c5f`。
- `top.abdl_space.app`，3.2.0/code34，非debuggable；证书SHA-256 `fd2098a3d3493222c247a7ba2787ec48fd8f62d38e9432ed4d6290a057dea475`，与既有正式签名一致。
- `apksigner verify`、ZIP CRC、`zipalign -c -P 16 4`均通过，六个DEX和原生库存在。
- 原测试包`ABDL-Space-3.2.0-code34-nbw-v2-test.apk`完整保留，不覆盖；本轮不上传正式下载。仍与线上同code34，不能只凭版本号区分。
- 98项原始结果和产物索引：`android-final-tests-assemble.log`、`apk-verification.json`。后端图片修复尚未上线，安装此包不会自动改变当前生产列表图片行为。
- 新包仍3.2.0/code34，仅文件名和哈希区分，保留原测试包，不改正式下载JSON/R2/GitHub Release。

证据目录：`/home/ZYongX/projects/nbw-v2-ui-fix-evidence-20261025/`。
