# 首页冷启动空列表恢复修复（2026-11-04）

## 反馈与已确认根因

用户反馈每次重新启动App显示“此列表为空”，手动刷新才加载时间线。本轮基于develop `d290b79c`，仅现有Android，不涉及V4、阅读器、ROM动画、后端或网页发布，不操作设备或修改生产NBW/AI策略。

代码与受控回归确认：主页在NBW discovery尚未完成时开始首屏缓存读取及 `/api/v1/timelines/all`；稍后 discovery 的 null→fresh 使公共/账号NBW世代改变，并通过 `clearNBWRemoteCache` 使混合主页缓存lease失效。Home把此变化与账号/token硬失效一并处理，取消旧加载、空替换并标记 `loaded=true/showingHomeHistory=true`。若已越过首次onViewCreated/onShown，后续没有自动补发路径，连只含本站 `p_/c_` 的首请求也被丢弃，停留页面便显示空。

手动刷新会重新捕获稳定后的世代/lease并发另一次all，因而恢复。再次真正onShown/重建也可能恢复，不声称“永远只能手刷”。本轮没有独立读取用户实际安装版本，确认的是d290b79c/code35 cfac28cd所对应代码路径存在与反馈吻合的可复现竞态。

此前809项相关测试并未覆盖Home首请求与真实cold discovery分阶段完成：History测试的NBW状态基本静止，其他多为已同步准备public/capabilities或只测repository、未mountHome。它们的通过记录真实有效，但不能代替这条新增冷启动时序。

## 最小修复

生产修改仅 `HomeTimelineFragment.java`：

- 区分当前账号/session/token/active account硬身份，与NBW路由世代失效；不降低旧缓存lease或远端策略校验。
- 若硬身份仍有效，只丢弃旧世代响应、移除远端 `nbw_` 并重新过滤已显示的本站历史，不合成“空列表加载成功”；重置旧分页游标，合并安排一次当前世代all读取，无需用户手刷。
- 手动刷新、取消、换号/token变化、view销毁均失效旧恢复任务；网络503等正常失败不进入自动重试循环。
- ViewPager可暂时detach/hide首页View而保留Fragment，因此暂不可见时保留独立恢复意图，由attach/可见pre-draw事件恢复同一待办；执行前仍校验身份、view、list mode、generation和请求所有权。成功/取消移除监听，无轮询。

`CacheController`、NBW能力/授权/端点解析及服务器配置不改。此修复保留已显示本地历史，不承诺首次activate前写入的磁盘混合snapshot永远不被安全失效；即使缓存不可用，也会补发合法本站请求恢复页面。

## 验证

- 修前以延迟discovery、held首all及阻塞缓存回调复现：首请求过期后无补发（all停在1次），或缓存先失效导致首次all根本未发送（停在0次）；不是服务器真实返回空列表。
- 首轮冷启动/History/Snapshot 31项通过；扩展边界94项通过。独立focused复核发现“暂时detach会消耗唯一恢复机会”的P2，补attach/可见恢复及两项真实View移除/重挂测试后关闭。
- 最终冻结 **8类96/96**，0fail/error/skip、源码前后不变；其中新增14个Home冷启动用例，另覆盖既有History14、Snapshot11、刷新几何8、Thread事件19、NBW lifecycle14、session repository9及endpoint repository7。
- 覆盖coldunknown→public local_only、publicfull→认证local_only、配置先于缓存回包、已显示warm历史、activate前磁盘warm后仍自动恢复、500ms身份检查、重复旧回调、换号/token变化、view销毁、手刷接管、同世代cache事件不盲重发、503不循环以及临时detach/hide后的恢复。断言结果到真实attached holder，无需refresh/gesture帮助恢复。
- 不把之前809与本轮96相加称905全量，也不称本轮完整重跑全仓或真实设备冷启动已验。

同一冻结源码已串行 `assembleRelease` 成功，正式签名修复包 `ABDL-Space-3.3.0-beta-code35-home-startup-fix-test-20261104.apk`，168,774,612字节（约160.96MiB），SHA-256 `b39a66531e152aa4d3b7849b75b810f3d3c430fbfc854909d43c8f4eef7a1a0b`。版本维持 `3.3.0-beta / 35`，证书SHA-256 `fd2098a3d3493222c247a7ba2787ec48fd8f62d38e9432ed4d6290a057dea475`。

签名/非debug、CRC、16KiB对齐、完整Noto字体、实际双语言LocaleConfig、地图key存在、非导出详情宿主和ReaderSettings FlowRow实际DEX方法链接均核验通过；测试前后及打包前后源码指纹相同。新文件名不覆盖cfac28cd旧包；未正式发布或安装。

证据：`~/.local/share/abdl-home-cold-start-20261104/`，实际Gradle fresh XML与源码指纹在 `~/.local/share/abdl-seamless-nbw-20261103/home-cold-start-*-20261104*`。失败、夹具初始编译修正及红绿过程保留，不用旧日志冒本轮通过。
