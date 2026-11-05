# 赞助者支付宝支付渠道（2026-11-05开发中，未发布）

## 范围

用户要求现有套餐选择页不变，点击“成为赞助者”后进入支付渠道页：顶部所选套餐；中间支付宝+红实心“推荐”标签、爱发电聚合支付+镂空“支持微信”标签；底部“跳转支付”。支付宝调用原生SDK，爱发电进入原引导页，保留原计时/答题/URL/SKU安全；图标来自用户指定的本地SVG转原生矢量，不执行SVG内容。

基于Android develop2c23d333，在feat/sponsor-alipay-checkout-20261105开发。只develop与原证书release测试包，不自动发布、安装设备或真实交易，不改V4/阅读器/原套餐布局。支付宝默认关闭、当前未签约/无有效生产支付配置，沙箱未创建；官方CLI授权不等于开通或真实可付。

## 金额与能力

未证实“AppPay个人单笔50元”规则。官网0.01～50元是按量付费服务单价，不套用App移动收款。实际本收款主体的AppPay单笔与累计额度须商家平台核实；后端limits_verified未知时禁Alipay，不虚构无限额或固定5000分。金额不支持时仅爱发电，不拆单/分次绕过额度；其他未开通/未配置/额度未确认给出明确原因。

## 支付安全与恢复

新页不自行信任Bundle金额，服务端只按plan ID/version重新定价并持久不可变快照。客户端只接收order_str并交官方SDK后台调起，商家私钥/签名留后端；SDKstatus成功/取消/处理中均不作为赞助到账证明，查询同原订单、实际履约GRANTED后刷新Me/SponsorRefresh。未知结果不得自动再付或切爱发电再次收费；恢复仅保存账号绑定订单标识及幂等操作号，签单和SDK原始结果不写偏好/Bundle/log。

爱发电仍原站外付款+兑换码，不因新付款通道移除；插入中间页后的“直接兑换”应回原中心，不新建中心或错退渠道页。

## 当前状态

客户端生产及支付专项已落盘，验证进行中。固定官方SDK `com.alipay.sdk:alipaysdk-android:15.8.42`，官方POM/AAR来源静态核实；AAR无JNI、包含consumer ProGuard及6个官方活动，不改签名/版本/最低Android版本。实际SDK元数据与Manifest首轮检查exit0，精确移除无关RC/HK/tbopen查询后复核最终merged XML仅增加国内正式钱包visibility，原QQ/http(s)保持。

首次完整编译在resource merge阶段内存/I/O高压，持续23分钟未进入业务诊断，父主动停止，仅本轮遗留Gradle定向结束；日志/PSI保留，不标通过。随后必要复核修正为支付恢复Store按owner/operation/order CAS、坏恢复ID零网络受控拒绝、新operation隔离旧terminal order、回调fence，并改新资源为英文default+中文zh-rCN；原三项独立静态核修已闭合，实际runtime仍待本轮专项。

后端58/58支付专项与完整2263/2254pass/同9旧基线、types315零新增、lint/dryrun均已完成；这不是客户端支付/真实付款验证。不把先前通用61项测试当本轮赞助闭环通过。测试仅受控网关/fakeSDK，产品签约、实际账户额度、沙箱、公网通知、客户端真实SDK和设备安装均待办。

## 构建阻塞与候选检查点（2026-11-05）

- `android-payments-targeted1`使用768MiB堆，`:mastodon:mergeReleaseResources`在约7分钟后明确`Java heap space`退出；测试实际执行0项，未生成客户端验收结果。
- 随后独立`mergeReleaseResources`重试使用1536MiB堆，仍持续约40分钟在全量资源编译中换页；JVM约1.8GiB被换至磁盘，整机可用内存约330MiB，memory/I/O PSI持续高压。线程附加无响应，父主动停止该任务及其已核对的本轮daemon，没有关闭用户浏览器或其他进程。
- 只读复核未发现cgroup内存限额；两个新增矢量仅约1.8/2.8KiB，新SDK仅4项资源/无JNI，新中英资源XML良构且40键一致。AGP8.9.1的JVM资源编译选项已强制，不臆造禁用参数、不删旧字体/语言/资源、不clean缓存绕过。
- 当前源码和测试只保存在功能分支候选检查点，**未完成Android全模块编译、客户端专项、实际UI截图或原证书release测试包**，不合入develop或正式分支冒充交付完成。后端候选通过的验证独立保留。

继续时先确保有足够可用物理内存，在相同分支沿用已有缓存串行运行release资源编译→支付8类专项→赞助及相邻联合回归→原证书assembleRelease/二进制核验。不要并行重工具，不为截图启用真实支付或设置未经核实的额度。代码、沙箱、签约仍属未生产可用候选，不请求用户提供尚不存在的支付页截图。

诊断与恢复脚本：`~/.local/share/abdl-sponsor-alipay-20261105/`；关键`android-payments-targeted1.log`、`android-production-compile1-stopped.json`、`android-resource-retry1-stopped.json`及pressure记录。尚无本轮APK，不把上轮NBW包6f352765当作已含支付宝功能的包。

## 只读诊断与辅助验证补充（2026-11-05 18:29 CST）

- AGP8.9.1实际字节码复核：资源请求在服务关闭时按worker数分组，以noIsolation在Gradle同一堆逐文件编译；未找到可证有效的独立batch-size或等价native XML分流参数。部分映射26578项中仅4604个目标存在；merger.xml可先于编译队列完成写入，不是成功检查点，不手动拼接或强制跳过资源任务。
- 上轮NBW6f352765和D1db383d07成功出包的mergeReleaseResources均为UP-TO-DATE；两次1024MiB/SerialGC成功不能证明本轮全量资源重编译可在相同内存完成。旧资源归档与当前源码未证匹配，不作替代。诊断后没有重复启动同环境的长时间资源编译，也没有关闭用户其他进程。
- 在独立私有目录用本机JDK21的javac --release17重新编译当前SponsorPaymentCoordinator、SponsorPaymentModels、SponsorPaymentRequest、AlipaySdkAdapter及相邻SponsorRequest/SponsorModels六份生产源码，原样编译现有fixture与两类JUnit；javac/JUnit各限256MiB、串行执行，**19/19辅助逻辑测试通过**。类加载来源与源码/真实依赖SHA固定，无生产源码或测试修改。
- 此辅助验证使用过滤后的旧release相邻类缓存，未证明相邻缓存与全仓源码一致；SDK仅真实字节码静态编译并从JUnit运行classpath排除。没有真实HTTP、SharedPreferences、Activity生命周期、SDK付款或UI验收，不替代Gradle完整客户端门禁。原Gradle支付8类实际仍未执行，仍无APK/截图、未合develop/main。
- 结果：`~/.local/share/abdl-sponsor-alipay-20261105/aux-payment-javac-junit/20261105T102725Z-gk_pdbmx/result.json`（auxiliary_only=true、full_android_gate=false）；诊断`resource-diagnosis-followup.json`。代码检查点Android b9e0c28f、Backend231cbfc已推功能分支；本补充仅记录验证事实，不改生产或收款配置。

## GitHub验证备用方案（2026-11-05，尚未运行）

用户要求继续尝试编译，必要时使用GitHub自动化。本地`android-resource-retry2`以1536MiB堆、`--info`限时重试；确认全量非增量资源动作，8分钟仍未完成，489秒按上限停止。最低可用内存约185MiB，memory full avg10最高68%；只终止具有本轮唯一归属标记的daemon，未关闭其他应用。结果在私有证据目录`android-resource-retry2-bounded-result.json`。

原两份`build_and_deploy*.yml`含签名及Google Play发布，不用于本任务。新增独立`sponsor-payment-verify.yml`和`.github/ci/`验证脚本，触发只限专用`feat/sponsor-alipay-ci-20261105`分支的CI文件push或手动；普通支付feature/develop/main不因本文件自动构建或部署。此流程不读取repository secrets，不调用assemble/bundle/Fastlane、不生成debug/安装包；不上传原证书私钥，也不启用真实支付。

远端先以JDK21覆写本机专用java.home、使用SDK37/30，核验受跟踪字体/地图/QQ/极光文件与支付宝/AppKit依赖SHA，真实运行release资源任务，再执行固定27类赞助支付及D1/NBW/ROM相邻回归。测试有失败、跳过、未执行、源文件变化或缺少8张payment原生UI fixture均保失败；fixture仍需人工视觉核验，不当真机/签约截图。资源成功后仅导出正常输入寻址Gradle build-cache条目，供本地标准Gradle决定是否命中；不覆盖部分输出、不跳过任务，不保证跨环境缓存必命中。

本地Python/YAML/27类清单与受跟踪二进制SHA校验通过；真实Gradle依赖记录任务exit0、支付宝/AppKit哈希匹配，独立build-cache init加载help exit0。依赖记录通过artifactView.componentFilter在解析产物前排除project AAR，不提前读取未生成模块文件。以上仅配置验证，不是资源编译或客户端运行验收。

准备阶段GitHub CLI尚未登录，未主动dispatch或推专用CI触发分支。用户登录后已实际核验账号ZYongX09、PUBLIC仓库ZYongX09/ABDL-Space-APP；发现090950ee推送虽未匹配构建分支，仍产生GitHub配置校验失败记录42020182557（jobs为空，没有构建或部署）。此前“未启动Actions”的准确含义应为未执行任何job，不能声称GitHub没有生成run记录。

定位到作业级env不允许runner上下文，而初版使用runner.temp；本地YAML语法检查未覆盖GitHub上下文可用性。已改为step内使用RUNNER_TEMP写入GITHUB_ENV，不改变main/develop、原支付源码或原发布流程。修正版推现有支付feature不触发构建；启动前仍核验账号、仓库、workflow、ref及精确提交并取得确认。原证书release APK仍待真实远端编译测试、UI验收和本地签名核验；不能用配置修正或空jobs失败冒作客户端结果。
