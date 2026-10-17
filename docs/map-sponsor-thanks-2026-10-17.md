# 地图首次引导与感谢赞助者

## 实现范围

- 地图准入顺序：明确接受当前协议 → 服务端当前宝宝认证 → 首次明确选择“谁可以看到我” → 定位权限 → 本地地图居中。首次单选不预选，确认成功前不请求定位、不读取个人位置点或上报位置。
- 隐藏与仅互关的新选择仅有效赞助者可用。非赞助者卡片显示锁与弱化标题，说明为“此选项仅赞助者可选”；卡片仍可点击，但只展示既有 SponsorNoticeSheet 样式的成本说明，不改变选择。
- 隐私安全例外：停止出现/撤回始终允许；已有隐藏/互关可显式保留，不因赞助过期变公开。已过期的指定位置用户确认可见性时同批切回设备模式，避免公开中间状态或重复上报旧指定点。
- 本地定位和公开位置分离：隐藏用户也可授权后居中，但不上传坐标，不生成公开自身标记/模糊圈。已有公开点不会因本地浏览定位自动替换。
- 液态玻璃底栏在地图激活前注册生命周期采样回调。地图使用独立 backdrop，SDK 截图按对应 provider View 的窗口原点和尺寸映射；TextureView 有界采样失败/透明帧回退 SDK 截图，普通页面 strip 不覆盖地图源。退出/暂停/销毁清空。
- 关于页新增“感谢赞助者”，独立原生页面保留关于页返回栈。四列真实头像/昵称、排名角标、前三金银铜描边，支持明暗主题与现有赞助中心入口。
- 感谢榜读取账号所在服务器的 `/api/v1/sponsors/thanks`，按成功兑换的方案金额快照累计排序；周+月示例为190+590=780分。未硬编码名单，不因权益到期删除累计。
- 感谢页可见时每15秒刷新、返回刷新、当前账号赞助事件立即刷新；分页刷新原子替换已载入前缀。后台/隐藏停止轮询并取消API和头像请求，旧账号/旧响应不覆盖当前界面。此处为轮询实时更新，不是WebSocket推送。

## 后端依赖与上线边界

本轮同时修改后端 develop：新增感谢榜，地图 settings 新增 `visibility_confirmed` 和 `capabilities.can_choose_visibility`。旧库需单次执行0079（前置0078），再协调确认感知Worker与客户端切换；SQL单独执行不能让旧Worker识别确认门禁。

本轮未运行生产SQL、未更新main、未部署Worker、未上传正式APK或修改latest。新客户端在旧后端上无法完成新引导/加载感谢榜，不能将测试包构建等同线上可用。

金额是成功兑换方案金额，不是支付实收/退款净额。免费管理员授予及admin来源码不计；无法证明的历史金额不猜测。详情见后端API合同。

## 验证记录

- 核心 Android release 单测87/87通过：Fragment真实权限/本地定位和回调10、Overlay跨API26/31/35共39、感谢页真实Fragment4、HTTP/Gson5、刷新分页状态机15、玻璃实际像素跨API33/35共14。
- 生产 Kotlin/Java、测试 Kotlin/Java 已完整编译。首次测试返回类型与同名摘要命中问题已修正；权限请求在 Fragment 边界捕获，不以 Activity shadow 的记录代替 Fragment 请求证据。
- 后端最终本轮重跑专项74/74通过，scoped ESLint与差异格式检查通过。
- 新地图schema与0075→0078→0079升级一致性通过；完整bootstrap仍存在既有paper_color缺列失败（1pass/1fail），未扩修无关初始化缺陷。
- 当前环境没有可用真机/模拟器；未进行GPU玻璃效果、双地图SDK、真实账号首次引导/兑换榜端到端或设计稿视觉验收。Robolectric像素测试不代表真机验收。
- 扩大 Android release 回归219/219通过（零失败/错误/跳过），`:mastodon:assembleRelease`成功，`verifyMapKeys`确认APK携带已配置密钥。低内存单worker构建耗时1h13m；既有D8/Kotlin metadata兼容警告未阻断构建，未借本轮升级构建依赖。
- 正式签名测试包：`/home/ZYongX/projects/ABDL-Space-3.1.0-code33-map-sponsor-thanks-test.apk`，236013663字节，SHA256 `f65af923aa52f74ab1cef03657e950071a44293fe57402beca2e0a6dfb525ab7`。与构建原包哈希一致；apksigner v2通过，证书SHA256 `fd2098a3d3493222c247a7ba2787ec48fd8f62d38e9432ed4d6290a057dea475` 与上一正式签名测试包一致。版本仍为3.1.0/code33，仅文件名区别，不是正式发布。
- 后端已提交并推送develop `f808bf3`，未更新main；Android同样仅交付develop。最终构建日志在仓库外 `/home/ZYongX/projects/abdl-map-sponsor-final-build.log`，签名记录在同目录 `abdl-map-sponsor-signature.log`。
