# 全功能模型选择实施计划

> **For agentic workers:** 使用 superpowers:executing-plans 在当前会话逐项实现。用户明确不使用子代理，继续当前分支。

**Goal:** 交付可以整套切换、按功能覆盖且真实使用所选 Qwen 模型的手机版本。

**Architecture:** 生产 provider 的模型目录解析预设和覆盖；操作开始时创建不可变配置。不同实时协议统一为原文、译文、声音和来源事件，Android 只负责功能路由、持久化和播放。

**Tech Stack:** Kotlin/JVM 17、Android 36、Compose、OkHttp、Gson、JUnit、Robolectric、Android instrumentation。

## 执行结果 · 0.6.0

任务 1–3 的目录/持久化、协议适配、入口/来源/缓存已实现；任务 4 的设置交互和可见来源已上机验证；任务 5 的 120 项电脑测试、Lint、打包以及最终包的 12 项无云手机回归通过。真实产品短测 Audio 3.1 / ASR 3.1 / MT Plus 通过，TTS Instruct 服务返回 404，保留明确待验证标识。原定展开态和放大字体专项、更多新模型长测不冒充完成，移交 NEXT_TASKS。下面保留最初的实施检查项和示例，详细实测以 evidence 为准。

## Global Constraints

- 继续 `codex/caption-scroll-recovery`，不用子代理、不建工作目录；保留个人历史、凭据和音量。
- 默认当前方案；更新设置不打断已开始的任务，不跨模型自动降级。
- 正式模块不依赖 evaluation；自动付费验证沿用已有 10 元账本。
- source、tests、docs 用 apply_patch。本机 main 已包含在 HEAD，无远程可拉取。
- 用户已要求“交付我后再停下来”，设计审核已通过，不再以阶段审批中断。

## Task 1 · 目录、解析与持久化

Files: 新增 provider 的 `ModelSelection.kt`、`ModelSelectionTest.kt`；修改 `TranslationSettings.kt`，新增 app 的 `ModelSettingsTest.kt`。

Interface: `ModelFeature` 标识七项功能；`ModelSelection(preset, overrides)` 提供 `model(feature)`、`selectPreset`、`override`、`encode/decode`、`validate`；`TranslatorSettings.options(feature)` 生成 `QwenOptions`。

- [ ] 红：预设覆盖全部功能、局部覆盖不影响其他项、整体清空覆盖、未知型号拒绝、保存重启保持。
```kotlin
assertEquals(ModelIds.AUDIO31, ModelSelection(ModelPreset.AUDIO31).model(ModelFeature.CAPTIONS))
assertEquals(ModelIds.MT, ModelSelection(ModelPreset.AUDIO31).model(ModelFeature.TEXT))
```
- [ ] 运行 `:provider:qwen:test --tests '*ModelSelectionTest'` 确认缺实现失败。
- [ ] 实现目录白名单、版本化 JSON、地址校验和一次提交的设置保存；非法配置保留错误而非回退联网。
- [ ] 运行 provider 与 app 专项测试，保存红绿证据。

## Task 2 · 实时协议与单句适配

Files: 新增 `RealtimeProtocol.kt`、`QwenUtterance.kt`、`StreamingAsr.kt` 及各自测试；修改 `QwenLiveSession.kt`、`RealtimeEvents.kt`、`QwenSpeech.kt`、core 的语音接口。

Interface: `QwenSpeech.utterance(side, wave, audio)` 返回统一 `SpeechResult`；`RealtimeProtocol.configure(options, textOnly, side)` 返回实际 session.update；`LiveUpdate.Finished` 携带采样率和来源。

- [ ] 红：Live38 使用 output_modalities；Audio31 使用指令且单连接；纯字幕无 audio；源增量与快照分别处理；声音字幕 delta；交错响应；取消和超时。
```kotlin
assertFalse(RealtimeProtocol.configure(QwenOptions(liveModel = ModelIds.LIVE38), true, Side.FRENCH).contains("\"modalities\""))
```
- [ ] 运行 provider 专项确认失败，随后实现分型号配置、有限网络队列、结束等待与错误分类。
- [ ] 单句把同一次按住的所有分段合并，固定方向，取消丢弃迟到事件；独立 ASR31 流式结果累积。
- [ ] 用本地 WebSocket/HTTP 测试真实请求及最终文字/声音，不调用个人账号。

## Task 3 · 所有入口与来源/缓存

Files: `PolyBridgeApp.kt`、`MainActivity.kt`、`ConversationTranslator.kt`、`LiveDesk.kt`、`ConversationVoice.kt`、`VoiceClips.kt`、`CallAssistPanel.kt`、core `Conversation.kt` 和对应测试。

Interface: 录音任务保存 `ModelFeature` 与 `QwenOptions` 快照；`ModelTrace` 持久化请求/实际型号和来源；缓存严格区分独立 TTS 与实时译音。

- [ ] 红：面对面与中文转法语选不同模型；修改设置后旧任务不混用；重译使用文字项；分享/重播使用声音项；取消不保存成功。
```kotlin
assertNotEquals(settings.options(ModelFeature.FACE).utteranceModel, settings.options(ModelFeature.REPLY).utteranceModel)
```
- [ ] 红：实时声音不可按 TTS 型号命中；改文/改模型不复用；失败不自动手机朗读。
- [ ] 实现带来源工厂、开始时快照、可选一体单句、声音来源、实际采样率处理及持久元数据。
- [ ] 运行 app/core 回归，保持现有重试、长句、回声与历史测试要求。

## Task 4 · 设置交互与可见反馈

Files: 新增 `ui/ModelSettings.kt`；修改 `SettingsPanel.kt`、`Studio.kt`、对话/监听/字幕状态文案；新增设置 instrumentation。

Interface: `ModelSettings(selection, changed)` 编辑草稿。账号与模型在同一保存动作提交；单项提供跟随整套；选整套清除覆盖且显示说明。

- [ ] 先测草稿保存/取消及选择状态；用 Robolectric 测数据接口，instrumentation 补充真实点击。
- [ ] 复用 StudioDialog、ChoiceRow、主题与 48dp 触控区；整套四项，逐项展开七个功能，不堆叠系统弹窗。
- [ ] 标注实验质量风险、所选型号、实际生效/待生效；用户可明确重试手机语音但不得静默降级。
- [ ] 在合屏、展开、字体放大状态检查滚动和保存区可达。

## Task 5 · 全量验证与交付

Files: 更新 `libs.versions.toml` 单一版本源、README、NEXT_TASKS、DECISION_LOG、设计状态、CI 与 `docs/evidence` 脱敏证据。

- [ ] 执行 `:core:test :provider:qwen:test :evaluation:qwen:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`，逐项记录实际结果。
- [ ] 自行静态复核配置错误、异步竞态、历史兼容、音频格式及密钥泄漏。发现问题先加回归再修。
- [ ] Fold5 上串行安装、运行隔离设置/路由回归；使用已有有界评测接口和账本控制新增真实模型测试，不碰个人历史。
- [ ] 核验版本、APK 摘要、无采集遗留，恢复测试临时状态；更新本地 Git，无远程不宣称推送。
- [ ] 交付 APK、设置入口和实测边界。仅本人认证或预算外授权成为必须等待用户的阻碍。
