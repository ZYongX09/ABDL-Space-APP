# 新版 App 邮箱验证码人机验证

本功能接入既有 3.2.0/code34 客户端开发线，不修改并行 V4。与后端独立 `/api/app/human-check` 协议及两站 `/admin/app-clients` 策略卡配套；策略默认 off，仅保护注册用途验证码邮件的首次发送与重发。旧 App、旧 `/api/auth/send-code`、网页及 Captcha V1 保持原合同，不强制升级，也不将保留旧入口的兼容策略称为全站防机器人。

## 交互

1. 新协议 `/send-code` 返回 `HUMAN_CHECK_REQUIRED` 后才进入全屏安全提示页。提示页创建时零 WebView、零挑战请求，不加载 Cloudflare。
2. 用户点击“开始验证”才显示居中圆角 Dialog，立即显示循环进度和“正在加载验证组件…”。10 秒显示慢加载提示，30 秒有界超时，不自动弹窗或循环重试。
3. 页面脚本加载并完成真实 widget render 才通知 ready；`onPageFinished` 不是 ready，更不是成功。需要交互时保留 Cloudflare 控件区域；核验时显示“正在核验”。
4. 页面将 token POST 给本站 `/verify`，后端 Siteverify 通过也只产生当前短期 attempt 的 verified 状态。原生收到消息后仍必须 `/complete` 校验当前操作持有证明、策略 revision、期限和一次性状态。
5. 只有服务端业务 `sent` 才返回成功，首次发送进入验证码页，重发启动既有 60 秒倒计时。伪造 Activity 成功结果、窗口关闭、脚本完成和 ready 均不能触发业务继续。

提示页返回取消整个操作；弹窗返回或关闭只取消当前 attempt，留在提示页，可由用户再次点击开始。遮罩不触发关闭。迟到 WebMessage、网络回包、旧 WebView 错误和后台投递均按 generation/owner/source/attempt 检查。旋转或进程死亡不恢复 verifier、不自动创建 widget 或重发。

服务端派送 CAS 是取消线性化点：派送接纳之前可取消；之后不能保证邮件撤回。App 仍不自动导航或倒计时，结果未知时只允许显式查询，不自动重发。已成功、已取消、明确失败的终态不因本地授权 TTL 过期重新变成 unknown；真正未决派送过期仍禁止自动创建重复发送。

## 主题与插画

提示页使用 App 原生 Material 主题属性，浅深色的页面、文字、按钮、导航及状态栏统一。支持安全区、可滚动内容与大字体横屏。参考插画按圆底、手机、盾牌和勾的轮廓与比例描摹，不复制京东品牌；保留源 `assets/illustrations/human-check-security.svg`，Android 使用 `ic_human_check_security.xml` 渐变矢量，不对整图施加 tint。

原生 Canvas 证据覆盖提示、初始化、策略变化失败及正在继续的深浅状态。其数据来自真实 Coordinator 处理受控 API 回包，不是实际 Cloudflare widget 验证，也不是硬件或真机验收。

## 协议与安全

- 请求固定 `https://api.abdl-space.top`，只设置新协议分类头，不采用版本号作为身份。无旧接口回退、无系统浏览器替代人机窗。
- 原生 transport 复用现有 system-only 敏感 OkHttp 客户端，禁 Cookie/Bearer、缓存、重定向、认证器和自动重试。请求 15 秒有界，响应最大 8 KiB，严格 JSON 拒绝重复字段、类型转换、未知字段、尾随内容和不匹配操作。
- `/challenge` 的 `expires_in` 是服务端剩余有效期，严格允许 1–300 的整数，而初次 required 仍严格为 300。超过最大值、0、负值、字符串和小数拒绝；服务端最终时限不依赖客户端计时。
- operation/verifier/token 只在进程内存，Intent 与 Bundle 只有非秘密 generation/owner 计数；进程死亡只能重新开始。邮箱不进入人机 Activity 的 Intent/URL，原验证码页既有邮箱参数不因此扩展。
- WebView 顶层只允许固定本站 HTTPS page，子资源限本站、Cloudflare 及必要 about 文档；禁 file/content、混合内容、权限申请、下载、外部窗口和 UA 伪造。`ABDLHumanCheck` 限 origin、主 frame 与当前 attempt，消息不含 token 或 verifier。
- 原生 transport system-only 与 WebView 的平台信任是两件事：WebView 沿用既有 App 配置（包含用户 CA），本轮未全局改变证书政策。TLS 错误始终 cancel，不调用 proceed；消息不能代替 Siteverify 和业务消费。
- 原有注册、密码、验证码验证、NBW、相册、地图和全局 API Controller 不在本轮重构范围。新匿名处理器不从 Cookie/Bearer 授权，外层既有 IP 跟踪/封禁仍执行。

## 服务端策略

off/risk/always 由两站管理员卡控制，revision CAS 防冲突，secret 不进入 App 或卡片。risk 默认邮箱 900 秒/2 次，可信来源 600 秒/5 次/3 个邮箱；最终原子派送入口结合已确认历史与窗口内 dispatching/unknown 保守占位，避免并发越阈。unknown 不冒称成功，明确 failed/cancelled 不计成功。硬限流和成本预算优先，验证不能绕过 429，预检不占邮件冷却。

后端详细合同见对应仓 `docs/app-human-check.md`。操作授权最长 600 秒、attempt 最长 300 秒，compact 回执保留 7 天；幂等承诺不超出该保留期，不承诺外部邮件 exactly-once。

## 验证与发布状态

实现期独立源码复核修复了已成功事务 TTL 锁死重发和旧 WebView 失败回调误取消新 attempt；两处均有真实状态机/Activity 回归。最终同一次串行相关回归 **176/176 通过，0 失败/跳过**，包括人机 60 项、NBW 登录/同意/回调、私信输入法及相册/封面缓存；随后同命令 `assembleRelease` 成功，28040 个源码/配置前后指纹一致。不是全仓 Android 测试通过声明。

8 张深浅色原生 Canvas 图已经检查，提示插画/文字/主题、居中加载与关闭入口、失败和继续状态可见。初始化与继续使用既有依赖内的主题色 CircularProgressDrawable，真实循环动画，不以页面完成伪造百分比；Robolectric 将无限 ValueAnimator 截为一次 repeat，测试核实其原始 INFINITE 配置而不错误要求截断后仍 isRunning。受控页面/消息不是实际 Cloudflare widget；真机仍未验。

初期日志保留了 TLS 测试构造限制、Activity shadow 类型错误、CountDownTimer fixture 不自动计时和新 capability probe 预期差异，均修正夹具或精确合同，不放宽权限/取消/服务端验证断言。首轮扩大集 176/175/1 是动画 shadow 断言位置问题，修后最终 176 全过。

正式签名 release 测试包：`/home/ZYongX/projects/ABDL-Space-3.2.0-code34-human-check-test.apk`，151747108 字节，SHA-256 `abec6a65bb9f54e75ff447ada22283462581b88c24fff9d1423eb813afbf7eb3`。仍 3.2.0/code34，非 debuggable，既有正式证书 `fd2098a3d3493222c247a7ba2787ec48fd8f62d38e9432ed4d6290a057dea475`；apksigner、ZIP CRC 和 16KiB 对齐通过，旧包保留。证据在 `/home/ZYongX/projects/app-human-check-evidence-20261028/`，含 final-report、失败日志、原生图和 APK 校验。

本轮只交付 develop 与既有正式签名 release 测试包，不推 main、不更新正式下载、不执行生产迁移 0097、不创建或旋转 widget、不修改密钥、允许域名或 WAF。尚未发布新后端时，新版邮箱发送会明确不可用，不回退旧接口；测试包构建不等于生产功能已经可用。真实 widget、真实邮箱送达及真机闭环未代验。
