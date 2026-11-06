# 支付宝手机沙箱专用包（2026-11-06，验证进行中）

## 用户授权

用户已在手机安装支付宝沙箱钱包并登录测试账号，批准专用原证书非debug测试APK、隔离本地后端/短时配对/临时HTTPS和develop交付。不得推main、正式发布、安装设备、自动付款或启用真实收款。原正式钱包测试包6fdcded2及正式App存储保留，不覆盖。

## 独立变体

`mastodon/build.gradle`新增`sandboxRelease`，保持AGP8.9.1/Gradle8.11.1/Kotlin/SDK/业务依赖不变。独立包名`top.abdl_space.app.sandbox`、明确“支付宝沙箱测试”应用名、非debug、既有release签名；不改正式`assembleRelease`默认支付环境。

该变体必须显式提供本次核实的`-PsandboxOrigin=https://…trycloudflare.com`，无缺省地址、生产域名、任意Intent地址或关闭证书验证fallback。传入的是公共origin，不含配对码、Bearer或支付配置；缺失时沙箱任务失败，正式任务不因此失败。沙箱任务配置阶段跳过生产地图文件读取，地图标志false/placeholder空；混合正式与沙箱任务一次调用被拒绝。

## 最小运行链路

- `SandboxApplication`直接继承Application，固定`EnvUtils.SANDBOX`并只初始化共享UI所需context；不执行正式`MastodonApp.onCreate`、账号加载、推送、地图、自更新或后台任务。
- Manifest采用白名单替换，仅专用启动Activity和官方支付宝SDK六Activity，保留官方各项属性及optional legacy library；只查询RC钱包，禁备份/明文网络，移除自动Provider/service/receiver及生产AppLink/OAuth/分享入口。最终必须核验实际合并及APK，源码白名单不当合并成功。
- `SandboxSponsorActivity/Ui`只支持手动一次性配对和唯一0.01元/day1合成测试方案，复用原`SponsorPaymentUi`、`SponsorPaymentCoordinator`及`PreferencesStore`；禁爱发电外跳和正式账号刷新。
- `SandboxAlipayLauncher`使用原`AlipaySdkAdapter.Engine`的生命周期/异步/状态过滤规则，专用RC Provider检查沙箱钱包，真实`PayTask.payV2`只由用户手动点击触发。正式Adapter源码和仅正式钱包断言不改。
- `SandboxSponsorSource/Protocol`独立OkHttp及原typed models，固定HTTPS origin、无重定向/自动重试/缓存、bounded严格JSON、原精确订单请求和Idempotency-Key；没有生产AccountSession/API controller初始化。

## 配对和恢复

配对码15分钟、一次性、五次失败锁定；从本地控制文件人工交付，APK/URL/普通日志/Git均不包含。响应Bearer固定43字base64url、1小时、无refresh，仅进程内存；配对码输入不保存、不自动填充，提交即清空。

仅origin/run_id/user_id公开身份持久化，owner绑定三者。进程重启需重新配对到同一测试账本，但原operation/order ID保留；未决单存在时拒绝换owner，不通过重配清空UNKNOWN或预算。配对仅允许120秒手机慢钟容差，不改写服务端expires_at或延长后端TTL。

SDK所有状态都只触发原单查询，成功须后端GRANTED加新鲜Me，只显示本地测试会员；不能把SDK9000、返回App或回调提示当付款/正式权益成功。取消、网络未知、重启、重复通知均按原支付核心处理。

## 验证记录

开发中首次`processSandboxReleaseMainManifest`实际BUILD SUCCESSFUL，7 Activity/零provider/service/receiver/onlyRC/禁备份；因随后补官方optional library及测试文件，指纹变化不当最终源码冻结门禁。原始结果保留。

最终冻结新增5类23项：SandboxProtocolTest、SandboxSessionTest、SandboxSponsorSourceTest、SandboxSponsorUiTest、SandboxIsolationContractTest。原27类209项正式支付及相邻回归仍单独`testReleaseUnitTest`，不删断言迁就沙箱。两张native Canvas计划验证320dp/1.6字体浅色配对、深色未付款页面，不伪装真钱包或交易成功。

首次实际sandbox源码和测试编译已运行1838.95秒，资源/清单处理完成后停留在compileSandboxReleaseKotlin，30分钟有界监控主动结束exit143，前后28339份清单源码不变、无源码错误输出；不当作编译通过。冻结后实际清单为7 Activity、零provider/service/receiver、onlyRC，只有INTERNET/ACCESS_NETWORK_STATE权限，官方optional legacy library保留。后端验证后已同源码增量重试，开始时28339份源码与首次逐hash相同；第二轮Gradle实际exit0、1136.43秒，主Java和单测Java/Kotlin均编译完成。期间因确认target35独立Activity系统栏/键盘遮挡缺口，另补仅该入口Insets处理，wrapper前后指纹变化保留，不把第二轮当最终冻结门禁。最终source/tests冻结后须重新执行回归与assemble；不clean或改依赖。最终签名APK证书、SHA256及公网服务交付状态按真实结果归档，不提前声称APK、安装或手机支付完成。资源不足时仅停止本轮进程，不clean缓存/旧包或关闭用户进程。

## 后端和交付

配套后端位于独立Node/SQLite账本，原位沙箱密钥只在后端运行时读取；手机不输入生产账号、密钥或社区密码。仅精确路径开放，generic创建/admin/退款/兑换不暴露。后端生产Worker835与0106–0108远端迁移不动。

私有证据`~/.local/share/abdl-sponsor-alipay-sandbox-20261106/`保存source hashes、回归日志、工具校验和暂时隧道连接；不存密钥、Bearer、配对码或签单。最终交付使用新APK文件，记录既有证书、独立包名、nondebug和SHA256，并明确临时HTTPS进程依赖。用户自行安装后再手动测试；未发生的手机/通知结果保持未验。
