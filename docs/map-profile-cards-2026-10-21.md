# 地图服务重置与公开扩列卡入口（2026-10-21，开发交付）

本轮基于 Android develop `a53dd436`，在 `feat/map-reset-profile-cards` 实现。后端配套实现 `f75e6ff` 与开发交付记录 `fe92c25` 已推送后端 develop；main未推进、0083未执行、Worker未部署。Android仅交既有正式签名的release测试包，不正式发布，不操作真实账号、令牌或生产地图数据。

## 八项调整

1. 删除账户设置中的交友地图入口，保留主导航地图。
2. 经典折扇的扩列卡资源改走 `FriendCardResourceIcon` 原生 ImageView，使用既有机械描摹矢量。图标测试比较真实展开菜单中心的笔画、洞口、主题色和独立原生资源参考，不能以圆形背景不为空代替图标可见。
3. 服务端检测观察过的有效赞助周期失效时，撤回位置、清范围确认、增加修订号；客户端依据最新 `visibility_confirmed=false` 重新进入范围选择，不从capability缺失/读取错误推断赞助到期。连续有效续期不重置；上线前已过期且从未被checkpoint观察的历史不作猜测。该服务端行为须0083与配套Worker上线后生效。
4. 个人主页简介下方新增图标与“查看扩列卡”，仅绑定服务端当前公开卡引用；点击复用详情，返回重新读取账户。重验失败隐藏入口，不复用旧引用；保留原相册/关于标签，失败清理与下拉刷新的交错状态。
5. 标准、紧凑、可选择三种帖头在用户名与徽章之间加入独立扩列卡图标。宝宝蓝light `#4E7394` / dark `#B9D9F0`，保留用户名emoji span、赞助用户名颜色及徽章span；回收/失效时清空图标和可访问性描述。不发逐帖或逐用户补查请求。
6. “出现设置”移除“在地图上出现”和“匿名显示”两个开关，不因移除UI而改写既有服务端匿名值。
7. “谁可以看到我”仅在已保存且已确认的public选择下显示普通“停止使用本服务”按钮；首次或未确认选择不显示。触控测试在真实附着窗口给完整overlay派发DOWN/UP，检查事件到达Button并只调用完整停止一次。
8. 设置页“停止在地图上出现”改为“停止使用本服务”，有二次确认，调用 `POST /api/v1/map/service/stop {}`。发送前递增generation并取消旧请求/定位/快照、清展示，只有通过完整响应合同校验才采用新consent并销毁SDK、回到协议引导。请求失败或旧服不支持时不伪报已停止、不自动改用隐藏。独立紧急撤回仍保留，但不等价于完整服务重置。

## 协议与兼容边界

账户扩展 `public_friend_card` 只包含 `schema_version=1` 和卡UUID；缺失、null、未知版本或畸形值仅隐藏入口，不丢弃原Account/Status。生产Gson、plain Gson、嵌套reblog与Parceler均有回归。暂停/删除/封禁/双向block/不可见发布帖均由服务端实时公开ACL决定，标志不是详情授权。

新停止能力必须由 `capabilities.can_stop_service=true` 明示。Android校验停止响应的consent=false、required_version、hidden、未确认、active=false、point=null及单调增加的settings_version。真实OS定位权限和厂商SDK全局隐私同意不被撤销；仅清本服务协议和本地provider状态。

旧生产后端未部署本轮功能，因此当前线上不会提供新的公开卡引用/完整停止能力，测试包将安全隐藏公开入口或禁用完整停止并说明原因。不能据此把旧服兼容结果当作新功能端到端验收。此轮没有对社交URL发起服务端请求。

## 验证与交付记录

最终同源码定向回归 **52类 /463项全部通过，0失败、0错误、0跳过**，非全项目全量测试。覆盖地图API26/31/35停止/过期/旧回调、完整overlay触控、profile五条生命周期行为、三种帖头浅深色与回收、扩列卡/社交/插卡/相册/感谢榜及导航整合。测试前后27,892个source/test文件指纹完全一致；随后仅补交付文档，未改生产源码。

首轮52类463项有3个同方法失败：测试先排缓存定位、再排AlertDialog确认，导致定位在停止生效前合法上报。仅修测试排队顺序，使真实确认监听先入队、真实定位回调后入队，保留严格零上报和旧回调不复活断言；地图专项72/72及最终52类整轮463/463通过。旧失败日志归档保留，不把中途停止或启动配置失败的运行计为通过。

正式签名release测试包：`/home/ZYongX/projects/ABDL-Space-3.1.0-code33-map-profile-cards-test.apk`，236,325,992 bytes，SHA-256 `e25367d7c4c62c5ff06e4dac892e02dc7a0db73a5afa711f75f3c42ff279e348`。沿用既有证书SHA-256 `fd2098a3d3493222c247a7ba2787ec48fd8f62d38e9432ed4d6290a057dea475`，APK V2验证通过；28,508项ZIP CRC全部通过，四ABI，top.abdl_space.app 3.1.0/code33、min26/target35/compile37。assembleRelease和verifyMapKeys通过，无debug APK、无缺密钥豁免。版本仍与线上相同，仅按新文件名区分，旧测试包保留。

证据目录 `/home/ZYongX/projects/abdl-map-profile-cards-evidence/` 包含最终命令、日志、JUnit归档、逐类摘要、冻结指纹、签名/包信息/产物摘要。34张本轮native渲染持久副本位于 `/home/ZYongX/projects/abdl-map-profile-cards-renders/`，含profile有卡/暂停/缺失、三种帖头emoji/长徽章/复用与折扇浅深色；主代理已检查主要状态和笔画，不是用户视觉验收。

宿主Linux无可用模拟器/USB设备，未安装环境或替用户接受SDK许可。Robolectric/native Canvas软件渲染验证不替代物理GPU液态玻璃、真实输入法、真实账户/双账号和定位权限闭环验收。
