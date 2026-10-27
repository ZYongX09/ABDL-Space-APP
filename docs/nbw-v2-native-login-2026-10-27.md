# 登录页 NBW V2 独立登录

日期：2026-10-27。用户指出登录页 NBW 登录缺失后授权继续完成。基于 Android develop `4fb6e7e1`，与后端独立login增量配套；不修改并行V4工作树，不自动发版或覆盖已装4.0.0的设备。已包含此前私信输入法避让修复。

## 登录入口与流程

- 邮箱验证码、账号密码两页恢复原NBW按钮。需勾选本站协议，并通过原生BottomSheet确认“使用宝宝新天地账号登录本站”，才启动nonexported `NBWLoginActivity`。
- 系统浏览器打开固定NBW授权端点，无WebView收集密码，无旧mobile-callback/UID-proxy/V1回退。取消确认或页面销毁/取消勾选不发起登录。
- App生成256bit verifier和S256挑战。`/api/app/nbw/login/start`只发送挑战，返回随机flow及受严格校验的固定主机/路径授权URL、匹配flow的签名state。
- callback必须精确 `abdl-space://callback?nbw_v2_login=complete&code=<canonical43>`。先于绑定/普通OAuth单独处理，拒绝额外字段、重复query、userinfo/port/path/fragment变形；清原Intent data后只把generation传给内部Activity。
- completion code只进入当前进程的pending事务；与verifier/flow三字段POST `/exchange`才能换本站JWT。无码、marker-only、无pending或进程死亡不发兑换请求。同码不重复自动兑换，不延长120秒期限；异码不覆盖已有code。
- 内存generation/owner/当前账号和会话代际、主flow600秒及完成码120秒共同保护取消、旋转、切号/退出和迟到响应。进程死亡安全失败，要求重新发起，不将proof写preferences、Bundle、内部Intent或日志。
- 新增guarded `AuthSessionInstaller.install`重载；请求前、真实GetOwnAccount回包及addAccount紧前均复核所有权，并返回可取消请求。旧QQ四参入口语义不变，Main跳转仍早于成功Listener。安装自身改变账号后不误作旧identity失败。

## 账号适用边界

后台要求当前唯一绑定且已有可解密、匹配UID的有效V2 grant，才允许用NBW登录本站；不创建/更新长期grant。旧email-only关联不足以证明本站账号归属，明确提示先用本站账号登录并重新连接。未绑定UID提示先登录/注册本站再连接，不自动建号/合并邮箱；不是“未绑定用户一键NBW注册”交付。

服务端复用现有固定OAuth回调配置，但合作方白名单/真实用户授权仍未验证。新路由与0096生产未上线；仅安装本测试包不会让线上自动具备新接口。

## 安全复核与修正

初稿marker-only深链搭配flow/verifier兑换，被主代理与独立定点复核确认缺少回调持有因子：发起者可转发授权URL并轮询领取他人授权。最终改为短期一次性completion code+PKCE双证明，DB只存hash并在最终CAS检查期限。旧两字段合同完全删除，该初稿从未发布。

Android和后端冻结补丁已由独立只读复核确认原阻断关闭；不把定点复核称为全仓安全审计或真实上游授权验收。

## 验证

- 同一次串行最终 **202/202** `testReleaseUnitTest`通过，0fail/error/skip，随后同命令 `assembleRelease`成功（27m14s）。保留之前173项、增加登录24次与既有协议确认/QQ installer合同5项。
- 新测试：Flow7、Activity6、HTTP(API26/28)8、实际两页按钮/确认/取消/生命周期与nativeCanvas Consent3。覆盖S256向量、请求三字段与无旧bearer、系统trust专用client选择/不跟随重定向、严格URL及JSON、无码不能轮询、过期/重复/并发/旋转/取消/退出/迟到回包、真实installer stale guard。
- 两页深浅色4张完整真实层级Canvas图已检查：文案、未染色NBW logo、确认/取消文字与边界正常。首图仅画dialog导致透明背景和未交付动画，后修为真实页面+窗口dim+sheet合成；fixture补GlobalUserPreferences.color初始化并用Window坐标验证控件边界，未为截图改生产UI或放宽断言。
- 首轮45项44通过/1个既有QQ资源测试失败，单独复跑仍失败；`ic_field_qq.webp`与基线4fb6e7e1逐字一致（chunk VP8X，旧测试要求VP8L）。本轮不改无关资源，最终相关门禁不包含该旧图片断言；不能将202通过称为全仓测试全绿。
- 最终测试/构建前后源指纹完全一致：Android28371文件、后端341文件。后端完整1469/1460pass/同9既有fail，新增87全过；类型340→340逐字归一化一致、lint零、Worker dry-run成功未上传，详见后端仓同主题文档。

## 正式签名测试包

- `/home/ZYongX/projects/ABDL-Space-3.2.0-code34-nbw-v2-login-test.apk`
- 151579883字节；SHA-256 `07a059e0873df12d6a1afb654d27e35df7f01fdea25d8b5bf215a228ee679fd0`。
- 仍3.2.0/code34，非debuggable，既有正式证书`fd2098a3…`；apksigner/ZIP CRC/16KiB对齐通过。旧测试包保留，不覆盖正式下载。

证据目录 `/home/ZYongX/projects/nbw-v2-login-evidence-20261027/`，截图 `renders/`。Android main仍`c8dd0ccd`；后端main仍`dc8e410`/Worker784、0096未执行，full/密钥未动。真实NBW授权、真实账号/设备闭环未代测。
