# 自由监听降噪 · 0.6.3

## 交付范围

GTCRN 已接入正式应用的**自由监听麦克风路径**，默认开启。在「设置 → 自由监听降噪」可关闭，下次开始监听生效；关闭回到原基本人声过滤。用户无需配置模型文件或算法参数，不改变所选千问模型、账号、音量或历史。

屏幕字幕保持 0.6.2 边听边显示的路径，不创建 GTCRN 实例、不增加整段等待；面对面按住说话也不接入本次增强。GTCRN 在本机运行，不增加云端模型调用。

## 链路与边界

1. 用原始麦克风波形和实际播报参考核对回声。不能先降噪再去匹配未经同样处理的播报。
2. 播报期间，独立人声检测持续检查去回声后的声音；能量变大不再直接导致播报降音量。拒绝噪声候选后重新开放检测，后面真的有人说话仍可插话。
3. 约 480ms 有界句首缓存保留判断期间的开头。只判断为可能人声的回声残余，也须有独立人声证据才能送去增强，避免零散残音被增强后误认。降音量后仍重新核验，确认才真正停播。
   识别侧另保留最多 640ms 抢话回看；同一次尚未确认的开口再次核验时，不重复清空人声模型的连续状态，避免雨声之后说话反而丢掉开头。
4. 发往识别的声音下采样到 16kHz，按 256 点步进经 GTCRN，再经 Silero 人声过滤；非人声替换为等长静音，保持云端输入时钟。
5. 正常结束排出剩余有效样本；取消不补发待判声音；每次监听独立创建和关闭状态。处理故障停止并明确提示“本机声音处理失败”，不假装成千问连接失败、不悄悄绕过增强。

增强器自身为 16ms 前瞻，不是额外等一句话；人声确认、回声缓存、网络和云模型另有延迟。不能将 16ms 宣称为最终字幕延迟。

## 模块和来源

- `audio/processing`：可移植 Kotlin 实现，FFT、GTCRN 循环缓存、Silero 检测、独立抢话证据及流式门控。
- `core/PlaybackFloor`：播报期间的句首缓存、降音量与正式打断状态机。app 通过别名复用。
- `app/audio/SpeechGate`：仅负责加载 Android 随包资源；`LiveDesk` 在每次录制开始固定开关值。
- Android 和电脑使用同版 ONNX Runtime 1.23.2。桌面原生库只用于测试，不随 APK 打包。
- 模型为 GTCRN 上游固定提交 `502ebfab64da7c4a9af78dcb9c6ceef1ebb01c73` 的 `stream/onnx_models/gtcrn_simple.onnx`，535190 字节；原文件未改，SHA256 为 `b4718df6228e7bdf1a8a435cf98f838636eb2fd331acabf86ba87c5192ebcb87`。[上游](https://github.com/Xiaobin-Rong/gtcrn/tree/502ebfab64da7c4a9af78dcb9c6ceef1ebb01c73)
- MIT 许可证、作者及固定来源随包位于 `assets/noise-reduction/`。选择依据是已有 24 组离线初筛和复用当前运行库，不宣称优于所有版本的 RNNoise/APM。

回声回归额外暴露了采样转换问题：旧全频核对只模拟采样保持，平滑插值后的安静辅音可能漏过。现在同时核对两类参考波形，仍要求高相关且剩余能量在环境声范围内，不能仅凭音量删掉另一位说话者。

## 自动化验证

电脑接口直接加载**随包的真实 GTCRN 和 Silero**，不是用假降噪器代替。覆盖：

- 两段纯雨的增强及识别输入；播放期间既不正式打断，也不短暂压低音量。
- 中法语音叠雨的完整过滤链与旧门控对照；轻声句首保留。
- 中法、轻声/普通音量、16/48kHz、雨声后再说话的完整回声→抢话→降噪→人声过滤链。
- 12 组插值重采样、低音量回声、35/100/215ms 延迟样本，输出给识别的非零样本为零。
- 任意分块、准确样本长度、1/255/257/511 等短尾、停止前持续输出、取消/结束/关闭幂等、损坏模型错误分类。
- Kotlin 输出与独立 Python 参考逐样本比对，固定 16013 点样本的 PCM 均方根误差约 0.008（16 位采样单位）。
- Robolectric 调用正式 `LiveDesk.record()`，验证仅自由监听启用、开关持久重读与下次生效、初始化/输入失败关闭连接并清除运行状态。
- 保留完整字幕回归：28 秒输入第 4/8/16/27 秒显示、LiveTranslate 3.5 双连接、三段五轮连播、记录保存。

```powershell
.\gradlew.bat :audio:processing:test :core:test :provider:qwen:test :evaluation:qwen:test :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

已新增无需账号、麦克风或扬声器的 Android 原生验证接口，手机接好后由开发运行：

```powershell
adb -d shell am instrument -w -r -e class dev.polybridge.app.ListeningDenoiseDeviceTest dev.polybridge.app.test/androidx.test.runner.AndroidJUnitRunner
adb -d shell am instrument -w -r -e class dev.polybridge.app.StreamingListeningRegressionTest#rainDuringPlaybackCannotBypassSpeechDetectionWhenItTriggersTheFloor dev.polybridge.app.test/androidx.test.runner.AndroidJUnitRunner
```

## 验证边界与下一步

最终电脑结果：164 项 JVM/Robolectric、6 项独立 Python 测试全部通过；Lint 0 错误、33 提示。主包与测试包构建并通过签名验证，确认主包为 0.6.3/6003、不包含桌面原生运行库。[脱敏证据](evidence/2026-11-05-listening-denoise-0.6.3.json)

本轮没有连接手机或可用模拟器，没有调用云端模型。电脑验证不能证明 Fold5 实际温升/耗电、自然房间所有噪声或文字准确率；电视人声、其他人交谈仍可能被当作语音。音量条反映原始输入，有噪声时仍会波动，并不表示已上传假人声。

下一次接线后补 Android 原生接口、真实房间轻声与播报插话、连续运行耗时；千问准确率测试仍受既有费用预算约束。本轮不消耗新的云端额度，不清理旧费用占位。

## 本轮用户需求原文

> 这个版本我直接发送到手机上安装了，要28s的语音结束后才开始翻译，你还是改回上一版吧，体验不好。 那些降噪算法可以用在实时监听的地方，自由监听模式下容易被环境音打断，而且会翻译错误的环境音

> 继续啊，能不能增加一点能动性，我都说了，你就做完后再说

恢复字幕已在 0.6.2 完成，本轮在此基础上交付自由监听的实际增强，不再仅交选型研究。
