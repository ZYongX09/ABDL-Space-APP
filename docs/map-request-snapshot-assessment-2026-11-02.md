# 地图请求机制与进入时快照方案评估

本记录仅基于当前Android `d3638b77` 与后端开发/发布线地图代码的只读分析，没有读取生产位置数据、测量真实流量或改变地图请求机制。本轮英文适配可更改地图显示文案，但不改变请求、定位、授权或撤回行为。

## 当前请求机制

地图活跃条件为页面resumed、Tab可见、View未销毁且账号session有效。正常进入先GET `/api/v1/map/settings`，通过协议、宝宝认证和可见范围确认后初始化地图SDK，再GET `/presence/me`，获取一次设备浏览定位；必要时PUT本人位置。

SDK ready、本人点位返回、定位/上报和相机停止都可能安排列表查询。相机停止后防抖400ms，请求 `/presence`，参数包含当前min/max经纬度、center、zoom与`limit=100`。没有最小移动距离、同bounds去重、视口缓存或将多视口合并为全国点集。每次响应替换当前点集，旧响应有generation保护，取消在途请求不能撤销已经执行的后端读取。

因此，进入一次地图的业务请求更接近“设置一次、本人状态一次、列表若干次、可选位置上报”，不能称为一次请求。点击人物focus、重新定位及SDK相机变化也可能间接触发列表查询；不是只有手指拖动才查询。

切出地图、后台或部分导航离开会取消请求并清除点位，回来重新激活加载。地图自身内嵌面板不是新进入；rotation和View重建目前没有独立持久点集。单纯Compose重组和玻璃截图不是业务点位API请求。

代码入口：
- `mastodon/src/main/java/org/joinmastodon/android/fragments/discover/FriendMapFragment.java`：active/loadSettings、loadOwnPresence、queryRunnable、loadViewport、onResume/onShown。
- `mastodon/src/main/java/org/joinmastodon/android/api/requests/map/MapRequest.java`：20秒敏感传输、bounds及limit参数。
- `mastodon/src/main/java/org/joinmastodon/android/ui/map/AbstractMapProvider.java`：marker渲染上限。
- 后端 `src/routes/map-presence.ts`：GET presence的当前权限和bounds SQL。

## 数据上限和缓存

- App单次请求100点；后端最多300点，按展示坐标bounds过滤、按更新时间排序。
- `nationwide`表示全国可浏览，不表示返回全部全国记录。
- `next_cursor`目前为空，没有完整性/截断标志或可用的全国快照分页；传全国bounds仍可能只得到前300点。
- 当前客户端也最多渲染300 marker，没有本地cluster算法；附近列表使用当前点集，不是独立全国数据源。
- 点位接口为private/no-store，无五分钟地图点缓存或周期自动刷新。`auto_refresh`字段存在不代表页面已有定时轮询。
- 位置是持久展示，没有“下线自动过期”的TTL；隐藏/停止服务才撤点。地图没有online字段或地图在线心跳。

头像缓存、聊天WebSocket和SDK瓦片是独立链路。把点位API改成一次快照不会消除它们，不能将总网络请求承诺为一次。

## 方案结论

**有条件支持“每次进入获取一次完整轻量静态快照，移动/缩放只在本地筛选聚合”；不支持“直到下次进入，所有数据和展示权限都无限冻结”。**

### 必要配套

1. 后端提供有完整性保证的轻量快照协议，含revision、complete/truncated信息及严格点数/字节预算。数据多时需一轮有一致性的分页快照，不假称只有一次HTTP。
2. 客户端保留轻量点集和空间索引，仅绘制当前视口内有限marker/cluster，头像对可见或选中项懒加载；附近面板需虚拟化。
3. 明确最大展示时效及与pan无关的撤回/权限失效链。隐藏、public转friends、取消互关、匿名化、精度变粗、认证撤销和账号状态不能等到无期限的下次进入才生效。
4. 当前地图候选SQL尚需补目标账号ban和双向block边界，不能以互关关系代替block。缓存不能绕过当前viewer权限。
5. 选中人物面板、头像、模糊圈与地图截图也必须随撤回失效，不能只删除marker而保留旧人物对象。
6. 为快照定义账号/IP/字节/分页预算，降低全国批量采集风险。匿名ID不能为增量更新而变成永久追踪标识。

### 推荐进入语义

- 从非地图Tab/入口进入：新entry。
- 地图内人物/设置/精度/provider面板：同一entry。
- rotation/recompose：同一entry，由独立于View的会话状态持有快照。
- Profile/认证/赞助流程返回：可以沿用entry，但先重新校验相关门禁；变化或过期则失效。
- 前后台：不必每个onResume重复全量下载，但长后台/过期应校验或结束entry。
- 切账号、注销、进程重建、明确离开：结束entry并清除个人数据。

## 成本和性能

该方向能减少pan/zoom/focus造成的重复点位JSON、D1前置鉴权/设置读取及列表查询。但当前LIMIT不意味着只读相同数量的数据库行；查询计划和真实D1用量需另行测量，不能从请求次数直接给出额度降幅。

全量数据不等于全量头像marker。仅量级示例：每点轻量JSON假设300–600字节，一万点约3–6MB未压缩，但一万张144×144 ARGB头像单份约0.77GiB，尚不含SDK描述符和缓存。应本地索引、可见范围聚合渲染，不能简单取消300上限并全画。

## 本轮边界

本轮只记录现状、风险与推荐方向，没有实现快照接口、调整地图limit/debounce或新增轮询。真正采用该方案需另行确认快照容量及撤回时效，并独立实施、测试和授权发布。
