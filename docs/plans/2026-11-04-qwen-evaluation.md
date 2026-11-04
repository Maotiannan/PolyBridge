# Qwen 隔离评测实施计划

> 执行方式：按 superpowers:executing-plans 在当前会话串行执行。用户禁止自动使用子代理，并明确选择继续当前分支；不创建新工作目录。

**Goal:** 在用户批准的 10 元首轮预算内，建立可重复的 Qwen 小样评测，记录失败、用量和内容，不改变已安装正式版本的模型选择。

**Architecture:** 新建 JVM `:evaluation:qwen` 工具模块，只作为 Android 测试依赖；纯函数解析厂商事件，独立费用日记先预留后联网。Android 测试只在本机读取应用已有加密凭据，固定样本直接发往同地域官方服务，不启动麦克风、播放器、字幕服务或修改个人记录。

**Tech Stack:** Kotlin/JVM 17、现有 Gson/OkHttp、JUnit4、Android instrumentation、Android AtomicFile。

## 全局约束

- 需求来源：[已确认的比较方法](../QWEN_EVALUATION.md)。用户回复「可以 开始吧」，批准 10 元预算。
- 正式应用 0.5.6 不替换；只有测试 APK 更新。密钥不打印、不导出、不写报告。只使用固定已授权样本。
- 每条结果包含模型、地域、输入时长、事件数量、原文/译文、用量完整性、错误分类；本轮固定录音文字不是个人数据。
- 价格以 2026-11-04 官方地域报价为基准，免费额度不算零成本。未知用量不按零结算；失败预留保留，禁止自动重试和自动无限晋级。
- 先检查本地 main：当前分支包含全部 main 提交，项目未配置远程，不能声称已同步 GitHub。

## Task 1 — 费用、事件与协议离线契约

**Files:** 新增 `evaluation/qwen/build.gradle.kts`、`src/main/kotlin/dev/polybridge/eval/{EvaluationBudget,EvaluationProtocol,EvaluationEvents}.kt` 及对应 `src/test/kotlin/dev/polybridge/eval/*Test.kt`；修改 `settings.gradle.kts` 只注册评测模块。

**Interfaces:** `EvaluationBudget.reserve(id, amount)` 在联网前同步保存预留；`settle(id, actual)` 仅在用量完整时结算，未知则保留预留。`EvaluationProtocol` 按候选生成仅文本配置。`EvaluationEvents.accept(json, elapsedMs)` 累积独立原文与译文，保持 delta/snapshot 区别，按响应编号去重用量；ASR 累计用量取最新而非相加。

- [x] 先写费用与事件测试，包括如下断言，运行看到失败，再实现。

```kotlin
budget.reserve("first", 6_000_000)
assertThrows(IllegalStateException::class.java) { budget.reserve("second", 5_000_000) }
assertThrows(IllegalStateException::class.java) { budget.reserve("first", 1) }
// 未结算预留必须在重新构造预算器后仍占额度。
assertEquals(6_000_000L, restored.reservedMicros)
```

- [x] 解析测试覆盖重复 completed/response.done、两个 item 的全文、迟到原文、旧版快照替换、新版 delta 追加、ASR sentence_id 与累计用量、失败和禁止音频输出。
- [x] 配置测试检查所有候选只要求文本；LiveTranslate 3.8 不能错用 3.5 的参数字段；ASR 使用二进制任务协议。
- [x] 执行 `./gradlew.bat :evaluation:qwen:test`，全部通过再允许设备联网。

## Task 2 — 有界网络探针与 Android 小样入口

**Files:** 新增评测模块 `EvaluationProbe.kt`、网络回放测试 `EvaluationProbeTest.kt`；新增 `app/src/androidTest/java/dev/polybridge/app/QwenEvaluationDeviceTest.kt`；只给 `app/build.gradle.kts` 加 `androidTestImplementation(project(":evaluation:qwen"))`。

**Interfaces:** `EvaluationProbe.run(model, account, pcm16, targetLanguage)` 返回脱敏 `ProbeResult`；有界建连、发送、等待和返回大小，不重试。账号参数仅存在运行进程，结果不含连接地址、凭据和原始服务错误全文。Android `AtomicFile` 持久化总预算，测试参数必须显式 `qwenEvaluation=true`，默认不联网。

- [x] 先用本地 WebSocket 服务测试成功握手、拒绝、错误终止、文本正确累积和总时长上限；不得测试成功即假定真实模型支持。
- [x] 首先实现不发送音频的账号/连接检查，检查测试地域与业务空间配置；不猜其他空间、不更改账号设置。
- [ ] 固定短法语和中文分别初筛，候选每个累计发送不超过一分钟；在每次网络调用前保存预留和运行编号，崩溃/取消也不会失去预算记录。
- [x] 运行 `./gradlew.bat :evaluation:qwen:test :app:assembleDebugAndroidTest`；只安装测试 APK，运行唯一 instrumentation，结束后才可安装或进行 UI 操作。

## Task 3 — 小样实测与按证据晋级

**Files:** `docs/evidence/2026-11-04-qwen-evaluation.json`，完整固定样本调试输出放忽略的 `app/build/qwen-evaluation/`。

- [x] 逐个执行候选，记录拒绝/不支持/超时，不把账号可用性失败归为翻译质量差；未发送音频和已发送音频的费用状态分开。
- [ ] 用相同法语/中文原音比较识别与译文，检查首字、首次译文、最后一字和响应完结。客户端没有收到的内容不虚构恢复。
- [ ] 只有完整、能计量且预算足够的候选继续；长测保持用户确认的播放中出字幕要求。若只有一个可用候选或账号不支持新模型，停止选优并报告证据，不反复扣费尝试别名。
- [x] 不为凑齐计划耗完预算。只有原文也完整的方案才能作为字幕候选；纯译文结果单独记能力缺口。

## Task 4 — 验证、报告与交付

**Files:** 更新 `README.md`、`docs/QWEN_EVALUATION.md`、`docs/NEXT_TASKS.md`、`docs/DECISION_LOG.md`。

- [x] 执行旧基线与新增离线测试；确认正式 APK 运行依赖不含评测模块。文档链接与 `git diff --check` 必须通过。
- [x] 核对手机正式安装包未替换；不留采集服务、不改耳机/机身音量、不清个人历史。
- [x] 报告真实通过与失败、估算用量费用和尚未结清的保守预留；不把估算冒充账单。保存本地 Git，说明未配置远程。
- [x] 对胜出模型的产品接入另列后续步骤，本轮不自动改变日常版本。

## 本轮检查点（2026-11-04）

基础设施和 13 次小样已完成，未勾选项仍待办，不宣布整份计划完成。新增 24 项评测离线测试、合计 98 项电脑测试通过；测试 APK 已安装，正式包不变。专属地址已确认三个新实时型号，Audio 和 LiveTranslate 3.8 通过受控 15 秒停顿前后两句检查；Omni 错误语种不晋级。只有 Audio 做了双向短句，完整同条件中法矩阵和 ASR→MT 尚未完成。

偏差与纠正：后台 socket 清理超时留档后改前台测试；旧地址失败后仅在用户提供专属空间时重测；LiveTranslate 未知文本输入单价不猜测。用户反馈“几乎没用”后，按已告知的旧批次 1 元余量调整保守占位并留审计。当前占位 7.002636 元不是账单，10 元总额未更改。下一阶段先补长连续讲话与真实采集，不自动发布模型替换。
