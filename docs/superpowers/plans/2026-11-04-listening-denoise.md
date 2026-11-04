# 自由监听降噪落地计划

> **For agentic workers:** 使用 superpowers:executing-plans 顺序执行，用户明确不允许自动子代理/新 worktree，不再为已授权步骤逐次请求继续。

**Goal:** 交付默认可用、可关闭的自由监听降噪，并减少环境声引起的临时降音量和正式打断；保持 0.6.2 实时字幕。

**Architecture:** 可移植 `audio:processing` 模块提供 GTCRN 流式增强、Silero 人声门控和独立的抢话前人声证据。回声匹配仍用原始麦克风波形，匹配后的残余音再增强，不能用降噪后的波形去匹配未降噪的播报。抢话前证据只判断、不向云发送；静音后的确认和句首缓存仍保留。

**Tech Stack:** Kotlin/JVM 17、现有 ONNX Runtime 1.23.2（Android 与桌面测试同版本）、JUnit/Robolectric、GTCRN 535190 字节固定模型。

## 最新授权原文

> 继续啊，能不能增加一点能动性，我都说了，你就做完后再说

不停止在候选研究。实施、测试、打包、文档、版本和本地 Git 都完成再交付；手机不在不阻挡电脑可做的工作。不能把无真机或云端验证写成这些已通过。

## 设计取舍

选择上一轮已用本项目 24 组音频初筛的 GTCRN，可复用 ONNX、16kHz，适配器已有独立参考。RNNoise 要引入 48kHz 原生链，APM 需要统一回声架构，此轮不同时堆叠。不是宣称 GTCRN 在所有场景优胜。

只对 LISTEN 开启增强和抢话前人声确认；CAPTIONS 保持原 Silero 门控、无增强实例。设置增加“自由监听降噪”一个持久开关，默认开，下次监听生效，不暴露数十个内部参数。异常模型/处理错误停止并提示声音处理失败，不静默发送未处理声音；关闭开关可回到基本过滤。

## 任务

- [x] 添加 `audio/processing/build.gradle.kts` 与真实模型接口测试；用直通实现先证实雨声未压低的失败。Kotlin GTCRN 输出需与独立 Python 参考一致，验证长度、首尾、分块、结束前输出、关停、无效输入。
- [x] 移植 512 点频谱/256 步进、16ms 前瞻和 ONNX 循环缓存；保留资源所有权和线程隔离。模型、许可证、固定提交、摘要随包。
- [x] 将现有 SpeechGate 的算法与 Android asset 加载分离，默认基本模式逐样本保持；增强仅在进入人声判断前启用。用真实 Silero/GTCRN 验证纯雨、混合中法、轻声、16/48kHz、重检、取消/异常关闭。
- [x] PlaybackFloor 移到 core，app 保留别名以兼容；先用真实雨声复现临时压低音量，再增加不发送声音的独立人声确认。保持回声匹配先于降噪、原有静音复核和句首回看，验证雨声零误降音量/打断与真实插话保留。
- [x] 接入 LiveDesk、持久开关、明确错误提示；Robolectric 验证 LISTEN/CAPTIONS 分流、开关重读、停止、初始化失败资源释放。保持全部实时字幕回归。
- [x] 完整测试、Lint、主包/测试包构建、验签；检查 APK 只包含 Android 原生库、不误打包桌面运行库。版本 0.6.3，保留 0.6.2 恢复包。
- [x] 同步 README/CHANGELOG/NEXT_TASKS/降噪说明与脱敏证据，检查 main/remote 后提交当前分支。没有远程仓库不声称推送。

## 完成与审查记录 · 2026-11-05

164 项 JVM/Robolectric + 6 项 Python 全通过，Lint 0 错误/33 提示，主包及测试包构建验签通过。12 组回声、16 组插话及真实雨声模型回归；补修平滑重采样回声漏出、雨声后连续重置抢话判断导致的句首丢失。仅调整音频本地处理，屏幕字幕不引入增强。

按审查清单自行核对所有权、状态隔离、异常、API 31 兼容和包内容（用户禁止自动子代理）；明确导入解决模块移动后的测试编译歧义，替换 API 33 才支持的 Optional 无参调用。沿用当前分支，不合并、不新建工作目录。未连接手机/模拟器、未调用云端；设备原生与自然房间验收仍单独列为后续工作。[证据](../../evidence/2026-11-05-listening-denoise-0.6.3.json)

## 验收调用

```powershell
$env:JAVA_HOME='D:\Android\Toolchains\temurin-17\jdk-17.0.19+10'
.\gradlew.bat :audio:processing:test :core:test :provider:qwen:test :evaluation:qwen:test :app:testDebugUnitTest --console=plain
.\gradlew.bat :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
```

算法指标和协议模拟不代表真实房间识别准确率、vivo 硬件或 WhatsApp 验收。无新云评测费用，不读取私人历史、不动账号和音量。
