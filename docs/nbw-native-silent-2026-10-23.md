# 本站帖子外站评论静默失败

用户明确要求：真正本站帖子拉取外站评论失败时不频繁报错，静默保留本站评论。同时NBW帖子拉不到及真实外站评论仍需后端处理，不能只把提示隐藏当恢复。

## 最小App修改

ThreadFragment仅按mainStatus.id来源决定是否显示外站缺失Snackbar：nbw_外站root/floor保留一次可重试提示；本站p_/c_/数字legacy ID即使已有nbwTid、作者已绑定nbwUsername，也静默。展示入口即时移除旧Snackbar，过期retry回调同样守卫，不发多余请求。不修改GetStatusContext/StatusContext flags及真正HTTP500/503/权限错误，不把本地失败改成功。仅3文件，strings/Kotlin/版本不改。

原两测试扩充native metadata保留、raw错误、p_/numeric/c_/已双发+绑定/旧提示清除/旧retry拒绝及外站floor仍可提示。最终同次 **11类88项执行全过，0失败/错误/跳过**（方法数受CreateStatus参数化影响，以最终JUnit执行计数为准，不按口估82/88拼接）。生产Java与测试重编译，Kotlin生产未改复用已验证source-matched输出；release构建及verifyMapKeys成功。

## 签名测试包

`/home/ZYongX/projects/ABDL-Space-3.1.0-code33-nbw-native-silent-test.apk`

- 151,512,601 bytes；SHA256 `d22286437ce55c1626e8698cbd578c60258404c4e6c0ab79217aa25a480f0258`。
- top.abdl_space.app /3.1.0/code33/min26/target35，非debug；既有证书 fd2098a3d3493222c247a7ba2787ec48fd8f62d38e9432ed4d6290a057dea475，V2签名/CRC/zipalign通过；四ABI/29native保留且字节与旧包相同。
- 旧包保留，不更新Android main/正式下载/版本JSON，不覆盖设备。本包是静默体验修复，后端时限修复可独立上线。

## 对应后端与真实边界

后端75207fa已上线：本站5秒只读deadline会让limit20超时，而limit5可取；改只读12秒/分页总25秒，外站root详情去掉get_replies前置。真实线上20/40分别返回20/40帖，merged重新含NBW、root详情200。get_replies在perpage20仍返回JSON503并Retry-After，actual_external_reply_items=0、nbw_unavailable仍true；不能称外站评论已经恢复，不会因本站静默而掩饰这个事实。

证据私有目录 `/home/ZYongX/.local/share/abdl-space-release/20261023-nbw-fetch/`：android-silent-native-first.log、android-final-tests.json/JUnit、冻结source、release-artifact、delivery签名与after真实HTTP。未代真实账户写入或真机覆盖安装；AI补充任务保持暂停。
