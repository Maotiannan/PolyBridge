# 稳定字幕与漏句恢复实施计划

> **For agentic workers:** 使用 superpowers:executing-plans，当前会话按任务执行；用户明确不使用子代理、不新建工作目录。设计依据为 docs/TRANSLATION_PIPELINE.md，用户已授权自主落地，不再逐个小步骤等待批准。

**Goal:** 确认的原文先保存再显示，完整译文随后补齐；中间结果、取消的半句译文不冒充最终字幕；网络乱序不静默漏句。

**Architecture:** 保留底层实时协议的临时事件，在 core 增加字幕专用发布边界；app 只对屏幕字幕启用。确认原文作为 TRANSLATING 记录持久化，Finished 只完成对应待译记录。协议层有界暂存先到的 response，复核不能悄悄重写已确认原文。

**Tech Stack:** Kotlin/JVM、JUnit、OkHttp MockWebServer、Android/Robolectric、Gradle。

## Global Constraints

- 手机不常连；无需凭据、不调用付费服务的自动测试优先。
- 不静默改字、不以静音推断 WhatsApp 消息身份、不把合成事件测试当作真实模型质量测评。
- 保留当前分支和用户数据；版本、README、CHANGELOG、测试证据、本地 Git 同步。

## 任务 1：锁定字幕发布与保存边界

文件：core/LiveConversation.kt、core/StableCaptions.kt、core/Conversation.kt（均位于 src/main/kotlin/dev/polybridge/core），对应 src/test 下测试；app/audio/LiveDesk.kt。

- [x] 新增失败测试：临时文字不显示，确认原文先显示，取消译文不显示；已发布字句不被重复、复核、撤回覆盖；重连与长会话不串句。
- [x] 新增 `Caption.sourceFinal` / `Finished.sourceFinal`，`StableCaptions.accept(LiveUpdate)`；sourceFinal 默认 Caption=false、Finished=true 兼容现有最终事件。
- [x] 新增 `Conversation.heardSource(line, origin)`，使用 TRANSLATING；`heard` 只完成相同 id/side/source/origin 的待译记录，不覆盖历史。
- [x] LiveDesk 仅 CAPTIONS 经 StableCaptions；最终原文耐久保存成功后发布；异常与结束收尾保留原文。
- [x] 执行 `:core:test :app:testDebugUnitTest`，检查失败原因→实现→全绿。

## 任务 2：协议顺序与翻译中断

文件：provider/qwen/RealtimeEvents.kt、RealtimeProtocol.kt、QwenSpeech.kt 与对应测试；core/RecoveringCaptionSession.kt。

- [x] 失败测试先重现 response.created 先于 input 到达时最终翻译丢失；相邻两句返回乱序不串句；重复事件不重开。
- [x] 有界排队未关联 response，仅按协议创建顺序匹配；以容量上限明确失败，禁止无限缓存。
- [x] 输出真实 sourceFinal，循环原文的 safeSource 不冒充 final；保护复核不能改写已显示原文。
- [x] 对 Audio/Omni 使用配置中的媒体停顿，不写死 800ms；仅使用官方支持的防打断配置。
- [x] 对已确认原文的取消/空回复启用有界一次文字翻译补齐；不用重听整段、不无限重试；失败仍保留原文。
- [x] 本地 WebSocket/HTTP 接口测试真实解析→稳定发布→持久记录，包含长会话、迟到、取消、失败及停止。

## 任务 3：交付与验证

- [x] 补字幕“正在翻译/未完成”的明确状态，不把正在翻译写成正在听。
- [x] 全量强制执行测试（不是只读取 UP-TO-DATE）：`:core:test :provider:qwen:test :evaluation:qwen:test :app:testDebugUnitTest`。
- [x] 运行 `:app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`。
- [x] 版本更新至 0.6.1；同步 README/CHANGELOG/NEXT_TASKS/TRANSLATION_PIPELINE 和本轮证据。
- [x] 自审 diff 和测试边界；提交当前分支。真机采集、模型准确率、噪声算法收益单列待验证，不能宣称已通过。

## 用户本轮原始要求

新增回归任务（用户在打包阶段补充）：以 LiveTranslate 3.5 为可用性基线。复现并修复忽略原文—译文关联、缺失时间戳回排、前一段失败误删后段响应；本地两路 WebSocket 连续三段重复五轮，覆盖关联信息晚到与第三段先返回，不能把协议模拟当作真实第三段事故已定位。

> 我刚刚又测试了手机上的版本，换回livetranslate3.5后我发现可用性还是有的。所以你的所有进步应该建立在这个基础以上继续改善。我发现屏幕翻译有个问题，我翻译了两段语音之后，第三段只能跳出两个词然后又跳回已经翻译好的第二段语音去了，不论重新多少次都没用。这是个bug要修复。
>
> （返回应用里的第三段记录）只有两个词

> 我倾向出现后就不再改字。可以先出现原文，因为原文说出来就不会变了对吧，译文稍微晚一点出现没关系，关键是要准。
>
> 能不能记住，不要我说一句你动一下，你要有自主性
>
> 目前手机不能一直插着，你想办法自己验证吧，我晚点再插上手机让你验证。你既然不用我帮你调参，那就落地为止。期间的测试你自己做好！我希望一次过关这次

最终验证：152 项电脑测试零失败；Lint 零错误/31 提示；主包及测试包构建验签。全部检查在当前分支执行；依用户要求自行审查，未启用子代理。最终现场验收仍待接线，不能保证实际第三段事故已消失。证据：docs/evidence/2026-11-04-stable-captions-0.6.1.json。
