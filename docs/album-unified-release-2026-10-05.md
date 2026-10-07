# 相册统一交互与遗漏液态修复补回

## 本轮请求

核对之前液态玻璃修复遗漏；上传页沿用App主题；暂时隐藏相册评论入口；添加照片使用内置图片选择器；图片传相册图标为图片；非液态主页发帖FAB使用同款三动作列表；NBW恢复合并验证；以后测试只构建正式签名release包。

## 历史遗漏的实证与补回

原main `cb6b9a35`已有graphics/comments/backdropchain到1667c81e，但缺少后续 `3352159d`（移除运行异常熔断）与 `56d76723`（旧视觉结构、采样暂停/恢复、旧位图清空与预算规划）。二者只在fix/recovery；前轮基于main构建造成整合遗漏，不是用户之前修复全部消失。

本轮在 `fix/album-unified-release-20261005` 从main继续，应用共同基线f8c510fa到56d76723的25文件补丁。24个非重叠文件与历史最新blob一致；共享HomeFragment手工保留相册第三菜单项和新Image图标，其余图形变化保持一致。未用旧worktree整树覆盖，未改安全模块。

移除熔断保持既有API33能力门槛、性能提醒、硬件窗口及16MiB预算；真正图形异常可能直传，不承诺提高稳定性。采样超预算暂停后在同callback下恢复，清除旧图，不被错误当作永久GPU故障。

## 交互调整

- 上传页移除固定黑灰/蓝金palette，使用Surface/SurfaceVariant/Primary/OnPrimary/SecondaryContainer等App主题tokens；保留参考布局。
- 相册reply入口GONE/禁用/不可聚焦，点击与适配器自动评论网络均禁止；普通帖子reply、点赞、下载不变。仅暂时隐藏，不删除后端API或数据。
- 内置MediaPickerSheet：仅图片、剩余20张额度，多选与图片相机。API26–34按图片权限处理，包括Android14所选照片权限；没有自动系统SAF回退或错误持久授权。
- 相机与权限结果维持会话/世代保护，外部活动pause保留意图，但实际hide/destroy撤销pending，防过期回调编辑草稿。
- Home发帖FAB弹28dp圆角底部三卡片：普通帖/交友帖/图片传相册；取消/连续点击安全，使用当前fragment账号。同液态菜单共享openComposeAction路由；主页长按账号选项与其他页面FAB不变。
- 液态图片传相册图标改Miuix Image。

## NBW恢复与裂图评估

后端原有恢复回归21/21通过；另追加“本站/交友分页耗尽后、新的无游标刷新重新合并NBW”实测fixture通过，专项合计22/22。NBW失败是per-request，不永久禁用、不消耗其opaque游标，恢复后自动再参与；不会重新排序已经发出的旧分页。

NBW目前公开时间线200。正确native UA/header下24个附件preview_url均等于原图；正文两图重复三次200/TLS通过，4096字节样本一致，本次未复现随机图片网络失败。大图4032×3024约12.19MP/2.60MB，不是小缩略图；默认头像301到SVG实际200 image/svg+xml，而Appkit平台位图加载链无SVG渲染/失败默认头像兜底。头像与正文不可混为一种已确认根因；没有下载完整图片或真机复现，不排除其他URL、传输尾部/设备信任链/时段问题。

证据为脱敏公开读取，无token/用户内容，位于 `projects/dist/album-unified-release-evidence/nbw-image-followup.json`。本轮不擅改后端媒体代理或TLS校验。

## 验证与交付边界

本轮只assembleRelease，既有签名证书SHA256 `fd2098a3d3493222c247a7ba2787ec48fd8f62d38e9432ed4d6290a057dea475`，包名top.abdl_space.app、3.0.1/code32；签名工具与APK本体核验通过。旧输出已独立保存，根目录mastodon-release.apk原哈希不变。构建不是正式发布，不更新latest、签名或版本号，不覆盖安装用户设备。

初次release单元回归434项有78失败：多数Compose测试activity只在debug测试manifest注册、multiSDK测试资源OOM，以及新选图/评论测试fixture不符真实框架路径。已采用测试侧注册ComponentActivity（不进入release manifest），按SDK独立回归保持512MiB预算，修正权限controller、各用例隔离FileProvider静态缓存、真实FileProvider与可见窗口/消息循环fixture。最终release专项343/343通过、API26–35独立SDK偏好回归96/96通过、发帖弹层深浅实际渲染1/1通过，合计440通过/0失败/0跳过。NBW恢复专项22/22通过。

最终独立release测试包：`/home/ZYongX/projects/dist/album-unified-release-evidence/ABDL-Space-3.0.1-code32-album-unified-release.apk`，86,858,852字节，SHA256 `ed51f7f4bf8e0ffa12af02850c622f992e8c030b054e086fed1ec704f8897a5f`；正式证书v2签名核验通过。没有assembleDebug或交付新debug APK。持久日志、XML、主题上传页/三动作菜单截图、图片排查证据与release-manifest.json同目录。

本轮源码未提交/推送/合入main，后端仅追加恢复测试，未部署改动。真实GPU、内置选图与覆盖安装、相册双账号/真实COS完整闭环仍须实机验收。
