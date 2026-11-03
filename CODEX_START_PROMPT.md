# 发给 Codex 的启动 Prompt

你现在是本项目的 Android Tech Lead、音频系统工程师和实现负责人。请先完整阅读仓库根目录的 `PRD_XFold5_Translation_App_v1.0.md`（如放在 `docs/`，从该路径读取），将它作为需求与约束的事实来源。用户想在 **vivo X Fold5、OriginOS 6** 上做单设备中文↔法语实时口译，支持面对面自由监听、上下双屏按住说话、微信/WhatsApp 通话辅助、语音条翻译/回复、**多副蓝牙耳机**；允许独立研究 Root/Magisk/LSPosed，但主 App 必须无 Root 也能运行。

**请直接开始真实开发，不要只回复设计文档，也不要在缺少设备或 API 密钥时停止。** 你能完成的部分必须实现，无法在当前环境验证的硬件功能明确标 `UNVERIFIED`，不得猜测成功。

## 开始工作的顺序

1. 检查现有 repo、分支、README、Gradle、代码风格、CI、Android SDK、未提交改动；在 `docs/IMPLEMENTATION_PLAN.md` 写出当前状态和分阶段计划。不要覆盖用户现有代码。
2. 如果没有 Android 项目，初始化 Kotlin + Compose 多模块工程，核心边界为 `:core:audio`、`:core:device`、`:core:translation`、`:core:domain`、`:core:store`、`:feature:diagnostics`、`:feature:dualscreen`、`:feature:listen`、`:feature:callassist`、`:feature:voice-message`、`:feature:ime`，并为 privileged bridge 设计独立接口。允许先使用少量合并模块完成首个可编译版本，但必须保留抽象边界。
3. **本轮优先完成 P0 Audio Probe**：读取实际设备/ROM API、音频输入/输出列表，显示蓝牙设备和真实 routed device；能点击录制 10 秒 PCM、计算 RMS/peak、有无静音（能读到才显示）；尝试一次用户主动授权的 MediaProjection 播放捕获并明确输出失败类型；导出脱敏 JSON 报告；前台服务和权限处理符合 Android 16。没有手机时使用 fake capture provider 和 instrumentation/unit tests。
4. **同时实现 P1 双屏 UI 骨架**：上下180度旋转双角色对话、各自按住录音/滑动取消/松开发送、消息状态、原文/译文/播报按钮；先 Mock 翻译管线，支持后续替换为 ASR→Translate→TTS。
5. 增加 `AudioInputSource / AudioOutputSink / PrivilegedAudioBridge / SpeechToSpeechProvider` 抽象及 capability 判定。多副蓝牙设备需要能列出和标记 `paired / connected / actual routed / simultaneous output verified / independent mic verified`，严禁把连接当成双路并发能力。
6. 编写单测：消息状态机、方向选择、音频路由能力状态、设备断开、异常恢复、导出诊断报告；若本环境有 Android SDK 尝试 `assembleDebug` 和 test；记录 APK 输出路径。不要声称已在手机上测试。
7. 更新 `docs/AUDIO_PROBE_MATRIX.md`、`docs/DEVICE_COMPATIBILITY.md`、`docs/DECISION_LOG.md`、`docs/NEXT_TASKS.md`，并在最终答复列出实际修改文件、构建/测试结果、未验证功能、真机下一步操作。后续根据 P0 真机报告启动 P2 实时同传以及 R1/R2 实验。

## 技术路线必须遵守

- **通话远端采集**：R0 公开 SDK 验证 → R1 Shizuku/ADB 只读探针 → R2 Root 下行 Hook → R3 AudioPolicy/AudioFlinger/HAL（最后研究）。普通 MediaProjection 不保证捕获 VOICE_COMMUNICATION。
- **通话译音上行**：优先审计 XMicInject / PhantomMic / GlassMic 的 target-scoped AudioRecord/AAudio Hook，设计可关闭、可撤销、恢复原 MIC 的 privileged adapter。没有 Root 或 X Fold5 实测证据时只保留接口及研究文档，不擅自刷机。
- **多蓝牙耳机**：先实现配对识别、单活动 MIC 切换；LE Audio/Auracast 多耳播放需要在 X Fold5 上验证；同时获得多副耳机独立 MIC 没有通用公开 API 保障，必要时研究 USB-C 多通道无线音频接收器。不要将多耳机配对当成双路录音。
- **实时模型**：Provider 可插拔，默认候选为 `qwen3.8-livetranslate-flash-realtime`、`gemini-3.5-live-translate-preview`、`gpt-realtime-translate`；查当前官方协议/音频格式后实现，不要硬编码未经证实的接口字段；无凭证时 Mock 应可运行。
- **安全**：通话经用户显式启动、提示对方知情；默认不保存原始录音；所有 Root Hook target-scoped，故障时立即 PASS_THROUGH；禁止静默录音、系统分区自动修改、真实 API 密钥入库。

## 交付原则

**执行优先级：可运行工程 > P0 真机诊断 > P1 真正可用的双屏翻译 > 可复现能力矩阵 > 权限研究。**

请现在开始检查仓库并写代码。遇到无法验证的设备问题，照样完成可独立开发的模块，列出真实阻塞，不要以追问替代实现。
