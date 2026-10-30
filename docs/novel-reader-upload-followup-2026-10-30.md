# 小说阅读器、玻璃采样与上传错误跟进

## 范围

本轮响应测试反馈：液态底栏仍异常、封面上传仍失败、阅读器超出了只调整按钮显隐的要求，以及保存成功长提示常驻。用户已回退旧App并要求从代码分析后修复，因此不操作、安装或覆盖手机当前App，不发送真实投稿、COS上传或审核请求。

开发基线为Android `da4a9cb4`，只在功能分支修复并回合 `develop`，不推进Android `main`，不发布正式下载，不动V4、两站管理端或并行AI provider。配套后端 `6842ebe` / Worker801已经上线，本轮不更改后端生产源码、不重放迁移。

## 修复边界

### 玻璃采样

小说页面原底色只在捕获容器祖先Box绘制，真实捕获实现先清透明再绘制子树，无法包含祖先背景。现在满屏不透明小说背景位于捕获内部ComposeView的child内容中；导航仍是独立兄弟节点，避免把玻璃本身再次采入。不改共享shader或无证据地增加逐帧捕获循环。

新测试使用生产 `NovelCapturedPage → ComposeView → LazyColumn` 的真实像素，检查明暗主题留白不透明、导航兄弟不入采样、滚动和封面更新改变实际条带。软件环境可模拟硬件资格门禁，但不是RuntimeShader/GPU折射验收。

### 阅读器

恢复上一版的覆盖式工具栏、全宽HorizontalPager及页内边距，不再因工具栏显隐挤小正文、改变分页viewport或重建Pager。host两按钮回到原有右下位置和间距，只随工具栏显隐；非玻璃使用原Material按钮，玻璃仍沿既有材质开关。

保留实际字体和像素约束分页、未修改原文连续范围、Unicode字素、极小窗口兜底以及真正字号/窗口变化时的anchor重排。没有回退为固定字符估算，也没有改默认fling或动画曲线。

### 保存反馈

手动保存不再写入常驻的“已保存到本机，不会自动上传”notice；底部在干净状态不显示重复保存成功文案。正在保存和未保存修改仍有状态，预览入口不移除；保存失败、远端冲突和恢复文稿通知保留。10秒只本地保存、不自动上传的行为不变。

### 封面上传

成功链仍为generic authorize/PUT/complete，不凭猜测改purpose、域名、预览要求或增加图床fallback。修复共享上传器将COS XML/代理HTML强行按JSON解析、进而掩盖HTTP状态的问题：失败有结构化阶段、HTTP状态和安全分类，错误正文有界容错解析；封面层不再用异常message正则猜状态，也不展示签名、token、URL或原始响应。

新增共享旧动态上传兼容、XML403、HTML502、空/畸形/超大错误体、complete409/422/502、取消和注销隔离。采用生产6842ebe签名函数生成的fixture黄金向量，并用Node和Java独立HMAC校验及OkHttp真实BridgeInterceptor检查Host、Content-Type和Content-Length。隔离生产后端的本地Hono/SQLite测试另走完整authorize、complete及对象HEAD分支。

**此次修复了确定的错误处理缺陷，不能据此声称真实COS上传已经成功。** 代码契约检查未证明用户失败的实际阶段；generic目录权限、真实网络/代理或complete对象HEAD失败仍需脱敏阶段证据，不能把本地模型通过当成真实上传验收。

## 验证

最终同一源码执行App相关 **42类、263/263项通过**，零失败、错误或跳过；同次 `assembleRelease` 成功。28186个源码/资源及构建配置文件的SHA256前后无变化。本地编辑核心25/25、阅读器核心38/38已分别实际执行通过。上传客户端26/26与隔离后端真实函数3/3是单独执行记录，不混加为同一次总数，均无外部COS请求。

真实采样回归两项通过：明暗主题逐像素alpha=255、导航兄弟不入采样、滚动及封面替换改变实际条带，同一个生产listener保留且backdrop generation精确更新。首轮测试将enabled=false的生产binding与手动listener混用，封面重组relayout后被disabled binding正确清理，已改为真实enabled binding。随后又暴露了测试对View硬件属性的错误前提，移除该不适用前置和不必要AttachInfo改写，保留所有listener/generation/Bitmap identity和像素断言。阅读器按钮原Material40dp视觉区域与48dp触控区域已用上一版真实Row渲染精确对照，不以放宽像素误差掩盖失败。

阅读器6项真实Compose/native回归包含工具栏显隐前后正文约束、文本、Pager identity与全宽bounds不变，无重分页spinner，原按钮布局、目录交互、真实左右滑动和原文连续性。编辑器手动/自动保存不写常驻成功提示；保存不减少正文高度、保持IME composing，恢复文稿重要提示仍保留。原21/21设计页及新阅读器/编辑器截图已产出，软件像素不代表GPU折射或真机流畅度。

正式签名release测试包：`/home/ZYongX/projects/ABDL-Space-3.2.0-code34-novel-reader-upload-fix-test.apk`，167765542字节，SHA256 `ea3173959f0166a855ca145875c7b1d5b222d051f45139e775fb96bc11f3db42`。包名 `top.abdl_space.app`，3.2.0/code34、非debuggable；既有正式证书、CRC、16KiB对齐及完整宋体核验通过。旧测试包和正式下载保留。本轮没有覆盖安装用户已经回退的App。

构建有D8 8.9.32重写较新Kotlin metadata的告警，最终构建退出0并产出已核验APK；不能称无告警构建。本轮不为了清告警升级Kotlin/Gradle/AGP，也不把它混作四项用户问题的修复。

原始证据位于 `/home/ZYongX/.local/share/abdl-novel-reader-upload-20261030/`。真实COS失败的运行阶段仍未知，错误处理修复不等于已验证上传成功；硬件液态玻璃、真机翻页流畅度与真实上传闭环均未验收。
