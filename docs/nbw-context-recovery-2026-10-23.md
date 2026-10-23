# 回复读取故障恢复与外站缺失提示

用户反馈回复NBW主帖被“回复读取失败”阻挡，本站及外站评论页泛server error。后端实际复现为已同步本站根的强NBW依赖；原生无映射有评论帖200正常。后端d74f040已发布Worker744/a93b4081，7个真实公开线程context全200，镜像保本站评论、外站部分不可用明确标nbw_unavailable/header，不伪装零评论。create_reply不再以get_replies可用为必要前置，而用官方合作主题列表精确校验，权限/生态仍由写接口强校验；未知楼层读验证失败保持明确错误。

## Android增量

- StatusContext新增primitive boolean nbwUnavailable，缺省false，保@AllFieldsAreRequired的数组及完整Status严格校验。
- GetStatusContext同时接受JSON nbw_unavailable和X-ABDL-Context-Degraded:nbw，两者OR；真正503/401/403/无效JSON仍走错误，不转空数组成功。
- Thread真实渲染本站评论后显示独立persistent Snackbar：“外站回复暂不可用，已显示本站评论”；本地空数组用“外站回复暂不可用，尚未取回外站回复”。重试重新读取同一root、inflight去重，连续降级不重复弹，恢复正常撤销并重置；退出/隐藏/销毁/root删除清理，不绕过已有deleted守卫。
- 不改变发帖AI、来源标识/主页/默认头像或版本号，不正式发布App。

## 验证与测试包

原两个测试类新增7项，最终同次 **11类82项全部通过，0失败/错误/跳过**：真实loopback HTTP验证JSON/header/严格数组状态、真实Thread Snackbar/重试/恢复/退出；完整保留此前75项。生产Java及测试重新编译，生产Kotlin未改复用已核源匹配输出。assembleRelease/verifyMapKeys成功。

正式签名测试包：
`/home/ZYongX/projects/ABDL-Space-3.1.0-code33-nbw-replies-recovery-test.apk`

- 151,512,865 bytes，SHA256 `5249d4e663af5d20914ca30d0d811b04dbdded02704ef839828c5327ee28facf`。
- 包名top.abdl_space.app，3.1.0/code33，min26/target35/compile37，非debug；四ABI/29native与旧包展开字节相同。
- 原证书SHA256 fd2098a3d3493222c247a7ba2787ec48fd8f62d38e9432ed4d6290a057dea475，V2签名/ZIP CRC/zipalign通过；本地NBW头像资源仍在。
- 未上传正式下载、不改latest/版本JSON、不推进Android main、不覆盖连接设备。旧包保留；新包用于看到明确外站缺失提示，旧包亦能渲染后端恢复的本站评论。

私有证据 `/home/ZYongX/.local/share/abdl-space-release/20261023-replies-fix/`：android-first-tests-build.log、android-final-tests.json/JUnit、android-frozen-source.json、release-artifact.json及delivery签名核验。无真实账户写入、外站create_reply持久化或真机UI验收，不能把mock/回归当已闭环。外站get_replies仍待合作方恢复，当前修复隔离故障，不造外站内容；本地和已验证主帖可用性已恢复。AI补充任务仍暂停。
