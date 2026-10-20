# 扩列卡社交账号、链接预览与自适应按钮（2026-10-20）

## 范围与发布边界

基于 Android develop `525b2cad` 和后端 develop `99d9e29`，两仓均使用 `feat/friend-card-socials`。参考 `04.png`、`05.png`、`06.png`、`media.pen` 以及 QQ 插卡帖子截图。只集成/推送 develop 与交付既有正式签名 release 测试包，不推进 main，不发 App，不执行生产数据库迁移或部署。社交账号和服务端 LINK 封面元数据须后端先上线，未有能力标识时客户端保留草稿并禁止发送新字段；不把前端 UI 写成已在生产可用。

## 帖子链接卡片

普通帖子引用由纯宣言改为独立紧凑横向入口：轻微倾斜的竖向缩略卡、归属标签、最多两行宣言和右侧箭头。无图或旧后端无 `link_preview` 时用既有官方扩列卡功能图标，不留空图块。保留原帖文字、作者、音频/图片与互动条；不把普通插卡改成完整发布卡。

只读取当前服务端投影的 `friend_card.link_preview` 中封面、焦点和卡主展示名，不使用外部卡片 URL抓取、不读取完整卡或社交账号作缩略内容。私密/direct、敏感、隐藏图片、不可用、屏蔽与失效卡不发预览图请求；重新绑定后旧图不能回填。旧文字/HTML尾注与编辑三态引用语义不变。

## 社交账号

在“关于我”阶段互动习惯后新增区域，最多5条。选择平台弹层支持搜索，预设 QQ、微信，另可选自定义平台；官方 logo 从用户提供 PSD 合成图提取，不重画。每条稳定 UUID，账号120码点、自定义平台名称20码点，单行；可删除、独立控制公开，新增默认仅自己可见。不导入账号资料、不验证真实账号、不请求第三方信息。

详情只显示逐条明确公开的账号，并提供显式复制（剪贴板设置 sensitive 标记），不自动跳转第三方链接。推荐摘要与帖内缩略卡不展示社交账号。草稿保存私有信息，公开预览/旧端文字只投影公开项。

服务端 v1 JSON 增加可选 `social_accounts` 数组，不新增 DDL。省略保留旧 canonical hash，旧客户端全量 PATCH 省略时保留服务端原账号和可见性；显式 `[]` 清空。每条 `is_public` 不纳入默认公开字段集。Android nullable 字段不被 serializeNulls 强行上送为null，旧 pending 操作比较仅归一化新增字段null/省略，保留原 operation ID和原inputJson；不放宽对真实输入修改的锁定。

自定义主页可填公开 http(s) URL 或普通账号名。仅作语法/host 校验与复制，零第三方请求；拒绝危险协议、凭据、私有/保留名称与IP地址。国际化域名通过 IDNA 校验但不改写用户原文，避免服务端接受而客户端无法再编辑。

## 详情按钮实时对比度

普通与液态模式使用同一内容层来源。只记录下方实际内容（包含不透明底色，不包含悬浮按钮），按关闭/管理两个按钮各自的实际区域裁剪为16×16像素回读；不按全局主题或整张封面平均色猜测。内容变化、滚动、封面加载/淡入/动画与布局使采样失效，约100ms合并节流，无变化时不轮询。生命周期停止/销毁取消回读；不调用decor/root/View.draw生成运行时截图。

独立相对亮度和滞回判断选黑白前景，前景、保护底色、边框、阴影220ms过渡，保留原玻璃折射、高光与按压。超过时限或软件/不支持的回读使用保护性静态样式，不假称已采样。同步GPU原语不能由协程强制中断，额外检查单批实际耗时，超时后禁用重复采样。

## 验证记录

后端专项61/61和scoped lint通过。新鲜完整序列基线608总/599pass/9fail，最终617总/608pass/9fail，失败名称多重集合完全一致，新增9项通过；未做类型全绿声明。持久证据 `/home/ZYongX/projects/abdl-friend-card-socials-backend-evidence/`。

Android最终扩列卡/发帖兼容定向回归22类144项通过（0失败/错误/跳过）。首轮144项中6项失败包含：平台弹层宿主不可见，改为自包含原生Dialog；历史pending null/省略比较遇到Gson Float .4 vs解析数值不等，改为两侧都parse已序列化JSON且仅归一化social_accounts:null；测试暂停时钟未发布snapshot导致动画尚未开始，补同步/起始帧并保留精确220ms中间色与端点断言；音频fixture漏previewURL与软件字体几何假设，补fixture并用原生字体检验保留两行ellipsis断言。未跳过测试或放宽隐私锁。原始失败证据 `abdl-friend-card-socials-initial-ui-junit.tar.gz`保留。

原生截图包含社交表单、独立Dialog平台选择、公开详情的浅/深色及8张有图/无图/失效/长标题插卡帖。已人工查看主要状态。持久副本 `/home/ZYongX/projects/abdl-friend-card-socials-renders/` 共39张（含复用的既有扩列卡状态）。

最终同一生产源码的定向整合回归 **44类 / 351项全部通过，0失败、0错误、0跳过**。覆盖本次新增及既有扩列卡、发帖、地图、相册、感谢榜、折扇/液态菜单。不是全项目全量测试，不累计专项144到351。生产及测试Kotlin/Java已强制非增量重编译。最终日志 `/home/ZYongX/projects/abdl-friend-card-socials-final-release.log`；逐类摘要 `abdl-friend-card-socials-test-summary.json`；JUnit XML/HTML归档 `abdl-friend-card-socials-final-junit.tar.gz`（同目录）。

正式签名 release 测试包（不发布）：
- `/home/ZYongX/projects/ABDL-Space-3.1.0-code33-friend-card-socials-test.apk`，236,547,568 bytes。
- SHA-256 `0750e92b60e66e33315822fd010be1fc9c6453683597a24d0e0eee2dddb66e9b`；ZIP 28,508项CRC全部通过。
- V2签名验证通过，沿用既有证书SHA-256 `fd2098a3d3493222c247a7ba2787ec48fd8f62d38e9432ed4d6290a057dea475`。
- top.abdl_space.app / 3.1.0 / code33，非debuggable，min26/target35/compile37，四ABI保留。assembleRelease与verifyMapKeys成功，未输出密钥或生成debug包。
- 与线上同versionCode，仅文件名区分；正式版main与下载/版本JSON未动。签名/badging日志 `abdl-friend-card-socials-{signature,badging}.log`。

软件API28截图不替代物理GPU液态玻璃/实时回读真机验收。真实社交账号、双账号、COS上传和发帖闭环不使用用户token或伪造认证补验。
